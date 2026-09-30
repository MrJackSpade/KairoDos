# Licensing and third-party credits

First-party KairoDos and pinned Kairo code remain GPL-2.0-or-later; see [COPYING](../COPYING). Spleen is BSD-2-Clause with source/notice under `shared/third_party/spleen/`.

Staging is predominantly GPL-2.0-or-later. The mixer includes MVerb under GPL-3.0-or-later; GCEM is Apache-2.0. **Distribute the combined APK under GPLv3 terms**, using first-party "or later" permission. Per-file licenses are preserved. GPL-2.0-only FreeDOS resources/utilities are separate works in the aggregate; source snapshots are supplied in `extras/dos-programs/`.

Preserve Staging's LICENSE, licenses directory and per-file notices. These cover DOSBox/Staging authors, MAME emulators, NE2000, Nuked OPL3, ESFMu, MVerb, TAL chorus, reSID, decoders, SIMD helpers, Unicode mappings and freely licensed DOS utilities. [Source provenance](source-import.md) records external dependency versions and complete sources.

## Distribution audit

- ARM64 builds link copied Staging and static dependencies, with Android `c++_shared`.
- `tools/GenerateStagingNotices.py` generates the offline About notice from copied license texts and embedded notices, dependency notices, Spleen and NDK notices.
- Corresponding source must include the exact app revision, pinned shared revision, complete Staging/SDL trees, dependency/recipe archives, adapters and build scripts. A checkout without shared is incomplete.
- No proprietary ROM, BIOS, operating system, soundfont or game is supplied. Freely licensed Staging keyboard/codepage resources and DOS utilities include notices and source.
- Device boot acceptance and source/license checks are required before an APK is described as release-ready. Build success alone is insufficient.

Existing eXoDOS/LaunchBox-derived metadata and artwork still need creator/redistribution/derivative rights review before public distribution. Access to source data does not grant redistribution rights. Project-owner artwork is separate from the code license.
