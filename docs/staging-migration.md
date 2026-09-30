# DOSBox Staging migration

The engine is DOSBox Staging 0.83.0. Product identity, shared library/navigation/controller/touch/display infrastructure and application ID are preserved. Kairo98's core is unchanged.

## Game data

Android document-provider URIs cannot be mounted as ordinary native folders. KairoDos materializes a game's verified files into `files/saves/<game-id>/staging/drive` once. This directory is persistent and writable; the source ZIP remains unchanged. Parent and game-folder launch variants use the same drive.

Original Pure overlays and emulator states remain on disk. Pure's file overlay is imported transactionally, including changed files, deletions and renames. Interrupted or rejected imports never replace the live drive. Multiple ambiguous overlays and missing rename sources produce an error and preserve the originals. Source identity changes likewise preserve an existing writable drive rather than overwrite progress.

Staging has no save-state API. Save/Load state actions were removed; use each game's own saves. Pure states cannot be converted. Staging also does not natively support Pure's CHD/JRC media formats. Some profiles for other DOSBox forks require manual setup. This migration does not modify game executables or original game archives.

## Android adapter

Kairo owns Android surfaces, input and AudioTrack. Embedded SDL provides events, timers and the dummy window/audio device; it does not create an SDLActivity or consume audio samples. Staging video follows the emulated DOS clock; Kairo's presenter uses a latest-frame queue, independently of panel refresh. AudioTrack is the only playback clock. Mouse capture is handled by Kairo, so desktop SDL relative-mode requests are bypassed.

Each session's engine library is unloaded after worker cleanup to reset core globals. Host pauses wake on reset/exit and reset timing on resume. Staging errors return through the session instead of terminating Android. Its desktop process exit and signal handlers are disabled.

## ARM64 dynamic recompilation

The Android build enables `C_DYNREC` and `C_TARGET_CPU_ARM`, selecting Staging's `risc_armv8le.h` emitter and per-page W^X cache. The adapter has a compile-time guard against disabling this core. The launch adapter selects `core=dynamic` for profiles with a default/auto core, enabling generated ARM64 code in both real and protected modes. Explicit normal/full/simple choices and prefetch CPU compatibility are preserved. Cycle limits and CPU models remain controlled by the catalog. Staging's upstream `auto` mode is also exercised for protected-mode switching.

Bridge version 2 publishes CPU telemetry from the emulation thread into atomic frontend snapshots. Counters record translated blocks and successful returns from generated ARM64 code. Each return can cover a chain of blocks. `StagingCoreFixture` requires translation, more than 100 successful returns, and continued execution over another second with the product's real launch configuration in Doom and Cannon Fodder. Doom's second launch also exercises upstream automatic mode. Reset and consecutive launches are exercised as well. The fixture reads `/proc/self/maps` after each session to verify that anonymous executable JIT mappings return to their pre-launch baseline. Teardown restores guest page handlers, deletes cache page/invalidation allocations and unmaps the JIT cache before unloading the core.

September 30 Retroid results:

- Doom default dynamic mode: 9,212 translated blocks, 82,855,712 native returns.
- Doom after reset: 8,774 translated blocks, 4,573,268 native returns.
- Doom automatic mode on the second launch: 8,693 translated blocks, 6,569,403 native returns.
- Cannon Fodder default dynamic mode: 271 translated blocks, 630,641 native returns; reset: 272 blocks, 688,769 returns; second launch: 271 blocks, 618,669 returns.
- Both runs passed video/audio, pause/resume, surface recreation, reset, repeated sessions, paused exit and released JIT mapping checks. These counters prove generated host code ran and provide diagnostic execution totals.

## Acceptance on Retroid Pocket Classic, September 30, 2026

- Final ARM64 debug APK and instrumentation APK built from pinned API 26 dependency sources.
- APK packages Staging, host and `c++_shared`; no Pure/libretro library.
- Storage fixture passed: original sources/overlays/states preserved, save changes retained on retry, rename chains/deletions imported, unsafe paths and cancelled imports rejected, parent launch configuration generated, dynamic defaults and explicit interpreter/prefetch compatibility choices verified.
- Doom reached playable first-person gameplay. Final lifecycle fixture: 729 video frames, 2,954,689 PCM frames and nonzero audio, with pause/resume, surface detach/recreation, reset, two consecutive sessions and exit while paused.
- Cannon Fodder reached its animated helicopter/jungle intro after numeric boot selection: 477 video frames, 1,632,160 PCM frames, nonzero audio and the same lifecycle checks.
- Fixtures used private cache copies and saves, not the player's original media or live writable drive.

These are functional acceptance results, not a performance benchmark or a claim that every cataloged game is compatible. Corresponding sources, notices and provenance are supplied; existing catalog artwork/metadata distribution review remains required before public release.
