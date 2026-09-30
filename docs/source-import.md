# Source provenance

The pinned first-party frontend is at `shared/`. Emulator sources are copied, without upstream remotes, submodules or automated merges.

## DOSBox Staging

- Stable release **0.83.0**, August 27, 2026.
- Commit `7b40053b7ac580843d0461eba8c36a47a990e66c`.
- [Complete source archive](https://codeload.github.com/dosbox-staging/dosbox-staging/tar.gz/7b40053b7ac580843d0461eba8c36a47a990e66c).
- SHA-256 `40d4e32d23c4fa04901f004f57f46c64e181bf66801a7d164c915433c00dc536`.
- Extracted tree: `third_party/dosbox-staging/`, including licenses and corresponding source for freely licensed DOS utilities and keyboard resources in `extras/dos-programs/`.

Android changes are guarded by `KAIRO_STAGING`:

- `src/main.cpp`: callable entry, no process exit/signal handlers, cleanup on failure.
- `src/dosbox.cpp`: frontend restart instead of fork/exec; reset host timing after pause.
- `src/gui/sdl_gui.cpp`: frontend renderer/event polling and Android mouse capture routing.
- `src/audio/mixer.cpp`: AudioTrack consumes samples; dummy SDL device stays paused.
- `src/cpu/core_dynrec.cpp`: emulation-thread counters for translated blocks and returns from generated ARM64 code; the adapter publishes snapshots for device verification.
- `src/cpu/dyn_cache.h`: release JIT mappings, code-page handlers and invalidation maps during `CPU_Destroy`, before guest memory is destroyed. Desktop cache retention is unsuitable for repeated embedded sessions.
- `src/ints/bios_keyboard.cpp` and `src/hardware/input/mouseif_dos_driver.cpp`: shared touch-mode telemetry.
- `src/misc/fs_utils_posix.cpp`: case-insensitive paths on API 26 without Bionic glob.
- `src/misc/support.cpp`: session errors instead of aborting the Android process.
- `src/libs/loguru/loguru.cpp`: session-owned logging cleanup instead of process exit callbacks.

`backend-dos/CMakeLists.txt` builds `libdosbox_staging.so` from upstream component lists, using ARM64 dynamic recompilation, per-page W^X and optimized core code even in debug APKs. First-party adapters are in `backend-dos/src/main/cpp/`; JNI presents frames and routes input/audio. Game code is not patched.

## Dependencies

Complete original archives and recipe sources are in `third_party/staging-deps/sources/`. `sources.json` records every SHA-256, recipe commit `6283825b81bb60f952af1d0703638df1de611243` and verified tool release. Archives are corresponding source, not APK assets.

| Component | Version | License |
| --- | --- | --- |
| Asio | 1.32.0 | Boost 1.0 |
| FluidSynth | 2.5.2 | LGPL-2.1-or-later |
| GCEM | 1.18.0 | Apache-2.0 |
| iir1 | 1.10.0 | MIT |
| Munt/libmt32emu | 2.7.3 | LGPL-2.1-or-later |
| libogg | 1.3.6 | BSD-3-Clause |
| libpng | 1.6.54 | libpng license |
| Opus | 1.5.2 | BSD-3-Clause and source notices |
| opusfile | 9d718345ce03b2fad5d7d28e0bcd1cc69ab2b166 | BSD-3-Clause |
| SDL2 | 2.32.10 | Zlib and source notices |
| SDL2_image | 2.8.8 | Zlib and source notices |
| SpeexDSP | 1.2.1 | BSD-3-Clause and source notices |
| zlib | 1.3.1 | Zlib |
| zlib-ng | 2.3.3 | Zlib and source notices |
| vcpkg recipes | 6283825b81bb60f952af1d0703638df1de611243 | MIT build tooling |

The extracted SDL2 tree at `third_party/staging-deps/sdl2/` includes pinned recipe patches and guarded `SDL_KAIRO_EMBEDDED` changes to CMake, `src/file/SDL_rwops.c` and `src/thread/pthread/SDL_systhread.c`: POSIX files/threads and dummy audio/video without SDLActivity. First-party `sdl_embedded_android.c` supplies platform queries. The unused upstream Android sample Gradle wrapper JAR is omitted from the extracted tree so CI validates only the actual Kairo wrapper; the original SDL2 archive remains complete. Other libraries use shipped recipe patches only.

Run `pwsh tools/PrepareStagingDependencies.ps1` to verify sources and build static API 26 libraries. The bootstrap downloads the pinned vcpkg tool and host tools needed by its recipes. See [Windows build](build-windows.md).

## Previous core

Pure 1.0-preview6, commit `a4a0bab7f8931433588f2fcad9045c85b277373d`, remains in earlier revisions with corresponding source. Existing overlays/states on devices are preserved. Ordinary game file changes can be imported; Pure emulator states cannot be loaded by Staging.
