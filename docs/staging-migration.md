# DOSBox Staging migration

The engine is DOSBox Staging 0.83.0. Product identity, shared library/navigation/controller/touch/display infrastructure and application ID are preserved. Kairo98's core is unchanged.

## Game data

Android document-provider URIs cannot be mounted as ordinary native folders. KairoDos materializes a game's verified files into `files/saves/<game-id>/staging/drive` once. This directory is persistent and writable; the source ZIP remains unchanged. Parent and game-folder launch variants use the same drive.

Original Pure overlays and emulator states remain on disk. Pure's file overlay is imported transactionally, including changed files, deletions and renames. Interrupted or rejected imports never replace the live drive. Multiple ambiguous overlays and missing rename sources produce an error and preserve the originals. Source identity changes likewise preserve an existing writable drive rather than overwrite progress.

Staging has no save-state API. Save/Load state actions were removed; use each game's own saves. Pure states cannot be converted. Staging also does not natively support Pure's CHD/JRC media formats. Some profiles for other DOSBox forks require manual setup. This migration does not modify game executables or original game archives.

## Android adapter

Kairo owns Android surfaces, input and AudioTrack. Embedded SDL provides events, timers and the dummy window/audio device; it does not create an SDLActivity or consume audio samples. Staging video follows the emulated DOS clock; Kairo's presenter uses a latest-frame queue, independently of panel refresh. AudioTrack is the only playback clock. Mouse capture is handled by Kairo, so desktop SDL relative-mode requests are bypassed.

Each session's engine library is unloaded after worker cleanup to reset core globals. Host pauses wake on reset/exit and reset timing on resume. Staging errors return through the session instead of terminating Android. Its desktop process exit and signal handlers are disabled.

## Acceptance on Retroid Pocket Classic, September 30, 2026

- Final ARM64 debug APK and instrumentation APK built from pinned API 26 dependency sources.
- APK packages Staging, host and `c++_shared`; no Pure/libretro library.
- Storage fixture passed: original sources/overlays/states preserved, save changes retained on retry, rename chains/deletions imported, unsafe paths and cancelled imports rejected, parent launch configuration generated.
- Doom reached playable first-person gameplay. Final lifecycle fixture: 517 video frames, 2,072,288 PCM frames and nonzero audio, with pause/resume, surface detach/recreation, reset, two consecutive sessions and exit while paused.
- Cannon Fodder reached its animated helicopter/jungle intro after numeric boot selection, with nonzero audio and the same lifecycle checks.
- Fixtures used private cache copies and saves, not the player's original media or live writable drive.

These are functional acceptance results, not a performance benchmark or a claim that every cataloged game is compatible. Corresponding sources, notices and provenance are supplied; existing catalog artwork/metadata distribution review remains required before public release.
