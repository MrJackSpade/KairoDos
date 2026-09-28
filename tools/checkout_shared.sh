#!/usr/bin/env bash
set -euo pipefail

git submodule update --init --recursive shared

expected="$(git rev-parse HEAD:shared)"
actual="$(git -C shared rev-parse HEAD)"
if [[ "$expected" != "$actual" ]]; then
  echo "Shared frontend checkout does not match the pinned commit" >&2
  exit 1
fi

if [[ -n "$(git -C shared status --porcelain)" ]]; then
  echo "Shared frontend checkout is dirty" >&2
  exit 1
fi
