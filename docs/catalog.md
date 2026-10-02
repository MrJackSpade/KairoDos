# DOS game catalog and installation

KairoDos scans a user-selected DOS folder. Game ZIPs, DOSZ archives, extracted folders, standalone programs, and disk images remain on the user's device. The selected game is prepared in a persistent writable DOSBox Staging drive when launched. Known games receive title, description, tags, cover, screenshot, and startup settings from a hash-keyed catalog seeded from eXoDOS metadata. Unknown games remain playable by filename.

## Content identity

`sha256-dos-manifest-v1` hashes a canonical list of normalized file paths, sizes, and CRC-32 values. A ZIP's central directory supplies those values without decompressing every file; an extracted folder computes them from its files. One common outer folder is stripped, ASCII case is folded, and eXoDOS `.exo` markers are excluded. Recompression and unpacking the outer folder therefore preserve a match. The CRC-32 manifest is a fast lookup identity, not cryptographic proof of file contents. Standalone files use `sha256-dos-file-v1` over their bytes.

The catalog is sharded by content ID under `kairodos/src/main/assets/catalog/dos/`. A generated `hidden-index-v1.json` holds the visibility flags so opening the library does not parse every shard before its first frame. A missing match shows the user's filename without catalog artwork. The user does not need to own eXoDOS.

KairoDos checks for an updated catalog when it opens, and **Update game catalog** in the library menu checks on demand. It fetches the small `Kairo/catalog/dos/online-v1.json` revision file first and skips the archive when its SHA-256 is unchanged. The public snapshot at `Kairo/catalog/dos/online-v1.zip` contains the 256 metadata shards, folder index, and controller profile index. The app verifies the downloaded archive against the revision file and validates the complete snapshot before saving it; a failed update leaves the active catalog intact. The public export includes nonempty descriptions, reviewed titles, and heart tags. Descriptions are retained or rewritten case by case; they are no longer excluded as a category. Online records can update these fields, launch settings, and controller profiles without an APK update. The shared frontend owns download and atomic cache behavior; DOS-specific code validates and reads the ZIP records. Run `pwsh -File tools/BuildPublicDosCatalog.ps1` after changing bundled metadata. CI checks that both public archives and revision files match the reviewed source. Source attribution and the separate artwork review remain recorded in [licensing](licensing.md).

The generated `catalog/dos/controller-profiles-v1.json` selects controller layouts by game content ID. Its `profiles` section preserves the Doom grouping; `assignments` selects researched per-game recommendations, and `presets` contains validated bindings. Downloaded profiles replace bundled defaults; only explicitly saved user controls take precedence. See [FPS controller defaults](fps-controller-profiles.md) for the current With Sticks and Without Sticks layouts. Mouse-direction mappings have a saved 0.1x-20x speed slider in the shared controller editor. Key cycles accept any available guest keys and share state within the selected D-pad or shoulder pair; they cannot detect weapon ownership or changes made in-game. Doom-titled games with different controls, such as DOOM 2D and DOOM, the Roguelike, retain their normal defaults.

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

## Reviewed display language

`catalog/metadata-review-v1.json` is the source for reviewed title spellings,
descriptions, and adult-theme markers. Explicit title words use `♥` (for example,
`F♥ck Quest`); descriptions summarize the premise and gameplay without graphic
details. Reviews are tied to content IDs and, where needed, exact variant keys.
They never change archive names, content IDs, artwork paths, launch settings,
controller profiles, or user overrides. Ordinary words and names such as
"comic strip" and "Dick Tracy" are not subject to a runtime word filter.

DOS already displays catalog tags, so its adult marker is emitted as a `♥` tag.
This gives the same visible marker as Kairo98 without adding a field that older
DOS update validators would reject. An absent heart is not an age rating.

To regenerate metadata-only edits without the private source collection:

```powershell
python tools/dos_catalog_review.py
pwsh -File tools/BuildPublicDosCatalog.ps1
python -m unittest discover -s tools -p 'test_dos_catalog_review.py'
```

The full catalog generator also applies these reviews after source import.
Missing identities, changed source titles, and missing variants require review
instead of silently applying an edit to a different game. The public-export
check rejects stale reviewed fields. Blank source notes are omitted because
they are not valid description overrides; all nonempty descriptions are exported.
