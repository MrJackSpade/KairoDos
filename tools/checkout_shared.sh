#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${KAIRO_SHARED_DEPLOY_KEY:-}" ]]; then
  echo "KAIRO_SHARED_DEPLOY_KEY is required while the Kairo repository is private" >&2
  exit 1
fi

ssh_dir="$(mktemp -d)"
trap 'rm -rf "$ssh_dir"' EXIT
chmod 700 "$ssh_dir"
printf '%s\n' "$KAIRO_SHARED_DEPLOY_KEY" > "$ssh_dir/key"
chmod 600 "$ssh_dir/key"
# GitHub's published Ed25519 host key: https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints
printf '%s\n' 'github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl' > "$ssh_dir/known_hosts"
export GIT_SSH_COMMAND="ssh -i $ssh_dir/key -o IdentitiesOnly=yes -o UserKnownHostsFile=$ssh_dir/known_hosts"
git submodule update --init --recursive shared

expected="$(git rev-parse HEAD:shared)"
actual="$(git -C shared rev-parse HEAD)"
if [[ "$expected" != "$actual" ]]; then
  echo "Shared frontend checkout does not match the pinned commit" >&2
  exit 1
fi
