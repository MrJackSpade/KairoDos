# DOS game catalog and installation

KairoDos scans a user-selected DOS folder. Game ZIPs, DOSZ archives, extracted folders, standalone programs, and disk images remain on the user's device. The selected game is prepared in a persistent writable DOSBox Staging drive when launched. Known games receive title, description, tags, cover, screenshot, and startup settings from a hash-keyed catalog seeded from eXoDOS metadata. Unknown games remain playable by filename.

## Content identity

`sha256-dos-manifest-v1` hashes a canonical list of normalized file paths, sizes, and CRC-32 values. A ZIP's central directory supplies those values without decompressing every file; an extracted folder computes them from its files. One common outer folder is stripped, ASCII case is folded, and eXoDOS `.exo` markers are excluded. Recompression and unpacking the outer folder therefore preserve a match. The CRC-32 manifest is a fast lookup identity, not cryptographic proof of file contents. Standalone files use `sha256-dos-file-v1` over their bytes.

The catalog is sharded by content ID under `kairodos/src/main/assets/catalog/dos/`. A generated `hidden-index-v1.json` holds the visibility flags so opening the library does not parse every shard before its first frame. A missing match shows the user's filename without catalog artwork. The user does not need to own eXoDOS.

KairoDos checks for an updated catalog when it opens, and **Update game catalog** in the library menu checks on demand. It fetches the small `Kairo/catalog/dos/online-v1.json` revision file first and skips the archive when its SHA-256 is unchanged. The public snapshot at `Kairo/catalog/dos/online-v1.zip` contains the 256 metadata shards, folder index, and controller profile index. The app verifies the downloaded archive against the revision file and validates the complete snapshot before saving it; a failed update leaves the active catalog intact. The public export omits eXoDOS/LaunchBox descriptions because their redistribution rights are not established. Existing games retain their bundled descriptions; new online records can supply titles, tags, launch settings, and controller profiles without an APK update. Artwork remains bundled pending a separate rights review. The shared frontend owns download and atomic cache behavior; DOS-specific code validates and reads the ZIP records. Run `pwsh -File tools/BuildPublicDosCatalog.ps1` after changing bundled metadata. CI checks that both public archives and revision files match the sanitized source.

The generated `catalog/dos/controller-profiles-v1.json` selects controller layouts by game content ID. Its `profiles` section preserves the existing Doom grouping; `assignments` selects researched per-game recommendations, and `presets` contains their validated bindings. A saved per-game controller layout takes precedence over catalog defaults. The existing Doom profile remains unchanged: D-pad up/down moves forward/back and left/right strafes; L1/R1 turn left/right for play without sticks. The left stick moves and strafes; the right stick turns through Doom's relative mouse input at 8× the cursor movement rate. Mouse-direction mappings have a saved 0.1×–20× speed slider in the shared controller editor, so each game/direction can be tuned without changing touch input or other mappings. Untouched saved Doom presets upgrade to 8×; customized profiles retain their settings. A fires, B uses, X runs, Y opens the automap, Select opens the game menu, and Start confirms menu choices. L2/R2 cycle backward/forward through the weapon keys 1–7, wrapping around. The first L2 press sends 7; the first R2 press sends 1. Original Doom has direct weapon keys rather than a native next/previous weapon command, so this cycle cannot track weapon changes made elsewhere or skip unavailable weapons. Each shoulder pair's key sequence can be changed in the shared controller editor using any available guest keys. Doom-titled games with different controls, such as DOOM 2D and DOOM, the Roguelike, retain the normal defaults.

## eXoDOS installers and startup

An empty `.exo` ZIP member identifies an eXoDOS source archive even when the catalog has no matching hash. It appears with ` - Installer`. On first launch, KairoDos creates a verified ` - Installed.zip` in the writable selected folder and launches it. The installed archive appears under the game title without the filename suffix. When the session ends, the app offers to remove the source archive. For a one-file grant from another frontend, the installed copy lives in private app storage and the source cannot be removed by the app.

The source ZIP already holds the extracted game files; KairoDos does not run the Windows batch installer. DOSBox Staging keeps later writes in a persistent per-game drive; existing Pure overlays are preserved and imported once. For recognized games, KairoDos adapts cataloged DOSBox configuration and startup commands to the mounted writable drive and any user-owned disc or dependency files. Alternate configs can appear as startup variants. Windows exception launchers that depend on other emulators or companion programs are not executed.

## Catalog generation

Controller configuration is global and selects With Sticks or Without Sticks defaults.
Auto requires two sticks on one controller. Catalog presets store these under `defaults`
with `withoutSticks` and optional `withSticks` arrays. Missing With Sticks defaults fall
back to Without Sticks; explicitly empty arrays disable bindings. Legacy catalog bindings
migrate unchanged to Without Sticks, and user overrides retain precedence. The built-in
Doom/Duke profiles have separate keyboard-only defaults for handhelds without sticks.
Touchscreen layouts are unchanged. Shared persistence and migration details are in
`shared/docs/controller-configuration.md`.

`tools/generate_dos_catalog.py` reads eXoDOS LaunchBox XML and images, DOSBox configuration metadata, and game archives to produce the hash catalog and optional downscaled artwork. Its generated provenance manifest records image sources and checksums. Keep source games and staging data out of Git. Review descriptions, images, and their redistribution rights before packaging them; see [licensing](licensing.md).

To refresh the bundled index from the owner's current collection, first generate a complete private scan, then reconcile it with the bundled catalog:

```powershell
python tools/generate_dos_catalog.py '<current eXoDOS root>' .tmp/catalog-refresh
python tools/generate_dos_catalog.py '<current eXoDOS root>' .tmp/catalog-refresh --refresh-from-staging
pwsh -File tools/BuildPublicDosCatalog.ps1
```

The refresh rejects incomplete or changed scans and missing launch metadata. It removes identities absent from the current directory, refreshes descriptions, tags, and launch configurations, and preserves existing bundled artwork without importing new images. Unambiguous title or filename matches carry artwork and controller profiles to changed identities. Research assignments and presets from `catalog/controller-research/recommendations.json` are merged into the generated profile catalog; assignments follow unambiguous identity migrations and never override Doom's existing profile. Archives without LaunchBox records remain indexed by filename with launch settings from their source folder. `docs/catalog-provenance.json` records every current archive and its resolved identity; `docs/catalog-refresh.json` reports added and removed identities, identity migrations, and changed metadata fields. No game files are copied. Review both reports and the generated catalog before committing; the public export also updates the first-party `shared/catalog/dos/` snapshot.

The catalog enriches the shared Kairo library. It does not create a separate eXoDOS UI or require the user to keep the original collection layout.
