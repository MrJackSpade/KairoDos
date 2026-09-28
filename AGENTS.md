# Project instructions

- KairoDos is an independent Android DOS app. Credit DOSBox Pure accurately without using its name as this app's brand.
- Keep only this project's `origin` remote and the pinned first-party Kairo frontend submodule at `shared/`. Do not add upstream remotes, fork relationships, or automated merges.
- Import an audited, pinned DOSBox Pure snapshot into `third_party/` before building the emulator. Record provenance and transitive licenses in `docs/source-import.md` and `docs/licensing.md`.
- Do not bundle proprietary BIOS, ROM, operating system, or game files.
- First-party code is GPL-2.0-or-later. Preserve each third-party notice and supply complete corresponding source for distributed binaries.
- Free GitHub and paid Google Play builds must have the same features and behavior and come from the same revision.
- Do not claim a binary is release-ready until it boots a game on an Android device and its license audit is complete.
- When the user asks to push a release tag, treat that as confirmation that testing is sufficient. Keep the normal tag-triggered release workflow enabled; do not bypass it or substitute manual publication to add a test gate.
