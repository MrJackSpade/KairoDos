# DOS game catalog

KairoDos reads a user-selected DOS folder. Game archives and extracted game folders
stay on the user's device; the app prepares one selected game for DOSBox Pure at
launch. A recognized game's content ID supplies its title, description, tags,
cover, and screenshot to the shared Kairo library and detail screens. The app
packages metadata and downscaled artwork, never eXoDOS games, operating systems,
or launchers.

## Source and matching

The seed is the owner's eXoDOS v6 collection. `tools/generate_dos_catalog.py`
reads `Content/XODOSMetadata.zip` (`xml/all/MS-DOS.xml` and `Images/MS-DOS/`)
and the game ZIPs in `eXo/eXoDOS/`. The XML `ApplicationPath` batch basename
maps a record to its game ZIP. This mapping is an import detail, not a UI mode
or a required directory layout for users.

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

On Android, a seekable document provider lets the app read a ZIP's central
directory directly while scanning. For providers that cannot seek, it copies
one archive to temporary storage for that scan. Standalone files are hashed as
streams, and the selected game is copied to private storage only when launched.

Catalog records are sharded under `kairodos/src/main/assets/catalog/dos/` by
the first two hex digits of the content ID. Artwork paths point to small WebP
assets under `art/catalog/dos/`. The current import matches 7,633 archives to
7,629 distinct content IDs and includes 14,938 artwork thumbnails. Four content
IDs are shared by two named archives, so those records retain filename variants.
On a missing match, the shared library shows
the user's filename and no artwork. The user never needs to own eXoDOS.

## Regeneration and review

Install Python 3 and Pillow, then run:

```sh
python tools/generate_dos_catalog.py "<eXoDOS root>" "<ignored staging directory>" --art
```

The tool caches completed archive identities in `hashes.sqlite` and writes a
manifest of source image names and SHA-256 values alongside the catalog shards.
The generated import manifest is committed as `catalog-provenance.json` in this
directory so each bundled thumbnail can be traced to its source image.
Review records and artwork rights before replacing packaged assets or publishing
an APK. The local eXoDOS files and staging database are not part of the repo.
Metadata source access does not itself grant redistribution rights to descriptions
or images in either the free or paid app.

Recognition does not prove a game boots. Some titles need particular startup
commands, configuration, or media mounting; these are separate emulator tasks.
