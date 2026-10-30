#!/usr/bin/env python3
"""Turn the eXoDOS import (catalog/complete-input-v1.zip, from tools/generate_dos_catalog.py) into
KairoDos's research catalog for the shared generator (shared/tools/app_catalog.py).

Usage: import_catalog.py [--check] [--references <out.json>]

Writes catalog/research/games-v1.json.gz (gzipped, shared catalog_files.py) and catalog/research/folders-v1.json. Each game is
"dos:<first 16 hex digits of its content ID>". An archive holding several games (its record's
"variants", eXoDOS's entries named by their file names) is a collection: one library entry whose
games the player picks between when it starts. It keeps its own key, gives each of its games
"<key>.<n>", and names them with their launches in its "diskCollection", members "1", "2"... in
name order. Entries that launch the same files the same way are one game eXoDOS lists twice, so
they become one game, the first entry's. A game's title, tags and artwork come from the import with the metadata review
applied (catalog/metadata-review-v1.json); its LaunchBox images from
catalog/launchbox-artwork-v1.json; its launch (folder, eXoDOS configs, exception) rides on its
medium's record; its controls are its controller preset (catalog/research/controller-profiles-v1.json,
which tools/GenerateDosControllerProfiles.ps1 maintains, with catalog/controller-defaults.json); its
machine settings, each with the reason it needs them, from catalog/research/machine-v1.json
({"<game key>": {"<option>": value, "reason": text}}, catalog_config.MACHINE_OPTIONS). Descriptions are original and come from the description files
(shared catalog_descriptions.py); the import's eXoDOS text is never shipped. --check fails if a
written file would change. --references writes that text, {"<game key>": text}, as reference
material for writing descriptions (shared descriptions.py worklist --references); keep it out of
the repository (.downloads/).
"""

import argparse
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "shared/tools"))
sys.path.insert(0, str(ROOT / "tools"))
import catalog_descriptions
import catalog_files
from catalog_config import MACHINE_OPTIONS
from dos_artwork import with_launchbox
from dos_catalog_review import apply_review, read_review

PREFIX = "sha256-dos-manifest-v1:"
HEART = "♥"


def key_of(content_id):
    return "dos:" + content_id.removeprefix(PREFIX)[:16]


def controls(source, defaults):
    """{content ID: {"profile", "defaults"}}: each game's preset, by assignment or profile list."""
    presets = {**source["presets"], **defaults["presets"]}
    assignments = {**source["assignments"], **defaults["assignments"]}
    by_game = {}
    for profile, ids in source.get("profiles", {}).items():
        for content_id in ids:
            by_game.setdefault(content_id, profile)
    by_game.update(assignments)
    result = {}
    for content_id, profile in by_game.items():
        preset = presets[profile]
        layouts = preset.get("defaults") or {"withoutSticks": preset["bindings"]}
        result[content_id] = {"profile": profile, "defaults": layouts}
    return result


def research_game(record, images):
    """A game's shipped fields from an import record (or variant): title, tags, adult, artwork."""
    tags = [tag for tag in record.get("tags", []) if tag != HEART]
    game = {"title": record["title"]}
    if tags:
        game["tags"] = tags
    if HEART in record.get("tags", []):
        game["adult"] = True
    art = record.get("artwork")
    if art:
        folder = record.get("launch", {}).get("folder", "").lower()
        game["artwork"] = with_launchbox(art, images[folder]) if folder in images else art
    return game


def apply_machine(games):
    """Each game's reviewed machine settings (catalog/research/machine-v1.json) as its "machine"."""
    review = json.loads((ROOT / "catalog/research/machine-v1.json").read_text(encoding="utf-8"))
    assert review.get("schemaVersion") == 1 and isinstance(review.get("games"), dict), "invalid machine-v1.json"
    for key, fields in review["games"].items():
        assert key in games, f"machine-v1.json: unknown game {key}"
        assert isinstance(fields.get("reason"), str) and fields["reason"].strip(), f"machine-v1.json: {key} gives no reason"
        settings = {option: value for option, value in fields.items() if option != "reason"}
        assert settings and all(value in MACHINE_OPTIONS.get(option, ()) for option, value in settings.items()), \
            f"machine-v1.json: {key} has settings the catalog does not offer"
        games[key]["machine"] = settings


def build():
    with zipfile.ZipFile(ROOT / "catalog/complete-input-v1.zip") as archive:
        files = {name: json.loads(archive.read(name)) for name in archive.namelist()}
    records = apply_review({key: value for name, root in files.items() if len(name) == 7
                            for key, value in root["games"].items()}, read_review())
    images = json.loads((ROOT / "catalog/launchbox-artwork-v1.json").read_text(encoding="utf-8"))["folders"]
    defaults = json.loads((ROOT / "catalog/controller-defaults.json").read_text(encoding="utf-8"))
    profiles = json.loads((ROOT / "catalog/research/controller-profiles-v1.json").read_text(encoding="utf-8"))
    controllers = controls(profiles, defaults)
    games, references = {}, {}
    for content_id, record in sorted(records.items()):
        key = key_of(content_id)
        assert key not in games, f"two games share {key}"
        variants = [variant for _, variant in sorted(record.get("variants", {}).items())]
        if len({json.dumps(variant["launch"], sort_keys=True) for variant in variants}) == 1:
            # One game that eXoDOS lists twice: the same files, launched the same way.
            record = {**variants[0], **{field: record[field] for field in ("hidden",) if field in record}}
            variants = []
        game = {**research_game(record, images), "contentIds": [content_id]}
        if record.get("description", "").strip():
            references[key] = record["description"]
        if record.get("hidden"):
            game["hidden"] = True
        if content_id in controllers:
            game["controller"] = controllers[content_id]
        if "launch" in record:
            game["diskMachine"] = {content_id: {"launch": record["launch"]}}
        members = {}
        for index, variant in enumerate(variants, 1):
            member_key = f"{key}.{index}"
            games[member_key] = research_game(variant, images)
            if variant.get("description", "").strip():
                references[member_key] = variant["description"]
            members[str(index)] = {"game": member_key, "launch": variant["launch"]}
        if members:
            game["diskCollection"] = {content_id: members}
        games[key] = game
    apply_machine(games)
    catalog_descriptions.apply(ROOT / "catalog/research", games)
    folders = {"schemaVersion": 1, "folders": files["folders.json"]}
    return {"catalog/research/games-v1.json.gz": {"schemaVersion": 1, "games": games},
            "catalog/research/folders-v1.json": folders}, references


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--references", type=Path)
    args = parser.parse_args()
    outputs, references = build()
    if args.references:
        args.references.parent.mkdir(parents=True, exist_ok=True)
        args.references.write_text(json.dumps(references, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
    for path, value in outputs.items():
        content = json.dumps(value, ensure_ascii=False, indent=1, sort_keys=True) + "\n"
        target = ROOT / path
        if path.endswith(".gz"):
            # The research holds descriptions: gzipped (shared catalog_files.py).
            if args.check:
                assert catalog_files.same(target, content.encode("utf-8")), f"Stale {path}"
            else:
                catalog_files.write_text(target, content)
        elif args.check:
            assert target.read_text(encoding="utf-8") == content, f"Stale {path}"
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
    print(f"KairoDos research: {len(outputs['catalog/research/games-v1.json.gz']['games'])} games")


if __name__ == "__main__":
    main()
