# KairoDos

KairoDos is a separate Android DOS emulator built around [DOSBox Pure](https://github.com/schellingb/dosbox-pure). It shares the first-party [Kairo frontend](https://github.com/MrJackSpade/Kairo) components with Kairo98, but has its own repository, Android package, launcher icon, game library, and DOS keyboard. It is not a PC-98 emulator.

## Current status

The development app (`com.loxifi.kairodos`, `0.1.0-dev`) imports a DOS program or self-contained game archive through Android's file picker, copies it into app storage, and runs it with the copied DOSBox Pure core. It renders video, plays stereo audio, and connects the shared on-screen keyboard, touch mouse, physical keyboard, and gamepad input. Each imported title has its own Pure save directory. The app offers mouse mode, DOS CPU speed, reset, and mute controls.

A small original `.COM` program was imported and booted on a Retroid Pocket Classic on 27 September 2026. Its DOS text rendered and an on-screen key advanced the program. The same program also booted from a ZIP containing a `DOSBOX.BAT` startup script. These checks do not establish compatibility across the other supported formats or performance across games.

The picker accepts `.zip`, `.dosz`, `.exe`, `.com`, `.bat`, `.iso`, `.chd`, `.img`, `.ima`, `.vhd`, and `.jrc`. A ZIP or DOSZ should contain the files needed by its game. Selecting a lone executable does not import adjacent files. Multi-file disc sets and external `.cue` references are not yet supported by the importer. No games, BIOS, ROMs, or operating system files are included.

This is a development build. There is no KairoDos release workflow or Google Play edition yet.

## Build

Clone with the pinned first-party frontend:

```sh
git clone --recurse-submodules https://github.com/MrJackSpade/KairoDos.git
cd KairoDos
./gradlew :kairodos:assembleDebug
```

The repository is currently private, so cloning requires access. Build with JDK 17, Android SDK 36 and Build Tools 36.0.0, NDK 28.2.13676358, and CMake 3.22.1. On Windows, use `gradlew.bat`. The current native build is arm64 only. `ndk-build` has trouble with spaces in the SDK or checkout path on Windows; use paths without spaces or drive mappings.

## Source and licensing

First-party KairoDos and Kairo frontend code is GPL-2.0-or-later. DOSBox Pure is copied into `third_party/dosbox-pure/` as ordinary source files, not a submodule or remote. See [source provenance](docs/source-import.md) and [licensing](docs/licensing.md) for its revision, source hash, included components, and outstanding distribution work. The shared Spleen font has a BSD-2-Clause notice. The launcher art was supplied by the project owner; its redistribution terms still need confirmation before a public binary release.

When free GitHub and paid Google Play editions exist, they must have the same features and behavior and be built from the same revision.
