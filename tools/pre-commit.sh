#!/bin/sh
set -eu
# The research catalog matches the eXoDOS import, and the generated catalog its research.
python tools/import_catalog.py --check
exec python shared/tools/app_catalog.py . --check
