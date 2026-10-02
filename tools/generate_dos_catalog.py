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
from dos_catalog_review import apply_review, read_review
from dos_artwork import compact, expand, transform

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


def launch_configs(root: Path, records: dict[str, ET.Element]) -> tuple[dict[str, dict], dict[str, dict]]:
    """Read launch settings separately from LaunchBox's descriptive metadata."""
    archive_path = root / "Content" / "!DOSmetadata.zip"
    # The small configuration ZIP contains thousands of tiny members. Buffer it
    # once so an SMB source does not incur a network seek for every config read.
    with zipfile.ZipFile(io.BytesIO(archive_path.read_bytes())) as archive:
        entries = {name.casefold(): name for name in archive.namelist()}
        by_folder: dict[str, list[str]] = {}
        for name in entries.values():
            if name.lower().endswith(".conf") and "/" in name:
                by_folder.setdefault(name.rsplit("/", 1)[0].casefold(), []).append(name)
        by_game_folder = {}
        for folder in by_folder:
            if "/!dos/" not in folder:
                continue
            prefix = folder + "/"
            configs = {}
            for name in by_folder.get(folder.casefold(), []):
                configs[name[len(prefix):].casefold()] = archive.read(name).decode(
                    "cp1252", errors="replace")
            if "dosbox.conf" not in configs:
                continue
            by_game_folder[folder.rsplit("/", 1)[-1]] = {
                "folder": folder.rsplit("/", 1)[-1],
                "configs": configs,
                "exception": (prefix + "exception.bat").casefold() in entries,
            }
        result = {}
        for filename, game in records.items():
            application = (game.findtext("ApplicationPath") or "").replace("\\", "/")
            folder = application.rsplit("/", 1)[-1].casefold()
            if folder in by_game_folder:
                result[filename] = by_game_folder[folder]
        return result, by_game_folder


def import_launch_only(root: Path, catalog: Path) -> None:
    """Add launch configs to existing hash shards without rebuilding artwork."""
    with zipfile.ZipFile(root / "Content" / "XODOSMetadata.zip") as metadata:
        records = game_records(metadata)
    configs, by_game_folder = launch_configs(root, records)
    shards = {path.stem: json.loads(path.read_text(encoding="utf-8"))
              for path in catalog.glob("[0-9a-f][0-9a-f].json")}
    shards = transform(shards, expand)
    artwork_by_title = {}
    for shard in shards.values():
        for record in shard["games"].values():
            for variant in record.get("variants", {}).values() if "variants" in record else (record,):
                if variant.get("artwork"):
                    artwork_by_title.setdefault(variant.get("title"), variant["artwork"])
    # A display spelling must not prevent raw source titles from finding art.
    old_games = {key: value for shard in shards.values() for key, value in shard["games"].items()}
    for item in read_review()["records"]:
        previous = old_games.get(item["contentId"], {})
        if item.get("variant"):
            previous = previous.get("variants", {}).get(item["variant"], {})
        if previous.get("artwork"):
            artwork_by_title.setdefault(item["sourceTitle"], previous["artwork"])
    matched = 0
    missing_config = []
    new_catalog = []
    seen_by_id = {}
    new_variants = 0
    def metadata_for(item: Path) -> dict:
        game = records.get(item.name.casefold())
        title = (game.findtext("Title") if game is not None else None) or item.stem
        description = ((game.findtext("Notes") if game is not None else None) or "").strip()
        year = ((game.findtext("ReleaseDate") if game is not None else None) or "")[:4]
        tags = [value for value in (year,
                game.findtext("Genre") if game is not None else None,
                game.findtext("PlayMode") if game is not None else None) if value]
        return {"title": title, "description": description, "tags": tags,
                "artwork": artwork_by_title.get(title, {})}
    archives = root / "eXo" / "eXoDOS"
    for index, item in enumerate(sorted(archives.glob("*.zip")), 1):
        config = configs.get(item.name.casefold())
        if config is None:
            with zipfile.ZipFile(item) as archive:
                roots = {name.split("/", 1)[0].casefold() for name in archive.namelist()
                         if "/" in name}
            if len(roots) == 1:
                config = by_game_folder.get(next(iter(roots)))
            if config is None:
                missing_config.append(item.name)
                continue
        if item.name.casefold() == "blood (1997).zip":
            # This archive contains BLOODCD1.cue; its metadata still points to
            # the BLOOD121.cue name used by an earlier eXoDOS revision.
            config = {**config, "configs": {name: body.replace("BLOOD121.CUE", "BLOODCD1.cue")
                       for name, body in config["configs"].items()}}
        content_id = hash_zip(item)
        shard = shards.get(content_id.split(":", 1)[1][:2])
        record = shard and shard["games"].get(content_id)
        if record is None:
            record = metadata_for(item)
            shard["games"][content_id] = record
            new_catalog.append(item.name)
        stem = item.stem.casefold()
        if "variants" not in record and content_id in seen_by_id and seen_by_id[content_id] != stem:
            first = dict(record)
            record.clear()
            record.update({"title": first["title"], "variants": {
                seen_by_id[content_id]: first, stem: metadata_for(item)}})
            new_variants += 1
        elif "variants" in record and stem not in record["variants"]:
            record["variants"][stem] = metadata_for(item)
            new_variants += 1
        variant = record.get("variants", {}).get(stem, record)
        variant["launch"] = config
        seen_by_id[content_id] = stem
        matched += 1
        if index % 250 == 0:
            print(f"Launch metadata {index} archives, {matched} matched", flush=True)
    reviewed = transform(apply_review({key: value for shard in shards.values()
                             for key, value in shard["games"].items()}, read_review()), compact)
    for shard in shards.values():
        shard["games"] = {key: reviewed[key] for key in shard["games"]}
    for prefix, shard in shards.items():
        (catalog / f"{prefix}.json").write_text(json.dumps(shard, ensure_ascii=False,
            separators=(",", ":")), encoding="utf-8")
    write_folder_index(catalog, shards)
    report = {"matched": matched, "newCatalogRecords": len(new_catalog),
              "newFilenameVariants": new_variants,
              "missingConfig": missing_config}
    print(json.dumps(report, indent=2))


def write_folder_index(catalog: Path, shards: dict[str, dict] | None = None) -> None:
    if shards is None:
        shards = {path.stem: json.loads(path.read_text(encoding="utf-8"))
                  for path in catalog.glob("[0-9a-f][0-9a-f].json")}
    folders = {}
    for shard in shards.values():
        for content_id, record in shard["games"].items():
            for variant in record.get("variants", {}).values() if "variants" in record else (record,):
                launch = variant.get("launch")
                if launch:
                    ids = folders.setdefault(launch["folder"].casefold(), [])
                    if content_id not in ids:
                        ids.append(content_id)
    (catalog / "folders.json").write_text(json.dumps(folders, ensure_ascii=False,
        separators=(",", ":")), encoding="utf-8")


def apply_controller_recommendations(catalog_profiles: dict, recommendations: dict,
                                     current_ids: set[str], migrations: dict[str, set[str]]) -> dict:
    """Add reviewed controller presets to the generated profile catalog."""
    if recommendations.get("schemaVersion") != 1:
        raise ValueError("unsupported controller recommendation schema")
    presets = recommendations.get("profiles")
    assignments = recommendations.get("assignments")
    if not isinstance(presets, dict) or not isinstance(assignments, dict):
        raise ValueError("controller recommendations need profiles and assignments")
    virtual_controls = {"up", "down", "left", "right", "a", "b", "x", "y",
                        "l1", "r1", "l2", "r2", "start", "select", "menu",
                        "lsup", "lsdown", "lsleft", "lsright",
                        "rsup", "rsdown", "rsleft", "rsright"}
    joystick_controls = {"b", "y", "select", "start", "up", "down", "left", "right",
                         "a", "x", "l1", "r1", "l2", "r2", "l3", "r3",
                         *(f"joy{port}{direction}" for port in (1, 2)
                           for direction in ("up", "down", "left", "right"))}
    mouse_controls = {"moveUp", "moveDown", "moveLeft", "moveRight", "leftButton", "rightButton"}
    actions = {"menu", "pause", "restart", "exit"}
    cycle_inputs = {"virtual:l1", "virtual:r1", "virtual:l2", "virtual:r2", "virtual:left", "virtual:right"}
    guest_key_codes = (set(map(ord, "0123456789qwertyuiopasdfghjkl'zxcvbnm"))
                       | set(map(ord, "-=[]\\;',./")) | {8, 9, 13, 27, 32, 127, 301, 303,
                           304, 305, 306, 307, 308} | set(range(256, 294)))
    def validate_bindings(bindings, profile_id, sticks=False):
        if not isinstance(bindings, list) or len(bindings) > 128:
            raise ValueError(f"invalid controller preset bindings: {profile_id}")
        seen_inputs = set()
        for binding in bindings:
            if not isinstance(binding, dict):
                raise ValueError(f"invalid controller binding in {profile_id}")
            source = binding.get("input")
            if (not isinstance(source, str) or source in seen_inputs
                    or source.startswith("axis:")
                    or (not sticks and source.startswith(("virtual:ls", "virtual:rs")))):
                raise ValueError(f"duplicate or analog controller input in {profile_id}: {source}")
            if source.startswith("virtual:") and source[8:] not in virtual_controls:
                raise ValueError(f"unknown virtual controller input in {profile_id}: {source}")
            if source.startswith("hat:") and not re.fullmatch(r"hat:\d{1,3}:[+-]", source):
                raise ValueError(f"invalid hat input in {profile_id}: {source}")
            if source.startswith("button:") and not re.fullmatch(r"button:\d{1,4}", source):
                raise ValueError(f"invalid button input in {profile_id}: {source}")
            if not (source.startswith("virtual:") or source.startswith("hat:")
                    or source.startswith("button:")):
                raise ValueError(f"unsupported controller input in {profile_id}: {source}")
            seen_inputs.add(source)
            targets = [name for name in ("keys", "action", "joystick", "mouse", "cycleKeys")
                       if name in binding]
            if len(targets) != 1:
                raise ValueError(f"controller binding needs one target in {profile_id}")
            target = targets[0]
            value = binding[target]
            if target in ("keys", "cycleKeys"):
                limit = 4 if target == "keys" else 16
                minimum = 1 if target == "keys" else 2
                if (not isinstance(value, list) or not minimum <= len(value) <= limit
                        or any(type(key) is not int or key not in guest_key_codes for key in value)
                        or len(set(value)) != len(value)):
                    raise ValueError(f"invalid guest key mapping in {profile_id}")
                if target == "cycleKeys" and source not in cycle_inputs:
                    raise ValueError(f"invalid cycle-key input in {profile_id}: {source}")
            elif target == "action" and value not in actions:
                raise ValueError(f"unknown app action in {profile_id}: {value}")
            elif target == "joystick" and (value not in joystick_controls or value in {"l3", "r3"}):
                raise ValueError(f"unsupported joystick target in {profile_id}: {value}")
            elif target == "mouse" and value not in mouse_controls:
                raise ValueError(f"unsupported mouse target in {profile_id}: {value}")
            if "mouseSpeed" in binding:
                speed = binding["mouseSpeed"]
                if (target != "mouse" or not str(value).startswith("move")
                        or type(speed) not in (int, float) or not 0.1 <= speed <= 20):
                    raise ValueError(f"invalid mouse speed in {profile_id}")
    for profile_id, preset in presets.items():
        if not isinstance(preset, dict):
            raise ValueError(f"invalid controller preset: {profile_id}")
        validate_bindings(preset.get("bindings"), profile_id)
        variants = preset.get("defaults")
        if variants is not None:
            if (not isinstance(variants, dict) or "withoutSticks" not in variants
                    or set(variants) - {"withoutSticks", "withSticks"}):
                raise ValueError(f"invalid controller defaults: {profile_id}")
            for kind, bindings in variants.items():
                validate_bindings(bindings, profile_id, sticks=kind == "withSticks")
    doom_ids = set(catalog_profiles.get("profiles", {}).get("doom-v1", []))
    generated_assignments = {}
    for content_id, profile_id in assignments.items():
        if profile_id not in presets:
            raise ValueError(f"controller assignment references missing profile: {profile_id}")
        if content_id in doom_ids:
            raise ValueError(f"controller recommendation duplicates Doom profile: {content_id}")
        targets = ({content_id} if content_id in current_ids
                   else migrations.get(content_id, set()))
        if not targets:
            raise ValueError(f"controller recommendation does not resolve to the current catalog: {content_id}")
        for target in targets & current_ids:
            if target in doom_ids:
                raise ValueError(f"controller migration duplicates Doom profile: {target}")
            if target in generated_assignments:
                raise ValueError(f"multiple controller recommendations for {target}")
            generated_assignments[target] = profile_id
    used_presets = {profile_id for profile_id in generated_assignments.values()}
    catalog_profiles["assignments"] = dict(sorted(generated_assignments.items()))
    catalog_profiles["presets"] = {
        profile_id: {**presets[profile_id], "defaults": presets[profile_id].get("defaults",
            {"withoutSticks": presets[profile_id]["bindings"]})}
        for profile_id in sorted(used_presets)
    }
    return catalog_profiles


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


def retired_identities(old: dict[str, dict], current: dict[str, dict]) -> dict[str, set[str]]:
    """Find obsolete identities with an unambiguous current catalog title."""
    by_title: dict[str, set[str]] = {}
    for content_id, record in current.items():
        for variant in record.get("variants", {"": record}).values():
            by_title.setdefault(" ".join(variant["title"].casefold().split()), set()).add(content_id)
    result: dict[str, set[str]] = {}
    for content_id in old.keys() - current.keys():
        for variant in old[content_id].get("variants", {"": old[content_id]}).values():
            targets = by_title.get(" ".join(variant["title"].casefold().split()), set())
            if len(targets) == 1:
                result.setdefault(content_id, set()).update(targets)
    return result


def refresh_from_staging(root: Path, staging: Path, catalog: Path) -> None:
    """Reconcile a completed private scan with bundled metadata, without new art."""
    project = Path(__file__).resolve().parent.parent
    manifest = json.loads((staging / "manifest.json").read_text(encoding="utf-8"))
    if manifest["errors"]:
        raise ValueError("resolve archive errors before refreshing the catalog")
    with sqlite3.connect(staging / "hashes.sqlite") as database:
        cached = {name: (size, modified, content_id, error) for name, size, modified,
                  content_id, error in database.execute("SELECT * FROM games")}
    files = sorted((item for item in os.scandir(root / "eXo/eXoDOS")
                    if item.is_file() and item.name.lower().endswith(".zip")),
                   key=lambda item: item.name.casefold())
    if len(files) != manifest["archives"]:
        raise ValueError("source inventory changed; regenerate staging first")
    for item in files:
        stat = item.stat()
        scan = cached.get(item.name)
        if not scan or scan[:2] != (stat.st_size, stat.st_mtime_ns) or scan[3]:
            raise ValueError(f"source archive changed or failed scan: {item.name}")
    old = {key: value for path in catalog.glob("[0-9a-f][0-9a-f].json")
           for key, value in json.loads(path.read_text(encoding="utf-8"))["games"].items()}
    old = transform(old, expand)
    old_by_title: dict[str, list[tuple[str, dict]]] = {}
    old_by_stem: dict[str, list[tuple[str, dict]]] = {}
    for content_id, record in old.items():
        for stem, variant in record.get("variants", {"": record}).items():
            old_by_title.setdefault(" ".join(variant["title"].casefold().split()), []).append((content_id, variant))
            if stem:
                old_by_stem.setdefault(stem, []).append((content_id, variant))
    # Identity migration still recognizes the original source title after a
    # display-only edit. Do not duplicate unchanged titles (that adds ambiguity).
    for item in read_review()["records"]:
        previous = old.get(item["contentId"], {})
        if item.get("variant"):
            previous = previous.get("variants", {}).get(item["variant"], {})
        if previous and previous.get("title") != item["sourceTitle"]:
            old_by_title.setdefault(" ".join(item["sourceTitle"].casefold().split()), []).append(
                (item["contentId"], previous))
    with zipfile.ZipFile(root / "Content/XODOSMetadata.zip") as metadata:
        xml = game_records(metadata)
    configs, by_folder = launch_configs(root, xml)
    rows: dict[str, list[tuple[str, dict]]] = {}
    archive_index = []
    migrations: dict[str, set[str]] = {}
    missing_config = []
    retained_art = {}
    current_ids = {cached[item.name][2] for item in files}
    for item in files:
        content_id = cached[item.name][2]
        stem = Path(item.name).stem.casefold()
        game = xml.get(item.name.casefold())
        title = (game.findtext("Title") if game is not None else None) or Path(item.name).stem
        previous_record = old.get(content_id, {})
        previous = previous_record.get("variants", {}).get(stem)
        if previous is None and "variants" not in previous_record and previous_record:
            previous = previous_record
        candidates = old_by_stem.get(stem) or old_by_title.get(" ".join(title.casefold().split()), [])
        if previous is None:
            # Prefer a former identity no longer present in this scan. Ambiguous
            # matches never transfer launch settings or controller profiles.
            stale = [pair for pair in candidates if pair[0] not in current_ids]
            selected = stale if len(stale) == 1 else candidates
            if len(selected) == 1:
                previous = selected[0][1]
                migrations.setdefault(selected[0][0], set()).add(content_id)
        previous = previous or {}
        year = ((game.findtext("ReleaseDate") if game is not None else None) or "")[:4]
        variant = {key: value for key, value in previous.items()
                   if key not in ("title", "description", "tags", "artwork", "launch", "variants")}
        variant.update({"title": title,
                        "description": ((game.findtext("Notes") or "").strip()
                                        if game is not None else previous.get("description", "")),
                        "tags": [value for value in (year, game.findtext("Genre"),
                                  game.findtext("PlayMode")) if value] if game is not None
                                else previous.get("tags", []),
                        "artwork": {kind: asset for kind, asset in previous.get("artwork", {}).items()
                                    if (project / "catalog/artwork" / asset).is_file()}})
        config = configs.get(item.name.casefold())
        if config is None:
            with zipfile.ZipFile(item.path) as archive:
                roots = {name.split("/", 1)[0].casefold() for name in archive.namelist()
                         if "/" in name}
            if len(roots) == 1:
                config = by_folder.get(next(iter(roots)))
        if config:
            if item.name.casefold() == "blood (1997).zip":
                config = {**config, "configs": {name: body.replace("BLOOD121.CUE", "BLOODCD1.cue")
                          for name, body in config["configs"].items()}}
            variant["launch"] = config
        else:
            missing_config.append(item.name)
        for asset in variant["artwork"].values():
            retained_art.setdefault(asset, set()).add(content_id)
        rows.setdefault(content_id, []).append((stem, variant))
        archive_index.append({"archive": item.name, "contentId": content_id, "title": title})
    if missing_config:
        raise ValueError(f"missing launch metadata: {missing_config}")
    refreshed = {content_id: variants[0][1] if len(variants) == 1 else
                 {"title": " / ".join(value["title"] for _, value in variants),
                  "variants": dict(variants)} for content_id, variants in rows.items()}
    refreshed = transform(apply_review(refreshed, read_review()), compact)
    shards = {f"{index:02x}": {"schemaVersion": 1, "games": {}} for index in range(256)}
    for content_id, record in refreshed.items():
        shards[content_id.split(":", 1)[1][:2]]["games"][content_id] = record
    provenance_path = project / "docs/catalog-provenance.json"
    previous_provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    # Earlier imports may already contain both an obsolete identity and its
    # replacement. Record those retirements even when no new ID was added.
    retired = retired_identities(old, refreshed)
    artwork = [{**item, "contentId": content_id}
               for item in previous_provenance["artwork"]
               for content_id in sorted(retained_art.get(item["asset"], []))]
    profile_path = catalog / "controller-profiles-v1.json"
    profiles = json.loads(profile_path.read_text(encoding="utf-8"))
    profile_migrations = retired | migrations
    for profile, ids in profiles["profiles"].items():
        profiles["profiles"][profile] = sorted({target for content_id in ids
            for target in ({content_id} if content_id in refreshed else profile_migrations.get(content_id, set()))
            if target in refreshed})
    recommendation_path = project / "catalog/controller-research/recommendations.json"
    recommendations = json.loads(recommendation_path.read_text(encoding="utf-8"))
    profiles = apply_controller_recommendations(profiles, recommendations,
        set(refreshed), profile_migrations)
    # Compare the persisted representations, not expanded matching inputs.
    old = transform(old, compact)
    added = sorted(refreshed.keys() - old.keys())
    removed = sorted(old.keys() - refreshed.keys())
    changed = sorted(key for key in old.keys() & refreshed.keys() if old[key] != refreshed[key])
    report = {"schemaVersion": 1, "source": manifest["source"],
              "archives": len(files), "previousIds": len(old), "currentIds": len(refreshed),
              "addedIds": [{"contentId": key, "title": refreshed[key]["title"]} for key in added],
              "removedIds": [{"contentId": key, "title": old[key]["title"]} for key in removed],
              "changedIdentities": [{"previousId": key, "currentIds": sorted(targets),
                                     "title": old[key]["title"]}
                                    for key, targets in sorted((retired | migrations).items()) if key in removed],
              "metadataChanges": [{"contentId": key, "title": refreshed[key]["title"],
                                   "fields": sorted(field for field in old[key].keys() | refreshed[key].keys()
                                                    if old[key].get(field) != refreshed[key].get(field))}
                                  for key in changed],
              "unmatchedArchives": manifest["unmatchedArchives"], "errors": [],
              "artworkPolicy": "Preserve existing bundled artwork; import no new images."}
    for prefix, shard in shards.items():
        (catalog / f"{prefix}.json").write_text(json.dumps(shard, ensure_ascii=False,
            separators=(",", ":")), encoding="utf-8")
    write_folder_index(catalog, shards)
    profile_path.write_text(json.dumps(profiles, indent=2) + "\n", encoding="utf-8")
    provenance_path.write_text(json.dumps({**manifest, "matches": len(files),
        "uniqueIds": len(refreshed), "sharedIds": {key: [stem for stem, _ in variants]
            for key, variants in rows.items() if len(variants) > 1},
        "artwork": artwork, "shards": sorted(shards), "archiveIndex": archive_index},
        ensure_ascii=False, indent=2), encoding="utf-8")
    (project / "docs/catalog-refresh.json").write_text(json.dumps(report, ensure_ascii=False,
        indent=2) + "\n", encoding="utf-8")
    print(f"Refreshed {len(files)} archives: {len(refreshed)} identities, {len(added)} added, "
          f"{len(removed)} removed, {len(changed)} metadata changes", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, help="eXoDOS v6 root")
    parser.add_argument("output", type=Path, help="ignored local staging directory")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--art", action="store_true", help="extract local thumbnail candidates")
    parser.add_argument("--launch-only", action="store_true",
                        help="attach eXoDOS launch configs to existing catalog shards")
    parser.add_argument("--folder-index-only", action="store_true",
                        help="rebuild the source-folder lookup from catalog shards")
    parser.add_argument("--refresh-from-staging", action="store_true",
                        help="reconcile a completed scan with bundled catalog and provenance")
    args = parser.parse_args()
    if args.refresh_from_staging or args.folder_index_only or args.launch_only:
        import tempfile
        from build_catalog_packages import generate, ROOT
        from catalog_package import write_zip
        with tempfile.TemporaryDirectory(prefix="dos-complete-input-") as temporary:
            catalog = Path(temporary)
            with zipfile.ZipFile(ROOT / "catalog/complete-input-v1.zip") as source:
                for name in source.namelist():
                    if "/" in name or "\\" in name or not name.endswith(".json"):
                        raise ValueError("Invalid complete catalog input")
                    (catalog / name).write_bytes(source.read(name))
            if args.refresh_from_staging: refresh_from_staging(args.root, args.output, catalog)
            elif args.folder_index_only: write_folder_index(catalog)
            else: import_launch_only(args.root, catalog)
            write_zip(ROOT / "catalog/complete-input-v1.zip",
                      {path.name:path.read_bytes() for path in catalog.glob("*.json")})
        generate()
        return
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
        # A private partial scan can omit reviewed identities; publishing a full
        # refresh above requires every review to resolve.
        reviewed = transform(apply_review({key: value for games in shards.values()
                                 for key, value in games.items()}, read_review(), require_all=False), compact)
        for prefix, games in shards.items():
            (destination / f"{prefix}.json").write_text(json.dumps(
                {"schemaVersion": 1, "games": {key: reviewed[key] for key in games}},
                ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
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
