# KairoDos

KairoDos is an Android DOS emulator built around [DOSBox Pure](https://github.com/schellingb/dosbox-pure). It offers a game library, controller and touch mapping, an on-screen keyboard, and per-game settings. The emulator core is copied into this project's source; KairoDos is an independent app.

[Kairo98](https://github.com/MrJackSpade/Kairo98) is the companion PC-98 emulator. Both apps use the [Kairo shared frontend](https://github.com/MrJackSpade/Kairo) for the library, navigation, and controls.

## Add games

Choose a folder containing your DOS games from the library menu. KairoDos scans ZIP and DOSZ archives, standalone programs and disk images, and extracted game folders. Select a game and tap **Play**. The app stages the selected game for DOSBox Pure. Games and operating system files are not bundled.

Known games receive a title, description, tags, and artwork from a hash-keyed catalog seeded from eXoDOS metadata. Your games do not need to be in an eXoDOS directory. An unknown game appears by filename and can still launch. See [catalog behavior](docs/catalog.md).

An eXoDOS source ZIP is detected by its empty `.exo` marker, even without a catalog entry, and appears with ` - Installer` in the library. On first launch, KairoDos creates an installed ZIP alongside it in a writable selected folder, verifies the copy, and runs it. After play, it offers to remove the source ZIP; you can keep it. If an external frontend grants access to only one ZIP, the installed copy goes into private app storage instead. DOSBox Pure stores later game writes in its own save overlay.

Some eXoDOS Windows launchers rely on companion programs or other emulators. Those steps are outside DOSBox Pure and may need manual setup.

## External frontends

Use the [external frontend setup guide](docs/frontends.md) to launch KairoDos games from other apps:

- [LaunchBox for Android](docs/frontends.md#launchbox-for-android)
- [ES-DE](docs/frontends.md#es-de)

## Build and source

Clone with `git clone --recurse-submodules https://github.com/MrJackSpade/KairoDos.git`, then run `./gradlew :kairodos:assembleDebug` (or `gradlew.bat` on Windows). The build uses JDK 17, Android SDK 36, NDK 28.2.13676358, and CMake 3.22.1 and targets ARM64.

First-party KairoDos and Kairo frontend code is [GPL-2.0-or-later](COPYING). See [source provenance](docs/source-import.md) and [licensing](docs/licensing.md). Free GitHub and paid Google Play editions must have the same features and behavior from the same revision.
