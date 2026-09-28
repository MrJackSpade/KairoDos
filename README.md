# KairoDos

KairoDos is a separate Android DOS app planned around DOSBox Pure. It shares the first-party [Kairo frontend](https://github.com/MrJackSpade/Kairo) with Kairo98, but has its own repository, Android package, launcher icon, and DOS-specific keyboard layout. It is not a PC-98 emulator.

## Current status

This repository builds a **development UI shell** (`com.loxifi.kairodos`, version `0.1.0-dev`, Android 8.0 or newer). It displays the shared keyboard with DOS/libretro key codes and lets you preview and save touch input settings locally. Keyboard events are discarded by the shell, and the saved settings do not control an emulator.

DOSBox Pure has not been imported or connected. There is no DOS emulation, disk loading, game library, or game execution yet. The repository has a debug build workflow, but no distributable release build or Google Play edition.

## Build the prototype

Clone with the pinned shared frontend:

```sh
git clone --recurse-submodules https://github.com/MrJackSpade/KairoDos.git
cd KairoDos
./gradlew :kairodos:assembleDebug
```

The repository is currently private, so cloning requires access. The build requires JDK 17 and Android SDK 36 with Build Tools 36.0.0. On Windows, use `gradlew.bat` for the last command. The APK is a UI preview and cannot run DOS software.

## Source and licensing

First-party KairoDos and Kairo frontend code is GPL-2.0-or-later; see [COPYING](COPYING) and the pinned `shared/` repository. The shared Spleen font has its own BSD-2-Clause notice. The launcher icon was supplied by the project owner; its distribution terms are tracked separately in [licensing](docs/licensing.md). No DOSBox Pure source or binary, BIOS, operating system, or game files are bundled. See [source provenance](docs/source-import.md) for the import boundary.

When emulator builds are distributed, the free GitHub and paid Google Play editions will have the same features and behavior and come from the same source revision.
