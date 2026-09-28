# Source provenance

The Kairo frontend is a pinned first-party submodule at `shared/`. [DOSBox Pure](https://github.com/schellingb/dosbox-pure) is copied into `third_party/dosbox-pure/` as ordinary source. KairoDos does not use a DOSBox Pure Git submodule or automated upstream merge.

## DOSBox Pure snapshot

- Upstream release: `1.0-preview6`
- Source commit: `a4a0bab7f8931433588f2fcad9045c85b277373d`
- [Source archive](https://github.com/schellingb/dosbox-pure/archive/a4a0bab7f8931433588f2fcad9045c85b277373d.tar.gz) SHA-256: `f0d04f087bb1c63a4cf1d46e314a9e0336afad427ddebbefde3daffe64b9005b`
- Import: complete extracted archive tree in `third_party/dosbox-pure/`

KairoDos patches `dosbox_pure_libretro.cpp` to enter a game ZIP's single wrapper folder when needed. It patches `src/dos/dos_programs.cpp` with generated `KAIRO:` and `KAIROZIP:` mount sources for game directories and separately staged user-owned media. These allow eXoDOS startup profiles to refer to their original game and disc paths without bundling the media. A local GPL-2.0-or-later telemetry bridge in `src/kairo_input_telemetry.*`, with hooks in `src/ints/bios_keyboard.cpp` and `src/ints/mouse.cpp`, reports guest keyboard waits, keyboard polls, and mouse reads to the shared touch mode decision logic.

`backend-dos/` builds the copied core into `libretro.so`; `kairodos/` builds the app host into `libkairodos_host.so`. The native build targets ARM64 and uses the core's dynamic recompiler selection. License details are in [licensing](licensing.md).
