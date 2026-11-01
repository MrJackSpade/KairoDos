#!/usr/bin/env python3
"""The manifests eXoDOS games have when their CDs are kept as CHDs (KairoDos#146).

Usage: chd_manifests.py <eXoDOS games folder> [--check]

A player may keep a game's CD as a CHD: chdman's "<sheet>.chd" in place of the CUE sheet and the
track files its FILE lines name. The app's manifest (DosContentHash) counts a CHD as the disc
image it holds, the track files joined in the sheet's order (the shared CHD reader), under the
CHD's own name: its size and CRC-32 are the track files' summed sizes and combined CRCs, so this
reads only each ZIP's directory and its sheets. Writes catalog/chd-manifests-v1.json,
{"schemaVersion": 1, "games": {"<manifest ID>": "<manifest ID with its CDs as CHDs>"}}, for every
game whose CDs are all sheets of BINARY track files; import_catalog.py gives each the same game.
--check fails if the written file would change.
"""

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from generate_dos_catalog import manifest, zip_files

OUTPUT = ROOT / "catalog/chd-manifests-v1.json"
FILE = re.compile(r'^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+(\S+)', re.IGNORECASE | re.MULTILINE)


def crc32_combine(first, second, second_length):
    """The CRC-32 of two blocks joined, from each block's CRC-32 and the second's length (zlib's
    crc32_combine)."""
    def times(matrix, vector):
        total, index = 0, 0
        while vector:
            if vector & 1:
                total ^= matrix[index]
            vector >>= 1
            index += 1
        return total

    def square(matrix):
        return [times(matrix, matrix[n]) for n in range(32)]

    if second_length == 0:
        return first
    odd = [0xEDB88320] + [1 << n for n in range(31)]
    even = square(odd)
    odd = square(even)
    while True:
        even = square(odd)
        if second_length & 1:
            first = times(even, first)
        second_length >>= 1
        if not second_length:
            break
        odd = square(even)
        if second_length & 1:
            first = times(odd, first)
        second_length >>= 1
        if not second_length:
            break
    return first ^ second


def chd_manifest(archive):
    """The game's manifest with every CUE sheet and its track files as one "<sheet>.chd", or None
    when it has no sheet, or a sheet of another kind of track file (MP3, WAVE)."""
    files = zip_files(archive)
    by_name = {name: (size, crc, item) for name, size, crc, item in files}
    sheets = [name for name in by_name if name.endswith(".cue")]
    if not sheets:
        return None
    replaced, added = set(), []
    for sheet in sheets:
        text = archive.read(by_name[sheet][2]).decode("latin-1")
        lines = FILE.findall(text)
        if not lines or any(kind.upper() != "BINARY" for _, _, kind in lines):
            return None
        folder = sheet.rsplit("/", 1)[0] + "/" if "/" in sheet else ""
        tracks = []
        for quoted, bare, _ in lines:
            name = folder + "".join(chr(ord(char) + 32) if "A" <= char <= "Z" else char
                                    for char in (quoted or bare).replace("\\", "/"))
            if name not in by_name:
                raise ValueError(f"a sheet names a missing track file")
            tracks.append(name)
        size, crc = 0, 0
        for name in tracks:
            track_size, track_crc, _ = by_name[name]
            crc = crc32_combine(crc, track_crc, track_size) if size else track_crc
            size += track_size
        replaced |= {sheet, *tracks}
        added.append((sheet[:-len(".cue")] + ".chd", size, crc))
    return manifest([entry[:3] for entry in files if entry[0] not in replaced] + added)


def build(folder):
    games = {}
    for path in sorted(folder.glob("*.zip")):
        with zipfile.ZipFile(path) as archive:
            chd = chd_manifest(archive)
            if chd:
                games[manifest([entry[:3] for entry in zip_files(archive)])] = chd
    return {"schemaVersion": 1, "games": dict(sorted(games.items()))}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    text = json.dumps(build(args.folder), indent=1) + "\n"
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text(encoding="utf-8") != text:
            raise SystemExit(f"{OUTPUT.relative_to(ROOT)} is out of date")
        return
    OUTPUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"{len(json.loads(text)['games'])} games with CDs kept as CHDs -> {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
