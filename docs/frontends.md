# External frontend setup for KairoDos

[← KairoDos](../README.md) · [All documentation](index.md)

KairoDos can open a DOS game file or ZIP sent by another Android frontend. You do not need to select a library folder in KairoDos first. Keep archive extraction off in the frontend; KairoDos opens ZIPs and handles eXoDOS installer archives itself. Choosing **Library** from a frontend-launched game closes KairoDos and returns to the frontend.

## Setup guides

- [LaunchBox for Android](#launchbox-for-android)
- [ES-DE](#es-de)
- [iiSU](#iisu)
- [Beacon](#beacon)

## LaunchBox for Android

Import DOS games into LaunchBox's **MS-DOS** platform. Open that platform, tap the **top-right three-dot menu → Emulator Settings**, and choose **Custom Emulator** as the default emulator (without “With Code”). Enter:

| Setting | Value |
| --- | --- |
| Custom Emulator Package Name | `com.loxifi.kairodos` |
| Custom Emulator Activity Name | `com.loxifi.kairodos.Launch` |
| Custom Emulator ROM Path Key | `ROM` |

Turn **Extract ROM Archives** off. No separate launch command is needed.

## ES-DE

Add this Android package rule to ES-DE's custom `es_find_rules.xml`:

```xml
<emulator name="KAIRODOS">
  <rule type="androidpackage">
    <entry>com.loxifi.kairodos/com.loxifi.kairodos.Launch</entry>
  </rule>
</emulator>
```

In the MS-DOS system's custom `es_systems.xml` configuration, add this command and select it as the emulator:

```xml
<command label="KairoDos">%EMULATOR_KAIRODOS% %ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%</command>
```

See the [ES-DE Android configuration guide](https://gitlab.com/es-de/emulationstation-de/-/blob/master/INSTALL.md) for custom file locations and system override syntax. Pass the original game file or ZIP to KairoDos.

## iiSU

iiSU reads its emulator list from two files in `Android/media/com.iisulauncher/iiSULauncher/Emuladores/` on shared storage. If your file manager cannot open `Android/media`, use Android's built-in Files app. Back up both files before editing them.

In `emuladores.json`, find the console whose `shortName` is `dos` and add this object to its `emulators` array:

```json
{
  "id": "KAIRODOS",
  "name": "KairoDos (Standalone)",
  "routeType": "uri",
  "commands": [
    {
      "description": "KairoDos",
      "command": "com.loxifi.kairodos/com.loxifi.kairodos.Launch -a android.intent.action.VIEW -d %ROM_URI%"
    }
  ],
  "packages": [
    "com.loxifi.kairodos"
  ]
}
```

Add this object to the array in `supported_emulators.json`:

```json
{
  "name": "KAIRODOS",
  "packages": [
    "com.loxifi.kairodos"
  ]
}
```

Put your games in the `dos` folder inside the folder iiSU reads games from (its ROM folder). Select the DOS (PC) console, open **Quick Settings → Console Settings → Edit Console Settings**, and choose **KairoDos (Standalone)** as the emulator. An iiSU update can replace these files; add the entries again if KairoDos disappears from the list.

## Beacon

Open **Settings** and add a platform:

| Setting | Value |
| --- | --- |
| Platform type | **Custom** |
| Name / Short name | Any, for example `MS-DOS` / `DOS` |
| Player app | **KairoDos** |
| ROMs folder | Your DOS game folder |

Scroll down, open **Advanced**, turn on **Use custom launch**, accept the warning, and enter this **am start command**:

```text
am start -n com.loxifi.kairodos/com.loxifi.kairodos.Launch -a android.intent.action.VIEW -d {file_uri}
```

Leave **File handling** at **Default** and save. Without the custom launch command, Beacon opens KairoDos's library instead of the game.

## eXoDOS installer archives

When a frontend grants access to a single eXoDOS source ZIP, KairoDos saves the installed copy in private app storage and cannot remove the frontend's source file. Selecting a writable folder within KairoDos instead lets it create the installed ZIP beside the source and offer to remove the source after play.

The app does not register a generic Android “Open with” file handler. Select games inside the app or use the explicit component configured above; APKs and unrelated documents should not offer the emulator as a handler.
