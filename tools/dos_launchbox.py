#!/usr/bin/env python3
"""Maps each eXoDOS game folder to its LaunchBox game and images, so the app downloads artwork
from LaunchBox and uses the repository's copy only for images LaunchBox does not have.

Usage: dos_launchbox.py <eXoDOS root> <LaunchBox MS-DOS snapshot>

The snapshot comes from shared/tools/launchbox.py snapshot <Metadata.zip> <dir> dos. Each game
takes the LaunchBox DatabaseID eXoDOS's MS-DOS.xml records for it; a game without one takes the
LaunchBox game whose name equals its title and whose year agrees. Writes
catalog/launchbox-artwork-v1.json: {"folders": {"<folder>": {"game": DatabaseID,
"boxArt"|"preview": LaunchBox image URL}}}, by lower-case folder name, which
tools/build_catalog_packages.py applies.
"""

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "shared/tools"))
from launchbox import BOX_TYPES, SCREENSHOT_TYPES, key  # noqa: E402

OUTPUT = ROOT / "catalog/launchbox-artwork-v1.json"


def exodos_games(root):
    """{folder: (title, year, DatabaseID or None)} from eXoDOS's MS-DOS.xml."""
    result = {}
    with zipfile.ZipFile(root / "Content/XODOSMetadata.zip") as metadata, metadata.open("xml/all/MS-DOS.xml") as stream:
        for _, element in ET.iterparse(stream):
            if element.tag != "Game":
                continue
            # The catalog names folders in lower case; eXoDOS keeps their own case.
            folder = (element.findtext("RootFolder") or "").replace("\\", "/").rstrip("/").rsplit("/", 1)[-1].lower()
            title = element.findtext("Title") or ""
            year = (element.findtext("ReleaseDate") or "")[:4]
            database = (element.findtext("DatabaseID") or "").strip()
            if folder:
                result[folder] = (title, int(year) if year.isdigit() else None, database or None)
            element.clear()
    return result


def images(game):
    """The game's preferred box and screenshot URLs, by the shared LaunchBox type order."""
    found = {}
    for field, types in (("boxArt", BOX_TYPES), ("preview", SCREENSHOT_TYPES)):
        for kind in types:
            url = next((image["url"] for image in game["images"] if image["type"] == kind), None)
            if url:
                found[field] = url
                break
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("exodos", type=Path)
    parser.add_argument("snapshot", type=Path)
    args = parser.parse_args()
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    by_name = {}
    for game in snapshot.values():
        for name in [game["Name"], *game["aliases"]]:
            by_name.setdefault(key(name), []).append(game)
    folders = {}
    counts = {"exodos": 0, "title": 0, "unmatched": 0, "no images": 0}
    for folder, (title, year, database) in sorted(exodos_games(args.exodos).items()):
        game = snapshot.get(database) if database else None
        source = "exodos"
        if game is None:
            source = "title"
            matches = [g for g in by_name.get(key(title), [])
                       if year is None or not re.match(r"\d{4}", g.get("ReleaseDate") or "") or
                       abs(int(g["ReleaseDate"][:4]) - year) <= 1]
            game = matches[0] if len(matches) == 1 else None
        if game is None:
            counts["unmatched"] += 1
            continue
        found = images(game)
        if not found:
            counts["no images"] += 1
            continue
        counts[source] += 1
        folders[folder] = {"game": int(game["DatabaseID"]), **found}
    OUTPUT.write_text(json.dumps({"schemaVersion": 1, "folders": folders}, indent=1, sort_keys=True) + "\n",
                      encoding="utf-8")
    print(f"{len(folders)} folders with LaunchBox artwork ({counts['exodos']} by eXoDOS's DatabaseID, "
          f"{counts['title']} by title); {counts['no images']} LaunchBox games without images; "
          f"{counts['unmatched']} folders with no LaunchBox game")


if __name__ == "__main__":
    main()
