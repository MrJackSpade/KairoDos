# DOS game catalog

KairoDos reads a user-selected DOS folder. Game archives and extracted game folders
stay on the user's device; the app prepares one selected game for DOSBox Pure at
launch. A recognized game's content ID supplies its title, description, tags,
cover, and screenshot to the shared Kairo library and detail screens. The app
packages metadata and downscaled artwork, never eXoDOS games, operating systems,
or launchers.

## Source and matching

The seed is the owner's eXoDOS v6 collection. `tools/generate_dos_catalog.py`
reads `Content/XODOSMetadata.zip` (`xml/all/MS-DOS.xml` and `Images/MS-DOS/`),
`Content/!DOSmetadata.zip` (per-game DOSBox configs), and the game ZIPs in
`eXo/eXoDOS/`. The XML `ApplicationPath` batch basename maps a record to its
game ZIP. Renamed archive variants can match the config by their single outer
ZIP folder. These are import details, not a UI mode or a required directory
layout for users.

The `sha256-dos-manifest-v1` identity hashes a canonical manifest of each game
file's normalized path, byte count, and CRC-32. The ZIP central directory
supplies these fields without decompressing every file; an extracted folder
computes the same CRC-32 values from its files. The importer strips a single
common outer folder, folds ASCII case, sorts paths, and excludes eXoDOS `.exo`
marker files. Thus ZIP compression, member order, and extraction of the outer
folder do not change a match. The CRC-32 manifest is a fast catalog lookup key,
not a cryptographic proof of file contents. Standalone disk images and programs
use `sha256-dos-file-v1` over their bytes. Two pairs of collection archives
share the same manifest; the catalog selects their distinct metadata using the
source filename when it is available.

Installer detection does not depend on a catalog match. Every game ZIP in the
supplied eXoDOS directory has an empty `.exo` member. KairoDos uses that marker
to identify an eXoDOS source ZIP, and its own ` - Installed.zip` filename
distinguishes the durable installed copy. Unknown sources still install;
without a matching startup profile, DOSBox Pure chooses the launch program.
The generic eXoDOS Windows installer extracts the game ZIP to a same-named
directory, with optional update and save overlays. The supplied collection has
no `Update/` tree. Because the ZIP already contains the extracted game files,
KairoDos preserves them in a separate archive and mounts that archive as the
game filesystem. It does not execute the Windows batch wrapper. Pure stores
subsequent writes in its separate save overlay. Removing the source ZIP is
offered only after installation and launch preparation succeed.

On Android, a seekable document provider lets the app read a ZIP's central
directory directly while scanning. For providers that cannot seek, it copies
one archive to temporary storage for that scan. Standalone files are hashed as
streams, and the selected game is copied to private storage only when launched.

Catalog records are sharded under `kairodos/src/main/assets/catalog/dos/` by
the first two hex digits of the content ID. Artwork paths point to small WebP
assets under `art/catalog/dos/`. The initial artwork import matched 7,633
archives to 7,629 distinct content IDs and included 14,938 artwork thumbnails.
The launch import matches all 7,669 current source archives, retains those
records, and adds identities for archives whose content changed. All 7,669
current archives have startup profiles; shared content IDs retain filename
variants.
On a missing match, the shared library shows
the user's filename and no artwork. The user never needs to own eXoDOS.

## Regeneration and review

Install Python 3 and Pillow, then run:

```sh
python tools/generate_dos_catalog.py "<eXoDOS root>" "<ignored staging directory>" --art
python tools/generate_dos_catalog.py "<eXoDOS root>" . --launch-only
```

The tool caches completed archive identities in `hashes.sqlite` and writes a
manifest of source image names and SHA-256 values alongside the catalog shards.
The generated import manifest is committed as `catalog-provenance.json` in this
directory so each bundled thumbnail can be traced to its source image.
Review records and artwork rights before replacing packaged assets or publishing
an APK. The local eXoDOS files and staging database are not part of the repo.
Metadata source access does not itself grant redistribution rights to descriptions
or images in either the free or paid app.

At launch KairoDos writes the selected game's DOSBox config beside its private
cached archive. It retains machine, audio, CPU, and `[autoexec]` settings,
removes the source collection's C: mount, and rewrites game and disc paths to
the mounted archive. Directory mounts inside that archive use Pure's locally
patched mirror drive. When an eXoDOS startup script references another game
folder, KairoDos looks up that folder's content hash in the selected library,
stages that archive separately, and mounts it as a dependency. DOSBox Pure loads
this sidecar config. Games with alternate
configs offer a startup variant chooser. Nothing is copied from the user's DOS
library into the APK.

Three DOS door games (Azalta, Dominions, and Legend of the Red Dragon) need a
player name and generated startup files that eXoDOS normally creates with a
Windows launcher. KairoDos asks for a DOS-safe name once and creates those files
inside Pure's writable game overlay on launch.

Recognition and a startup config do not prove that a game boots. eXoDOS Windows
`exception.bat` launchers can use other emulators, companion programs, and setup
operations outside DOSBox Pure. The catalog records their presence for auditing;
KairoDos does not execute Windows batch files. Games that require those steps
need an Android implementation or may remain incompatible with Pure.
