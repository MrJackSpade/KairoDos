"""KairoDos's third-party notices: the sources shared/tools/notices.py writes
kairodos/src/main/assets/THIRD_PARTY_NOTICES.txt from, without network access: DOSBox Staging's and
its pinned dependencies' license files, and every license or copyright comment in their sources."""
import re

import notices

OUT = 'kairodos/src/main/assets/THIRD_PARTY_NOTICES.txt'

HEADER = '''KairoDos third-party notices

KairoDos uses DOSBox Staging 0.83.0 (7b40053b7ac580843d0461eba8c36a47a990e66c).
KairoDos is an independent Android app. First-party source is GPL-2.0-or-later.
The combined emulator includes GPL-3.0-or-later MVerb; APKs use GPLv3 terms.
Complete corresponding source, dependency sources and build instructions:
https://github.com/MrJackSpade/KairoDos (the revision accompanying the APK).
See docs/source-import.md and docs/licensing.md in that source.'''

THIRD_PARTY = notices.ROOT / 'third_party'
CORE = THIRD_PARTY / 'dosbox-staging'
LICENSE_NAME = re.compile(r'(?:^|[._-])(license|copying|copyright|notice)(?:$|[._-])', re.I)
SOURCE = {'.c', '.cpp', '.h', '.hpp', '.txt', '.md', '.glsl'}
COMMENT = re.compile(r'/\*.*?\*/|(?:(?:^|\n)[ \t]*//[^\n]*)+', re.S)
LICENSE_TERMS = re.compile(r'copyright|SPDX-License|permission is hereby|redistribution and use', re.I)


def files(base):
    # Path ordering is case-insensitive on Windows but case-sensitive on Linux: sort portable names.
    return sorted((path for path in base.rglob('*') if path.is_file()), key=lambda path: path.as_posix())


def clean(body):
    return '\n'.join(line.rstrip(' \t') for line in body.replace('\r\n', '\n').strip().split('\n'))


SECTIONS = []
seen = set()


def add(path, body=None):
    name = path.relative_to(THIRD_PARTY).as_posix()
    # Some of DOSBox Staging's sources aren't UTF-8 throughout; their license text is.
    body = clean(body if body is not None else path.read_bytes().decode('utf-8', errors='replace'))
    if body and body not in seen:
        seen.add(body)
        SECTIONS.append((name, f'third_party/{name}', [body]))


add(CORE / 'LICENSE')
for path in sorted((CORE / 'licenses').glob('*.txt'), key=lambda path: path.as_posix()):
    add(path)
for path in sorted((THIRD_PARTY / 'staging-deps/notices').glob('*.txt'), key=lambda path: path.as_posix()):
    add(path)
for base in (CORE / 'src', CORE / 'include', CORE / 'resources', THIRD_PARTY / 'staging-deps/sdl2'):
    for path in files(base):
        if LICENSE_NAME.search(path.name):
            add(path)
        elif path.suffix.lower() in SOURCE:
            content = path.read_bytes().decode('utf-8', errors='replace')
            for block in COMMENT.findall(content):
                if LICENSE_TERMS.search(block):
                    add(path, block)
