# DOS game catalog and installation

[← KairoDos](../README.md) · [All documentation](index.md)

- [For players](#for-players)
  - [What the catalog does](#what-the-catalog-does)
  - [eXoDOS installers and startup](#exodos-installers-and-startup)
  - [Catalog updates](#catalog-updates)
  - [Controller defaults](#controller-defaults)
- [For catalog maintainers](#for-catalog-maintainers)
  - [Content identity](#content-identity)
  - [Update delivery](#update-delivery)
  - [Controller profile data](#controller-profile-data)
  - [Catalog generation](#catalog-generation)
  - [Reviewed display language](#reviewed-display-language)
  - [Compact artwork references (0.9.5)](#compact-artwork-references-095)

## For players

### What the catalog does

KairoDos scans a user-selected DOS folder. Game ZIPs, DOSZ archives, extracted folders, standalone programs, and disk images remain on the user's device. The selected game is prepared in a persistent writable DOSBox Staging drive when launched. Known games receive title, description, tags, cover, screenshot, and startup settings from a hash-keyed catalog seeded from eXoDOS metadata. Unknown games remain playable by filename.

A missing match shows the user's filename without catalog artwork. The user does not need to own eXoDOS. The catalog enriches the shared Kairo library. It does not create a separate eXoDOS UI or require the user to keep the original collection layout.

### eXoDOS installers and startup

An empty `.exo` ZIP member identifies an eXoDOS source archive even when the catalog has no matching hash. It appears with ` - Installer`. On first launch, KairoDos creates a verified ` - Installed.zip` in the writable selected folder and launches it. The installed archive appears under the game title without the filename suffix. When the session ends, the app offers to remove the source archive. For a one-file grant from another frontend, the installed copy lives in private app storage and the source cannot be removed by the app.

The source ZIP already holds the extracted game files; KairoDos does not run the Windows batch installer. DOSBox Staging keeps later writes in a persistent per-game drive; existing Pure overlays are preserved and imported once. For recognized games, KairoDos adapts cataloged DOSBox configuration and startup commands to the mounted writable drive and any user-owned disc or dependency files. Alternate configs can appear as startup variants. Windows exception launchers that depend on other emulators or companion programs are not executed.

### Catalog updates

KairoDos checks for an updated catalog when it opens, and **Update catalogs** in Menu › Manage library checks on demand. Online records can update titles, descriptions, tags, launch settings, and controller profiles without an APK update. A failed update leaves the active catalog intact.

### Controller defaults

Controller configuration is global and selects With Sticks or Without Sticks defaults. Auto requires two sticks on one controller. The built-in Doom/Duke profiles have separate keyboard-only defaults for handhelds without sticks. Touchscreen layouts are unchanged. See [FPS controller defaults](fps-controller-profiles.md) for the current With Sticks and Without Sticks layouts.

Downloaded profiles replace bundled defaults; only explicitly saved user controls take precedence. Mouse-direction mappings have a saved 0.1x-20x speed slider in the shared controller editor. Key cycles accept any available guest keys and share state within the selected D-pad or shoulder pair; they cannot detect weapon ownership or changes made in-game. Doom-titled games with different controls, such as DOOM 2D and DOOM, the Roguelike, retain their normal defaults.

## For catalog maintainers

### Content identity

`sha256-dos-manifest-v1` hashes a canonical list of normalized file paths, sizes, and CRC-32 values. A ZIP's central directory supplies those values without decompressing every file; an extracted folder computes them from its files. One common outer folder is stripped, ASCII case is folded, and eXoDOS `.exo` markers are excluded. Recompression and unpacking the outer folder therefore preserve a match. The CRC-32 manifest is a fast lookup identity, not cryptographic proof of file contents. Standalone files use `sha256-dos-file-v1` over their bytes.

KairoDos reads the shared catalog (`LayeredGameCatalog` with the `DosCatalog` schema), as every Kairo app does. Each game is a name-index record (`dos:<first 16 hex digits of its content ID>`: title, tags, heart, artwork, controls) and its archive's record in a content-ID shard names that game and carries its launch (`folder`, the eXoDOS DOSBox `configs` per version, `exception`). An archive holding several games picks each by its file's name (`variants`). `folders-v1.json` lists which games provide a launch folder another game's configuration mounts.

### Update delivery

The app fetches `catalog/core-v4.meta.json` from this repository first and skips the archive when its SHA-256 is unchanged; `catalog/core-v4.zip` holds the catalog's documents, read one at a time, and each language's descriptions are a download of their own (`core-v4-<language>.zip`). Versions before the shared catalog keep reading the frozen `Kairo/catalog/dos/core-v2.zip`.

Descriptions are original (`catalog/research/descriptions-v1.json` and its translations); the eXoDOS text in the import is never shipped. Source attribution and the separate artwork review remain recorded in [licensing](licensing.md).

### Controller profile data

`catalog/research/controller-profiles-v1.json` selects controller layouts by game content ID. Its `profiles` section preserves the Doom grouping; `assignments` selects researched per-game recommendations, and `presets` contains validated bindings. The import gives each game its preset as its `controller` (`profile` and `defaults`).

Catalog presets store bindings under `defaults` with `withoutSticks` and optional `withSticks` arrays. Missing With Sticks defaults fall back to Without Sticks; explicitly empty arrays disable bindings. Legacy catalog bindings migrate unchanged to Without Sticks, and user overrides retain precedence. Shared persistence and migration details are in `shared/docs/controller-configuration.md`.

### Catalog generation

`tools/generate_dos_catalog.py` reads eXoDOS LaunchBox XML and images, DOSBox configuration metadata, and game archives to produce the hash catalog and optional downscaled artwork. Its generated provenance manifest records image sources and checksums. Keep source games and staging data out of Git. Review descriptions and images before packaging them.

To refresh the bundled index from the owner's current collection, first generate a complete private scan, then reconcile it with the bundled catalog:

```powershell
python tools/generate_dos_catalog.py '<current eXoDOS root>' .tmp/catalog-refresh
python tools/generate_dos_catalog.py '<current eXoDOS root>' .tmp/catalog-refresh --refresh-from-staging
python tools/import_catalog.py
python shared/tools/app_catalog.py .
```

The refresh rejects incomplete or changed scans and missing launch metadata. It removes identities absent from the current directory, refreshes descriptions, tags, and launch configurations, and preserves existing bundled artwork without importing new images. Unambiguous title or filename matches carry artwork and controller profiles to changed identities. Research assignments and presets from `catalog/controller-research/recommendations.json` are merged into the generated profile catalog; assignments follow unambiguous identity migrations and never override Doom's existing profile. Archives without LaunchBox records remain indexed by filename with launch settings from their source folder. `catalog/catalog-provenance.json` records every current archive and its resolved identity; `catalog/catalog-refresh.json` reports added and removed identities, identity migrations, and changed metadata fields. No game files are copied. Review both reports and the generated catalog before committing.

### Reviewed display language

`catalog/metadata-review-v1.json` is the source for reviewed title spellings,
descriptions, and adult-theme markers. Explicit title words use `♥` (for example,
`F♥ck Quest`); descriptions summarize the premise and gameplay without graphic
details. Reviews are tied to content IDs and, where needed, exact variant keys.
They never change archive names, content IDs, artwork paths, launch settings,
controller profiles, or user overrides. Ordinary words and names such as
"comic strip" and "Dick Tracy" are not subject to a runtime word filter.

The import turns the `♥` tag into the shared catalog's `heart`, which shows the
same marker in every app. An absent heart is not an age rating.

To regenerate metadata-only edits without the private source collection:

```powershell
python tools/dos_catalog_review.py
python -m unittest discover -s tools -p 'test_dos_catalog_review.py'
```

The full catalog generator also applies these reviews after source import.
Missing identities, changed source titles, and missing variants require review
instead of silently applying an edit to a different game. The public-export
check rejects stale reviewed fields. Blank source notes are omitted because
they are not valid description overrides; all nonempty descriptions are exported.

### Compact artwork references (0.9.5)

Distributed records store `artwork: {id, variant, kinds}` instead of image paths.
`id` is the 64-character artwork identity, `variant` is its 12-character variant,
and `kinds` is 1 for cover, 2 for preview, or 3 for both. An empty object means
no artwork. The artwork identity can differ from the current game content ID
because a catalog refresh can retain artwork from a previous identity.

`DosArtworkReferences` reconstructs the established asset paths before catalog
layering. The existing download template then supplies the host. Cached files,
bundled images, legacy catalog records, and local artwork overrides retain their
existing paths and precedence. Generators reject references that cannot be
represented losslessly. The public exporter rejects unconverted artwork.
Old apps reject the new snapshot and keep their active catalog until updated;
0.9.5 can read both representations. This changes catalog representation, not
access permissions on the image server.
