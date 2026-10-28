#!/bin/sh
# KairoDos's own checks and build preparation, after the shared checks (shared/tools/check-app.sh):
# catalog review and artwork tests, the research and generated catalog matching their inputs,
# the pinned DOSBox Staging dependencies and their notices.
set -e
python3 -m unittest discover -s tools -p test_dos_catalog_review.py
python3 -m unittest discover -s tools -p test_dos_artwork.py
python3 tools/import_catalog.py --check
python3 shared/tools/app_catalog.py . --check
pwsh ./tools/PrepareStagingDependencies.ps1
python3 tools/GenerateStagingNotices.py
git diff --exit-code -- kairodos/src/main/assets/THIRD_PARTY_NOTICES.txt
