# KairoDos

KairoDos is an Android DOS emulator built around [DOSBox Pure](https://github.com/schellingb/dosbox-pure). It is a separate app from Kairo98. Both apps pin the same first-party [Kairo frontend](https://github.com/MrJackSpade/Kairo), which now owns the library list, game detail page, document-tree walker, in-game drawer, design system, and input widgets. Each app supplies its emulator, game-file reader, keyboard layout, settings, and catalog data.

## Current development build

Select a DOS folder from the library menu. KairoDos scans game ZIPs, DOSZ archives, standalone executables and disk images, and extracted game folders. It leaves the user's collection in place, prepares the selected game in private cache on launch, and runs it with the copied DOSBox Pure core. Video, stereo audio, keyboard, mouse, and gamepad input are connected. No games or operating system files are included.

Known files are matched to a hash-keyed metadata catalog seeded from the owner's eXoDOS v6 collection. Recognition shows titles, descriptions, tags, covers, and screenshots in the same library UI as Kairo98. eXoDOS is a metadata source, not a required user directory or a separate browser. See [catalog details](docs/catalog.md) for the identity format, generation, and review status.

A small original `.COM` program booted on a Retroid Pocket Classic on 27 September 2026, both loose and inside a ZIP with `DOSBOX.BAT`. The new shared library and folder scan have compiled but have not yet been checked on an Android device. Game compatibility and eXoDOS-specific startup scripts still require verification. This is a development app; no KairoDos public release workflow or Google Play edition exists yet.

## Build

Clone with the pinned frontend:

```sh
git clone --recurse-submodules https://github.com/MrJackSpade/KairoDos.git
cd KairoDos
./gradlew :kairodos:assembleDebug
```

The repository is private, so cloning requires access. Build with JDK 17, Android SDK 36 and Build Tools 36.0.0, NDK 28.2.13676358, and CMake 3.22.1. On Windows, use `gradlew.bat`. The native build currently targets arm64. NDK tooling requires paths without spaces on Windows; a temporary drive mapping works.

## Source and licensing

First-party KairoDos and Kairo frontend code is GPL-2.0-or-later. DOSBox Pure is copied into `third_party/dosbox-pure/` as ordinary source, not a submodule. See [source provenance](docs/source-import.md) and [licensing](docs/licensing.md). Catalog descriptions and image redistribution require a separate rights review before public release. The free GitHub and paid Google Play editions, when they exist, must have the same features and behavior from the same revision.
