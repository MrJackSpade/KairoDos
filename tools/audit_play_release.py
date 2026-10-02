#!/usr/bin/env python3
"""Verify DOS release assets against the reviewed public catalog export."""
import pathlib
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
if len(sys.argv) not in (3, 4):
    raise SystemExit('Usage: audit_play_release.py APK AAB [WITH_IMAGES_APK]')
for index, argument in enumerate(sys.argv[1:]):
    path = pathlib.Path(argument)
    prefix = 'base/' if path.suffix == '.aab' else ''
    with zipfile.ZipFile(path) as archive:
        for asset in ('THIRD_PARTY_NOTICES.txt', 'PRIVACY_POLICY.txt'):
            assert archive.read(prefix + 'assets/' + asset) == (
                root / 'kairodos/src/main/assets' / asset).read_bytes(), asset
        artwork = [name for name in archive.namelist() if name.startswith(prefix + 'assets/art/')]
        if index == 2:
            assert any(name.endswith('.webp') for name in artwork), 'Image APK contains no images'
        else:
            assert not artwork
    print(f'{path}: reviewed DOS catalog and current notices verified')

# Inspect actual signed distribution assets with the same exclusion ledger as generation.
import subprocess
subprocess.run([sys.executable, str(pathlib.Path(__file__).resolve().parents[1] /
    "shared/tools/audit_core_catalog.py"), "dos", *sys.argv[1:]], check=True,
    cwd=pathlib.Path(__file__).resolve().parents[1])
