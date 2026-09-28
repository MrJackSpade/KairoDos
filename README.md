# KairoDos

KairoDos is an Android DOS emulator built around [DOSBox Pure](https://github.com/schellingb/dosbox-pure). It is a separate app from Kairo98. Both apps pin the same first-party [Kairo frontend](https://github.com/MrJackSpade/Kairo), which now owns the library list, game detail page, document-tree walker, in-game drawer, design system, and input widgets. Each app supplies its emulator, game-file reader, keyboard layout, settings, and catalog data.

## Current development build

Select a DOS folder from the library menu. KairoDos scans game ZIPs, DOSZ archives, standalone executables and disk images, and extracted game folders. It prepares the selected game in private cache on launch and runs it with the copied DOSBox Pure core. Video, stereo audio, keyboard, mouse, and gamepad input are connected. No games or operating system files are included.

eXoDOS source ZIPs are identified by their empty `.exo` archive marker, even without a catalog match. They appear with ` - Installer` after the title. On first launch, KairoDos creates and verifies a separate ` - Installed.zip` beside the source ZIP in the selected folder. It then launches the installed archive and, when the session ends, offers to remove the source ZIP. Keeping it is always an option. The folder picker requests write access for this workflow; users who previously selected a read-only folder need to select it again. DOSBox Pure keeps game writes in its per-game save overlay.

Known files are matched to a hash-keyed metadata catalog seeded from the owner's eXoDOS v6 collection. Recognition shows titles, descriptions, tags, covers, and screenshots in the same library UI as Kairo98. The catalog also supplies each recognized archive's DOSBox configuration and startup commands; KairoDos adapts those commands to the user's mounted game. eXoDOS is a metadata source, not a required user directory or a separate browser. See [catalog details](docs/catalog.md) for the identity format, generation, and review status.

A small original `.COM` program booted on a Retroid Pocket Classic on 27 September 2026, both loose and inside a ZIP with `DOSBOX.BAT`. The shared library, folder scan, and eXoDOS startup integration have not yet been checked on an Android device. eXoDOS launchers that depend on other emulators or Windows companion programs remain outside DOSBox Pure. This is a development app; no KairoDos public release workflow or Google Play edition exists yet.

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
