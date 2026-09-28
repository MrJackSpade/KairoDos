"""Build a private, manifest-keyed DOS catalog from an owner's eXoDOS v6 files.

The tool reads game archives and the LaunchBox metadata ZIP but never copies game
files into the catalog. Each ZIP member's path, size, and CRC form a stable
identity that is also computable from extracted user folders.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import xml.etree.ElementTree as ET
import zipfile

from PIL import Image

PREFIX = "sha256-dos-manifest-v1:"
HEADER = b"kairo-dos-manifest-v1\0"
MAX_FILE = 8 * 1024 ** 3


def safe_path(raw: str) -> str:
    path = raw.replace("\\", "/")
    if (not path or len(path) > 4096 or path.startswith("/") or ":" in path
            or "\0" in path or any(x in ("", ".", "..") for x in path.split("/"))):
        raise ValueError("unsafe game file path")
    return path


def ascii_lower(value: str) -> str:
    return "".join(chr(ord(char) + 32) if "A" <= char <= "Z" else char for char in value)


def hash_zip(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        members = [(item, safe_path(item.filename)) for item in archive.infolist()
                   if not item.is_dir()]
        members = [(item, name) for item, name in members if not name.lower().endswith(".exo")]
        if not members:
            raise ValueError("no game files in ZIP")
        roots = {name.split("/", 1)[0] for _, name in members}
        strip = next(iter(roots)) + "/" if len(roots) == 1 and all(
            name.startswith(next(iter(roots)) + "/") for _, name in members) else ""
        normalized = sorted(((item, ascii_lower(name.removeprefix(strip))) for item, name in members),
                            key=lambda pair: pair[1])
        if len({name for _, name in normalized}) != len(normalized):
            raise ValueError("duplicate game file")
        digest = hashlib.sha256(HEADER)
        for item, name in normalized:
            if not 0 <= item.file_size <= MAX_FILE:
                raise ValueError("game file is too large")
            digest.update(name.encode("utf-8"))
            digest.update(b"\0")
            digest.update(str(item.file_size).encode("ascii"))
            digest.update(b"\0")
            if not 0 <= item.CRC <= 0xffffffff:
                raise ValueError("missing ZIP member checksum")
            digest.update(f"{item.CRC:08x}".encode("ascii"))
            digest.update(b"\0")
        return PREFIX + digest.hexdigest()


def game_records(metadata: zipfile.ZipFile) -> dict[str, ET.Element]:
    with metadata.open("xml/all/MS-DOS.xml") as stream:
        root = ET.parse(stream).getroot()
    records = {}
    for game in root.findall("Game"):
        launch = (game.findtext("ApplicationPath") or "").replace("\\", "/")
        if launch.lower().endswith(".bat"):
            records[Path(launch).stem.casefold() + ".zip"] = game
    return records


def image_index(metadata: zipfile.ZipFile) -> dict[tuple[str, str], list[str]]:
    result: dict[tuple[str, str], list[str]] = {}
    for name in metadata.namelist():
        match = re.fullmatch(r"Images/MS-DOS/(Box - Front|Box - 3D|Clear Logo|Screenshot - Gameplay|Screenshot - Game Title)/(?:[^/]+/)*([^/]+)-\d+\.(?:png|jpe?g)",
                             name, re.IGNORECASE)
        if match:
            key = match.group(1).lower(), match.group(2).casefold()
            result.setdefault(key, []).append(name)
    return result


def image_preference(path: str) -> tuple[int, str]:
    folders = path.split("/")[3:-1]
    region = folders[0].casefold() if folders else ""
    priority = {"united states": 0, "world": 1, "": 2, "europe": 3,
                "united kingdom": 4}.get(region, 5)
    return priority, path.casefold()


def image_choices(images: dict[tuple[str, str], list[str]],
                  kinds: tuple[str, ...], title: str) -> list[str]:
    keys = (title.casefold(), re.sub(r'''[:'\\/?*"<>|]''', "_", title).casefold())
    for kind in kinds:
        for key in keys:
            matches = images.get((kind, key))
            if matches:
                return sorted(matches, key=image_preference)
    return []


def art_image(raw: bytes, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(io.BytesIO(raw)) as image:
        image.thumbnail((480, 300), Image.Resampling.LANCZOS)
        if image.mode not in ("RGB", "RGBA"):
            image = image.convert("RGB")
        image.save(target, "WEBP", quality=70, method=4)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, help="eXoDOS v6 root")
    parser.add_argument("output", type=Path, help="ignored local staging directory")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--art", action="store_true", help="extract local thumbnail candidates")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    previous_art = {}
    previous_manifest = args.output / "manifest.json"
    if previous_manifest.is_file():
        previous_art = {item["asset"]: item["sourceSha256"]
                        for item in json.loads(previous_manifest.read_text(encoding="utf-8"))
                        .get("artwork", [])}
    archives = args.root / "eXo" / "eXoDOS"
    metadata_path = args.root / "Content" / "XODOSMetadata.zip"
    with zipfile.ZipFile(metadata_path) as metadata:
        records = game_records(metadata)
        images = image_index(metadata)
        files = sorted((item for item in os.scandir(archives) if item.name.lower().endswith(".zip")),
                       key=lambda item: (item.stat().st_size, item.name.casefold()))
        if args.limit:
            files = files[:args.limit]
        database = sqlite3.connect(args.output / "hashes.sqlite")
        database.execute("""CREATE TABLE IF NOT EXISTS games (
            name TEXT PRIMARY KEY, size INTEGER, modified INTEGER, content_id TEXT, error TEXT)""")
        database.commit()
        rows: dict[str, list[tuple[str, dict]]] = {}
        missing_xml = []
        failures = []
        art_manifest = []
        for index, item in enumerate(files, 1):
            stat = item.stat()
            previous = database.execute("SELECT size, modified, content_id, error FROM games WHERE name = ?",
                                        (item.name,)).fetchone()
            if previous and previous[:2] == (stat.st_size, stat.st_mtime_ns):
                content_id, error = previous[2:]
            else:
                try:
                    content_id, error = hash_zip(Path(item.path)), None
                except Exception as failure:
                    content_id, error = None, str(failure)
                database.execute("REPLACE INTO games VALUES (?, ?, ?, ?, ?)",
                                 (item.name, stat.st_size, stat.st_mtime_ns, content_id, error))
                database.commit()
            if error:
                failures.append((item.name, error))
                continue
            game = records.get(item.name.casefold())
            if game is None:
                missing_xml.append(item.name)
                continue
            title = game.findtext("Title") or Path(item.name).stem
            description = (game.findtext("Notes") or "").strip()
            year = (game.findtext("ReleaseDate") or "")[:4]
            tags = [x for x in (year, game.findtext("Genre"), game.findtext("PlayMode")) if x]
            selected = {}
            for kinds, output_kind in ((("box - front", "box - 3d", "clear logo"), "boxArt"),
                                       (("screenshot - gameplay", "screenshot - game title"), "preview")):
                choices = image_choices(images, kinds, title)
                if choices:
                    digest = content_id.split(":", 1)[1]
                    variant = hashlib.sha256(item.name.encode("utf-8")).hexdigest()[:12]
                    art_path = f"art/catalog/dos/{digest[:2]}/{digest}/{variant}/{output_kind}.webp"
                    selected[output_kind] = art_path
                    if args.art:
                        target = args.output / art_path
                        with metadata.open(choices[0]) as stream:
                            raw = stream.read()
                        source_hash = hashlib.sha256(raw).hexdigest()
                        if not target.is_file() or previous_art.get(art_path) != source_hash:
                            art_image(raw, target)
                        art_manifest.append({"contentId": content_id, "kind": output_kind,
                                             "source": choices[0], "sourceSha256": source_hash,
                                             "asset": art_path})
            rows.setdefault(content_id, []).append((Path(item.name).stem.casefold(),
                {"title": title, "description": description,
                 "tags": tags, "artwork": selected}))
            if index % 100 == 0 or index == len(files):
                print(f"{index}/{len(files)}  matched={len(rows)}  errors={len(failures)}",
                      flush=True)
        shards = {}
        for content_id, variants in rows.items():
            if len(variants) == 1:
                record = variants[0][1]
            else:
                record = {"title": " / ".join(item["title"] for _, item in variants),
                          "variants": {name: item for name, item in variants}}
            shards.setdefault(content_id.split(":", 1)[1][:2], {})[content_id] = record
        destination = args.output / "catalog" / "dos"
        destination.mkdir(parents=True, exist_ok=True)
        for prefix, games in shards.items():
            (destination / f"{prefix}.json").write_text(json.dumps(
                {"schemaVersion": 1, "games": games}, ensure_ascii=False,
                separators=(",", ":")), encoding="utf-8")
        (args.output / "manifest.json").write_text(json.dumps({
            "schemaVersion": 1, "source": "eXoDOS v6 Content/XODOSMetadata.zip", "archives": len(files),
            "matches": sum(len(group) for group in rows.values()),
            "uniqueIds": len(rows),
            "sharedIds": {key: [name for name, _ in group]
                          for key, group in rows.items() if len(group) > 1},
            "unmatchedArchives": missing_xml, "errors": failures,
            "artwork": art_manifest if args.art else [], "shards": sorted(shards)
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Wrote {len(rows)} hash records, {len(art_manifest)} art candidates, "
              f"{len(missing_xml)} missing XML, {len(failures)} hash errors", flush=True)


if __name__ == "__main__":
    main()
