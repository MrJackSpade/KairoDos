# DOS game catalog and installation

KairoDos scans a user-selected DOS folder. Game ZIPs, DOSZ archives, extracted folders, standalone programs, and disk images remain on the user's device. The selected game is staged for DOSBox Pure when launched. Known games receive title, description, tags, cover, screenshot, and startup settings from a hash-keyed catalog seeded from eXoDOS metadata. Unknown games remain playable by filename.

## Content identity

`sha256-dos-manifest-v1` hashes a canonical list of normalized file paths, sizes, and CRC-32 values. A ZIP's central directory supplies those values without decompressing every file; an extracted folder computes them from its files. One common outer folder is stripped, ASCII case is folded, and eXoDOS `.exo` markers are excluded. Recompression and unpacking the outer folder therefore preserve a match. The CRC-32 manifest is a fast lookup identity, not cryptographic proof of file contents. Standalone files use `sha256-dos-file-v1` over their bytes.

The catalog is sharded by content ID under `kairodos/src/main/assets/catalog/dos/`. A missing match shows the user's filename without catalog artwork. The user does not need to own eXoDOS.

KairoDos checks for an updated catalog when it opens, and **Update game catalog** in the library menu checks on demand. It fetches the small `Kairo/catalog/dos/online-v1.json` revision file first and skips the archive when its SHA-256 is unchanged. The public snapshot at `Kairo/catalog/dos/online-v1.zip` contains the 256 metadata shards, folder index, and controller profile index. The app verifies the downloaded archive against the revision file and validates the complete snapshot before saving it; a failed update leaves the active catalog intact. The public export omits eXoDOS/LaunchBox descriptions because their redistribution rights are not established. Existing games retain their bundled descriptions; new online records can supply titles, tags, launch settings, and controller profiles without an APK update. Artwork remains bundled pending a separate rights review. The shared frontend owns download and atomic cache behavior; DOS-specific code validates and reads the ZIP records. Run `pwsh -File tools/BuildPublicDosCatalog.ps1` after changing bundled metadata. CI checks that both public archives and revision files match the sanitized source.

Known Doom engine releases and level collections use the content IDs in `catalog/dos/controller-profiles-v1.json` to select a controller layout. D-pad up/down moves forward/back and left/right strafes. L1/R1 turns left/right for play without sticks. The left stick moves and strafes; the right stick turns through Doom's relative mouse input. A fires, B uses, X runs, Y opens the automap, and Start opens the game menu. L2/R2 cycle backward/forward through the weapon keys 1–7, wrapping around. The first L2 press sends 7; the first R2 press sends 1. Original Doom has direct weapon keys rather than a native next/previous weapon command, so this cycle cannot track weapon changes made elsewhere or skip unavailable weapons. Each shoulder pair's key sequence can be changed in the shared controller editor using any available guest keys. A saved per-game controller layout takes precedence. Doom-titled games with different controls, such as DOOM 2D and DOOM, the Roguelike, retain the normal defaults.

## eXoDOS installers and startup

An empty `.exo` ZIP member identifies an eXoDOS source archive even when the catalog has no matching hash. It appears with ` - Installer`. On first launch, KairoDos creates a verified ` - Installed.zip` in the writable selected folder and launches it. The installed archive appears under the game title without the filename suffix. When the session ends, the app offers to remove the source archive. For a one-file grant from another frontend, the installed copy lives in private app storage and the source cannot be removed by the app.

The source ZIP already holds the extracted game files; KairoDos does not run the Windows batch installer. DOSBox Pure keeps later writes in a per-game overlay. For recognized games, KairoDos adapts cataloged DOSBox configuration and startup commands to the mounted archive and any user-owned disc or dependency archives. Alternate configs can appear as startup variants. Windows exception launchers that depend on other emulators or companion programs are not executed.

## Catalog generation

`tools/generate_dos_catalog.py` reads eXoDOS LaunchBox XML and images, DOSBox configuration metadata, and game archives to produce the hash catalog and optional downscaled artwork. Its generated provenance manifest records image sources and checksums. Keep source games and staging data out of Git. Review descriptions, images, and their redistribution rights before packaging them; see [licensing](licensing.md).

The catalog enriches the shared Kairo library. It does not create a separate eXoDOS UI or require the user to keep the original collection layout.
