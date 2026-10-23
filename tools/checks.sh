#!/bin/sh
# KairoDos's own checks and build preparation, after the shared checks (shared/tools/check-app.sh):
# the public catalog, catalog review and artwork tests, catalog parts and packages that match their
# generator, the catalog audit, the pinned DOSBox Staging dependencies and their notices.
set -e
pwsh ./tools/BuildPublicDosCatalog.ps1 -Check
python3 -m unittest discover -s tools -p test_dos_catalog_review.py
python3 -m unittest discover -s tools -p test_dos_artwork.py
python3 tools/build_catalog_packages.py
git diff --exit-code -- catalog/parts catalog/optional/kairodos-art.nsfw.meta.json catalog/optional/kairodos-art.nsfw.zip
python3 shared/tools/audit_core_catalog.py dos
pwsh ./tools/PrepareStagingDependencies.ps1
python3 tools/GenerateStagingNotices.py
git diff --exit-code -- kairodos/src/main/assets/THIRD_PARTY_NOTICES.txt
