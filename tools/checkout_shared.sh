#!/usr/bin/env bash
set -euo pipefail

# Only bootstrap fetching belongs here; verification lives in the pinned shared project.
git submodule update --init --recursive shared
bash shared/tools/verify_checkout.sh "$(git rev-parse HEAD:shared)"
