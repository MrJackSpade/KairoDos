# Source provenance

[← KairoDos](../README.md) · [All documentation](index.md)

The pinned first-party frontend is at `shared/`. Emulator sources are copied, without upstream remotes, submodules or automated merges.

The shared frontend also supplies RetroArch Slang support from a pinned librashader v0.11.0 snapshot. Its `Cargo.lock`, 129 checked Rust source archives, provenance, and notices are under `shared/third_party/` and `shared/docs/`. The Android OpenGL runtime is built from source for arm64. Two selected presets from `libretro/slang-shaders` commit `e1d75632a205c70f14a4cc947c46d5abb7b3f7f1` are bundled. Their exact files, hashes, and per-file license review are in the [shared shader snapshot](../shared/third_party/slang-shaders/README.md); KairoDos pins the same shared revision as Kairo98.

## DOSBox Staging

- Stable release **0.83.0**, August 27, 2026.
- Commit `7b40053b7ac580843d0461eba8c36a47a990e66c`.
- [Complete source archive](https://codeload.github.com/dosbox-staging/dosbox-staging/tar.gz/7b40053b7ac580843d0461eba8c36a47a990e66c).
- SHA-256 `40d4e32d23c4fa04901f004f57f46c64e181bf66801a7d164c915433c00dc536`.
- Extracted tree: `third_party/dosbox-staging/`, including licenses and corresponding source for freely licensed DOS utilities and keyboard resources in `extras/dos-programs/`.

Android changes are guarded by `KAIRO_STAGING`:

- `src/main.cpp`: callable entry, no process exit/signal handlers, cleanup on failure.
- `src/dosbox.cpp`: frontend restart instead of fork/exec; the host fast-forward button runs the fast-forward hotkey's switch; the time the shared machine host holds the emulation thread between frames (its turn, KairoDos#124) counts as slept in the tick accounting (`KairoHostHeld`), so automatic cycles do not take it for work.
- `src/gui/sdl_gui.cpp`: frontend renderer/event polling and Android mouse capture routing.
- `src/cpu/cpu.cpp`: `KairoRunningDecoder`, the core the CPU runs or, while halted, resumes with, for the CPU telemetry.
- `src/hardware/video/vga_draw.cpp`: a count of every emulated frame, changed or not (`VGA_VerticalTimer` calls `KairoStagingFrameEnd`).
- `src/hardware/video/vga_draw.cpp` and `src/gui/render/render.cpp`: indexed VESA scanline history in the first-party `kairo_palette_cache.h` avoids palette expansion and RGB comparison when the renderer's preceding line is provably unchanged. Palette changes, missing lines, failed updates and renderer resets invalidate reuse. Hardware cursors, wrapped VRAM, ReelMagic mixing and unsupported renderer paths retain upstream conversion; scanout timing and game resolution are unchanged.
- `src/audio/mixer.cpp`: AudioTrack consumes samples; dummy SDL device stays paused.
- `src/cpu/core_dynrec.cpp`: emulation-thread counters for translated blocks and returns from generated ARM64 code; the adapter publishes snapshots for device verification.
- `src/cpu/dyn_cache.h`: release JIT mappings, code-page handlers and invalidation maps during `CPU_Destroy`, before guest memory is destroyed. Desktop cache retention is unsuitable for repeated embedded sessions.
- `src/ints/bios_keyboard.cpp` and `src/hardware/input/mouseif_dos_driver.cpp`: shared touch-mode telemetry.
- `src/misc/fs_utils_posix.cpp`: case-insensitive paths on API 26 without Bionic glob.
- `src/misc/cross.cpp`: `resolve_home` resolves a launch's host path reference (`kairo-host:<n>`, `KairoStagingHostPath`), so MOUNT, IMGMOUNT and BOOT open host paths the frontend passes as UTF-8 instead of host paths written into AUTOEXEC.BAT, which Staging converts to the DOS code page.
- `src/misc/support.cpp`: session errors instead of aborting the Android process.
- `src/libs/loguru/loguru.cpp`: session-owned logging cleanup instead of process exit callbacks.

`backend-dos/CMakeLists.txt` builds `libdosbox_staging.so` from upstream component lists, using ARM64 dynamic recompilation, per-page W^X and optimized core code even in debug APKs. First-party adapters are in `backend-dos/src/main/cpp/`; JNI presents frames and routes input/audio. Game code is not patched.

Optional measurement builds additionally define `KAIRO_GUEST_PROFILE`: `src/cpu/core_dynrec/decoder.h` records translation addresses and already-decoded opcode bytes, and `src/cpu/dyn_cache.h` records invalidation. The first-party recorder is `backend-dos/src/main/cpp/kairo_guest_profile.h`. These hooks add no guest memory reads or generated guest instructions, are compiled out by default, and are prohibited in Release builds.

## Dependencies

Complete original archives and recipe sources are in `third_party/staging-deps/sources/`. `sources.json` records every SHA-256, recipe commit `6283825b81bb60f952af1d0703638df1de611243` and verified tool release. Archives are corresponding source, not APK assets.

The MIDI synths, Munt libmt32emu 2.7.3 and FluidSynth 2.5.2 (LGPL-2.1-or-later, FluidSynth with GCEM 1.18.0, Apache-2.0), are shared's: `shared/cmake/KairoMidi.cmake` builds them with the core from `shared/third_party/` (shared's `docs/source-import.md`), one version for every Kairo app (KairoX86#306).

| Component | Version | License |
| --- | --- | --- |
| Asio | 1.32.0 | Boost 1.0 |
| iir1 | 1.10.0 | MIT |
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

The extracted SDL2 tree at `third_party/staging-deps/sdl2/` includes pinned recipe patches and guarded `SDL_KAIRO_EMBEDDED` changes to CMake, `src/file/SDL_rwops.c` and `src/thread/pthread/SDL_systhread.c`: POSIX files/threads and dummy audio/video without SDLActivity. First-party `sdl_embedded_android.c` supplies platform queries. The unused upstream Android sample Gradle wrapper JAR is omitted from the extracted tree so CI validates only the actual Kairo wrapper; the original SDL2 archive remains complete. Other libraries, except the SpeexDSP overlay described below, use shipped recipe patches only.

The SpeexDSP recipe overlay at `third_party/staging-deps/ports/speexdsp/` adds
a first-party GPL-2.0-or-later AArch64 floating-point interpolation patch to the
unchanged 1.2.1 archive. Its copied vcpkg build recipe retains the MIT notice;
SpeexDSP source retains its BSD notices. The four accumulators use NEON without
changing filter settings, state handling or reduction order. The original
oversample-one implementation is retained for identical rounding.

Run `pwsh tools/PrepareStagingDependencies.ps1` to verify sources and build static API 26 libraries. The bootstrap downloads the pinned vcpkg tool and host tools needed by its recipes.

## Previous core

Pure 1.0-preview6, commit `a4a0bab7f8931433588f2fcad9045c85b277373d`, remains in earlier revisions with corresponding source. Existing overlays/states on devices are preserved. Ordinary game file changes can be imported; Pure emulator states cannot be loaded by Staging.

## Local changes

The local changes below are recorded in `tools/patches.py`, which `shared/tools/local_patches.py .` applies to a fresh snapshot and checks in CI (`--check`: undoing and redoing them must give back the committed tree). Change the snapshot only through it: add or edit a group there, then apply it with `--group`.

## Local dynrec SETcc implementation

The imported Staging decoder and opcode helper now translate generic SETcc (0F 90-9F), retaining flags and existing checked writes. Nonzero flag masks are normalized to byte 1. Original GPL notices are preserved. The change is core-specific and contains no game code.

## Known-producer Jcc conditions

`src/cpu/core_dynrec/operators.h` and `decoder_opcodes.h` specialize Jcc helpers
for a single live CMP/TEST producer in the existing flag-elimination queue.
Other conditions/producers retain original handling; no memory or invalidation
semantics change. Original GPL notices remain intact. A verification-only macro
checks producer identity and is absent from normal builds.

Group `dynrec-flag-consumers` (Collection Chamber launch sweep). The queue's single
producer is only the live one if nothing filled the flags since: CMC, CLC, STC,
MUL, IMUL (all forms), VERR and VERW call helpers that fill the flags from the
lazy state at run time, so `src/cpu/core_dynrec/decoder.h` and `decoder_opcodes.h`
now acquire the flags before them, as PUSHF and RCL/RCR already did. Without it
a CMP; STC; Jcc sequence took its condition from the CMP's lazy state, and
Windows for Workgroups 3.11's VMM stopped with "Invalid VxD dynamic link call to
device number 0007". The verification macro runs clean over a Windows boot.

## Core-owned paging storage

Under `KAIRO_STAGING`, `src/cpu/paging.h` gives the single core-owned `paging`
object hidden visibility. The Android host uses only the C callback bridge and
does not import this object. Other data and weak-symbol binding remains unchanged;
non-Kairo builds retain upstream visibility. Original GPL notices are preserved.

## Core-owned lazy-flag storage

Under `KAIRO_STAGING`, `src/cpu/lazyflags.h` gives the core-owned `lflags`
object hidden visibility. The Android host does not import this object; the
C bridge, flag representation and non-Kairo visibility remain unchanged.
Original GPL notices are preserved.

## MIDI out to a MIDI port of the device

Group `staging-midi-port` (KairoDos#150). Under `KAIRO_STAGING`, `src/midi/midi.cpp` gains
`mididevice=kairo`, KairoDos's own device (`backend-dos/src/main/cpp/kairo_midi_port.cpp`), which plays nothing
itself; Staging's default, `port`, still opens no device on Android. `MIDI_RawOutByte` hands it every byte the guest writes first, as written
(Staging's own parsing rewrites running status), and it sends them through the host's `midi_byte`
callback to the port the player picked; the messages Staging sends of its own (`MIDI_Reset`) go to it
whole. Every other device is unchanged. Original GPL notices are preserved.

## Repeatable guest clock

Group `staging-fixed-clock` (KairoDos#156). Under `KAIRO_STAGING`, `src/ints/bios.cpp` (`BIOS_HostTimeSync`, the DOS date and BIOS tick count at boot) and `src/hardware/cmos.cpp` (the CMOS clock, which Staging reads from the host's time at every read) take the time from `KairoStagingClock` (`backend-dos/src/main/cpp/staging_bridge.h`): the host's local time, as before, or for a run that must repeat exactly (`kairo::Launch::repeatable`, the shared tests) a fixed start, 1993-01-01 00:00:00 UTC (`kairo_staging_fixed_clock`), plus the emulated time since the run began. The app keeps the host's time. Original GPL notices are preserved.

Group `staging-mixer-on-tick` (KairoDos#154). Under `KAIRO_STAGING`, `src/audio/mixer.cpp` mixes on the emulation thread instead of its own: a tick handler mixes, `KairoMixLagTicks` (4) emulated 1 ms ticks behind the sound devices, in blocks of the mixer's block size, and `KairoMixerFrameEnd` (called by the bridge's frame end) mixes the rest before the host takes each frame's sound, so identical runs give the same sound in every frame. A queued device's pull (`MIXER_PullFromQueueCallback`, `src/audio/mixer.h`) never waits and fails when its running queue is short; a channel enabled mid-run leads with silence up to the last tick; the channel sleeper counts emulated time; speex starts from zeros instead of `speex_resampler_skip_zeros`. `src/hardware/audio/opl.cpp` and `opl.h` render every OPL frame on the timeline, each tick (`Opl::RenderTick`), on the ordered OPL worker, which also hands each block to a second ordered stage for the channel's resampling and filters; the mixer waits for both before each mix and while it is locked (`OPL_WaitForWorker`), and the mixer callback only takes made frames. Original GPL notices are preserved.

Group `staging-queued-rate` (Collection Chamber launch sweep). A queued device's frames are mixed `KairoMixLagTicks` behind the tick that made them, at its channel's rate, so a rate change left the next mix asking the queue for the old rate's frames at the new one (Sound Blaster 11025 to 22050 Hz: 241 frames asked, 219 held). `KAIRO_SetQueuedSampleRate` (`src/audio/mixer.h`) hands the frames still queued to the channel at the rate they were made at, sets the new rate, and sizes the queue for it at once (`KAIRO_SizeQueue`, the pull's own sizing): sized for the lower rate until the next pull, the queue dropped what a Sound Blaster switched from 11025 to 22050 Hz made (236 frames held, the 11025 Hz capacity). The Sound Blaster, GUS, Tandy DAC and ReelMagic change their rate through it, and `MIXER_PullFromQueueCallback` shares its hand-off (`KAIRO_AddQueuedFrames`) and sizing. Original GPL notices are preserved.

## Ordered OPL worker

`src/hardware/audio/opl.cpp` and `opl.h` contain ordered catch-up synthesis
for ordinary OPL modes and an optional Debug-only synchronous comparator.
It uses first-party shared worker infrastructure; the imported Nuked engine
is unchanged. Original GPL notices are preserved. The worker is enabled by
default.
Verification-only queue statistics and a delayed one-entry stress mode also
live in `opl.cpp`; they are excluded from normal builds and do not change the
imported Nuked synthesis engine.
The verification build also maintains an independent synchronous catch-up
timeline/FIFO and compares the ordered OPL mixer-input samples. This oracle is
excluded from normal builds.
