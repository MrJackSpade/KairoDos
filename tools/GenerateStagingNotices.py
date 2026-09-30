#!/usr/bin/env python3
"""Build the offline notice from pinned source notices, without network access."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
CORE = ROOT / "third_party/dosbox-staging"
parts = ["KairoDos third-party notices\n\n"
         "KairoDos uses DOSBox Staging 0.83.0 (7b40053b7ac580843d0461eba8c36a47a990e66c).\n"
         "KairoDos is an independent Android app. First-party source is GPL-2.0-or-later.\n"
         "The combined emulator includes GPL-3.0-or-later MVerb; APKs use GPLv3 terms.\n"
         "Complete corresponding source, dependency sources and build instructions:\n"
         "https://github.com/MrJackSpade/KairoDos (the revision accompanying the APK).\n"
         "See docs/source-import.md and docs/licensing.md in that source.\n"]
seen = set()

def add(path, body=None):
    body = body if body is not None else path.read_text(encoding="utf-8", errors="replace")
    body = body.replace("\r\n", "\n").strip()
    if body and body not in seen:
        seen.add(body)
        parts.append(f"\n--- {path.relative_to(ROOT).as_posix()} ---\n\n{body}\n")

add(CORE / "LICENSE")
for path in sorted((CORE / "licenses").glob("*.txt"), key=lambda p: p.as_posix()):
    add(path)
add(ROOT / "shared/third_party/spleen/LICENSE")
for path in sorted((ROOT / "third_party/staging-deps/notices").glob("*.txt"), key=lambda p: p.as_posix()):
    add(path)

for base in [CORE / "src", CORE / "include", CORE / "resources",
             ROOT / "third_party/staging-deps/sdl2"]:
    # Path ordering is case-insensitive on Windows, but case-sensitive on Linux.
    # Sort portable names explicitly so the shipped notice is reproducible.
    for path in sorted(base.rglob("*"), key=lambda p: p.as_posix()):
        if not path.is_file():
            continue
        if re.search(r"(?:^|[._-])(license|copying|copyright|notice)(?:$|[._-])", path.name, re.I):
            add(path)
        elif path.suffix.lower() in {".c", ".cpp", ".h", ".hpp", ".txt", ".md", ".glsl"}:
            content = path.read_text(encoding="utf-8", errors="replace")
            blocks = re.findall(r"/\*.*?\*/|(?:(?:^|\n)[ \t]*//[^\n]*)+", content, re.S)
            for block in blocks:
                if re.search(r"copyright|SPDX-License|permission is hereby|redistribution and use", block, re.I):
                    add(path, block)

output = ROOT / "kairodos/src/main/assets/THIRD_PARTY_NOTICES.txt"
output.write_text("".join(parts), encoding="utf-8", newline="\n")
print(f"Wrote {output.name}: {output.stat().st_size} bytes")
