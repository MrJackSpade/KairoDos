<!-- kairo:discord -->
<p align="center">
  <a href="https://discord.gg/ZkYQ3bPTTq"><img src="https://raw.githubusercontent.com/MrJackSpade/Kairo/main/docs/assets/discord-banner.svg" alt="Join the Kairo Discord" width="49%"></a>
</p>
<!-- /kairo:discord -->

# KairoDos

**Play your own DOS games on Android phones and handhelds.** KairoDos gives you a game library with artwork, controller and touch controls, an on-screen keyboard, and per-game settings, running on [DOSBox Staging](https://www.dosbox-staging.org/) with ARM64 dynamic recompilation. It is an independent project, not an official DOSBox Staging release.

<!-- kairo:play-badge -->
[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=com.loxifi.kairodos)
<!-- /kairo:play-badge -->

## Contents

- [Download](#download)
- [Quick start](#quick-start)
- [Supported game formats](#supported-game-formats)
- [eXoDOS archives](#exodos-archives)
- [Saves](#saves)
- [Managing your library](#managing-your-library)
- [Controls](#controls)
- [DOS prompt and setup programs](#dos-prompt-and-setup-programs)
- [Launching from other frontends](#launching-from-other-frontends)
- [Documentation](#documentation)
- [Help and community](#help-and-community)
- [Building from source](#building-from-source)
- [License](#license)

## Download

<!-- kairo:download -->
- **[Google Play](https://play.google.com/store/apps/details?id=com.loxifi.kairodos)** (paid): updates automatically. Buying it supports development.
- **[GitHub Releases](https://github.com/MrJackSpade/KairoDos/releases)** (free): install the APK yourself. The larger `-with-images` APK also bundles catalog artwork.

Both editions are built from the same source and have the same features. Requires an ARM64 device running Android 8.0 or newer.
<!-- /kairo:download -->

## Quick start

1. Install KairoDos from [Google Play](https://play.google.com/store/apps/details?id=com.loxifi.kairodos) or [GitHub Releases](https://github.com/MrJackSpade/KairoDos/releases).
2. Open KairoDos and choose a folder containing your DOS games. You can change it later in **Menu › Manage library**.
3. Select a game and tap **Play**.

Games and proprietary operating system files are not included. Your original files are never modified: KairoDos prepares a writable DOS drive for each game in private app storage.

## Supported game formats

| Type | Formats |
| --- | --- |
| Archives | ZIP, DOSZ |
| Folders and programs | Extracted game folders, standalone EXE/COM/BAT programs |
| Disc and disk images | ISO/CUE, IMG/IMA |

Known games get a title, description, tags, and artwork from a catalog seeded from eXoDOS metadata. Your games do not need to be laid out like an eXoDOS collection, and unknown games still appear by filename and still launch. Details: [game catalog](docs/catalog.md).

Coming from DOSBox Pure: its CHD/JRC formats are not supported, but existing Pure overlay files are preserved and in-game saves are imported once.

ARM64 dynamic recompilation is used for default and auto CPU profiles in both real and protected mode. Games configured for interpreter or prefetch CPU compatibility keep those settings.

## eXoDOS archives

An eXoDOS game ZIP is detected automatically and appears with ` - Installer` after its name.

1. Launch it once. KairoDos creates and verifies an installed copy beside it in your game folder.
2. The installed copy appears under the game's normal title.
3. When you finish playing, KairoDos offers to remove the installer. You can keep it.

If an external frontend shares only that one ZIP, the installed copy is kept in private app storage instead. Some eXoDOS launchers depend on companion programs or features of other emulators and may need manual setup.

## Saves

- **In-game saves.** Everything a game writes, including its saves, is kept separately from the original files.
- **Editing a game's files.** Saves and settings belong to the game's place in your DOS folder, so you can add or change its files, such as a BAT or configuration file. If the game saved its own copy of a file you changed, KairoDos asks which copy to keep. Renaming or moving a game makes it a new game.
- **Save folder.** To keep saved games in a folder of your choice, choose **Menu › Configurations › Save folder › Custom folder**. Changing the folder moves your saves; if a game has saves in both places, you choose which copy to keep.
- **No emulator save states.** DOSBox Staging does not support save states, so use each game's own save system. Old DOSBox Pure state files stay on disk but cannot be loaded.

## Managing your library

- **Catalog updates.** KairoDos checks for catalog updates when it opens; **Update catalogs** in **Menu › Manage library** checks on demand. Updates refresh titles, descriptions, launch settings, and controller defaults without an app update.
- **Deleting a game.** Open the game's **Game settings** and choose **Delete game file** or **Delete game folder**. KairoDos names the source before deleting it. Saves and settings are kept.

## Controls

| Action | How |
| --- | --- |
| Remap a controller | Map buttons and sticks to DOS keys, mouse movement, or app actions; layouts are saved per game |
| Use touch controls | The same per-game layout works with on-screen controls |
| Use a second screen | On a two-screen handheld, the game and controls can use separate displays |

Many games ship with researched controller defaults. Doom and Duke Nukem 3D have full layouts for handhelds with and without analog sticks: see [FPS controller defaults](docs/fps-controller-profiles.md). Any mapping you save yourself always takes priority.

## DOS prompt and setup programs

On a game's page, press the command-prompt icon to the right of **Play** to:

- choose **Prompt** to drop into DOS with the game's drives mounted, or
- browse the mounted drives and run a different program, such as a game's `SETUP.EXE`.

This only affects that one session; the next **Play** starts the game normally. Details: [run a setup program](docs/dos-program-picker.md) and [boot to DOS prompt](docs/dos-prompt.md).

## Launching from other frontends

<!-- kairo:frontend-guides -->
KairoDos can open a game sent from another Android frontend; no library folder is needed. Step-by-step setup:

- [LaunchBox for Android](docs/frontends.md#launchbox-for-android)
- [ES-DE](docs/frontends.md#es-de)
- [iiSU](docs/frontends.md#iisu)
- [Beacon](docs/frontends.md#beacon)
<!-- /kairo:frontend-guides -->

## Documentation

The full list is in the [documentation index](docs/index.md).

- [External frontend setup](docs/frontends.md): LaunchBox, ES-DE, iiSU, and Beacon configuration
- [Game catalog](docs/catalog.md): how games are matched, eXoDOS installers, catalog updates
- [FPS controller defaults](docs/fps-controller-profiles.md): Doom and Duke Nukem 3D control layouts
- [Run a setup program](docs/dos-program-picker.md): launching another executable from a game's drives
- [Boot to DOS prompt](docs/dos-prompt.md): opening a DOS prompt with the game mounted
- [Release notes](docs/releases/README.md): changes in every version
- [Licensing and credits](docs/licensing.md): code license and third-party components
- [Source provenance](docs/source-import.md): DOSBox Staging, dependencies, and local changes

## Help and community

<!-- kairo:community -->
- **Report a game problem** in [Issues](https://github.com/MrJackSpade/KairoDos/issues). Include the title, file format, and what happened.
- **Chat** with other players on [Discord](https://discord.gg/ZkYQ3bPTTq).
<!-- /kairo:community -->

### Other Kairo apps

<!-- kairo:family -->
<table>
  <tr>
    <td align="center" width="33%"><a href="https://github.com/MrJackSpade/Kairo98"><img src="https://raw.githubusercontent.com/MrJackSpade/Kairo98/main/kairo98/src/main/res/drawable-nodpi/kairo98_icon_art.png" width="96" alt="Kairo98"><br><b>Kairo98</b></a><br>PC-98</td>
    <td align="center" width="33%"><a href="https://github.com/MrJackSpade/Kairo88"><img src="https://raw.githubusercontent.com/MrJackSpade/Kairo88/main/kairo88/src/main/res/drawable-nodpi/product_icon_art.png" width="96" alt="Kairo88"><br><b>Kairo88</b></a><br>PC-88</td>
    <td align="center" width="33%"><a href="https://github.com/MrJackSpade/Kairo68"><img src="https://raw.githubusercontent.com/MrJackSpade/Kairo68/main/kairo68/src/main/res/drawable-nodpi/product_icon_art.png" width="96" alt="Kairo68"><br><b>Kairo68</b></a><br>X68000</td>
  </tr>
  <tr>
    <td align="center" width="33%"><a href="https://github.com/MrJackSpade/KairoTowns"><img src="https://raw.githubusercontent.com/MrJackSpade/KairoTowns/main/kairotowns/src/main/res/drawable-nodpi/product_icon_art.png" width="96" alt="KairoTowns"><br><b>KairoTowns</b></a><br>FM Towns</td>
    <td align="center" width="33%"><a href="https://github.com/MrJackSpade/KairoMSX"><img src="https://raw.githubusercontent.com/MrJackSpade/KairoMSX/main/kairomsx/src/main/res/drawable-nodpi/product_icon_art.png" width="96" alt="KairoMSX"><br><b>KairoMSX</b></a><br>MSX</td>
  </tr>
</table>

Every Kairo app is built on [Kairo](https://github.com/MrJackSpade/Kairo), the shared frontend for the game library, controls, and settings.
<!-- /kairo:family -->

## Building from source

<!-- kairo:clone -->
Clone with submodules to get the pinned shared frontend:

```sh
git clone --recurse-submodules https://github.com/MrJackSpade/KairoDos.git
```
<!-- /kairo:clone -->

Then prepare the emulator dependencies and build:

```sh
cd KairoDos
pwsh tools/PrepareStagingDependencies.ps1
./gradlew :kairodos:assembleDebug
```

Requires JDK 17, Android SDK 36, NDK 28.2.13676358, and CMake 3.22.1; builds target ARM64. The default build uses a checked-in performance profile; see [the profile notes](backend-dos/pgo/README.md).

## License

<!-- kairo:license -->
First-party code is [GPL-2.0-or-later](LICENSE.md). The app as distributed is GPLv3 because it bundles GPLv3 components. Third-party components keep their own licenses; see [licensing and credits](docs/licensing.md) and [source provenance](docs/source-import.md).
<!-- /kairo:license -->
