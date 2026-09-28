# DOS game catalog and installation

KairoDos scans a user-selected DOS folder. Game ZIPs, DOSZ archives, extracted folders, standalone programs, and disk images remain on the user's device. The selected game is staged for DOSBox Pure when launched. Known games receive title, description, tags, cover, screenshot, and startup settings from a hash-keyed catalog seeded from eXoDOS metadata. Unknown games remain playable by filename.

## Content identity

`sha256-dos-manifest-v1` hashes a canonical list of normalized file paths, sizes, and CRC-32 values. A ZIP's central directory supplies those values without decompressing every file; an extracted folder computes them from its files. One common outer folder is stripped, ASCII case is folded, and eXoDOS `.exo` markers are excluded. Recompression and unpacking the outer folder therefore preserve a match. The CRC-32 manifest is a fast lookup identity, not cryptographic proof of file contents. Standalone files use `sha256-dos-file-v1` over their bytes.

The catalog is sharded by content ID under `kairodos/src/main/assets/catalog/dos/`. A missing match shows the user's filename without catalog artwork. The user does not need to own eXoDOS.

## eXoDOS installers and startup

An empty `.exo` ZIP member identifies an eXoDOS source archive even when the catalog has no matching hash. It appears with ` - Installer`. On first launch, KairoDos creates a verified ` - Installed.zip` in the writable selected folder and launches it. When the session ends, the app offers to remove the source archive. For a one-file grant from another frontend, the installed copy lives in private app storage and the source cannot be removed by the app.

The source ZIP already holds the extracted game files; KairoDos does not run the Windows batch installer. DOSBox Pure keeps later writes in a per-game overlay. For recognized games, KairoDos adapts cataloged DOSBox configuration and startup commands to the mounted archive and any user-owned disc or dependency archives. Alternate configs can appear as startup variants. Windows exception launchers that depend on other emulators or companion programs are not executed.

## Catalog generation

`tools/generate_dos_catalog.py` reads eXoDOS LaunchBox XML and images, DOSBox configuration metadata, and game archives to produce the hash catalog and optional downscaled artwork. Its generated provenance manifest records image sources and checksums. Keep source games and staging data out of Git. Review descriptions, images, and their redistribution rights before packaging them; see [licensing](licensing.md).

The catalog enriches the shared Kairo library. It does not create a separate eXoDOS UI or require the user to keep the original collection layout.
