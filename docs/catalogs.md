# Catalog parts

`catalog/parts/data.json` holds sanitized display and matching metadata for every game. `controls.json` holds controller, machine and launch settings. `art.json` contains positively reviewed artwork references. `art.nsfw.json` contains all remaining artwork references, including unreviewed material.

Both GitHub APKs include all four files. Only the with-images APK bundles image payloads. The Play AAB omits `art.nsfw.json` and image payloads. Functionality, package identity and signing remain the same. Core online snapshots contain metadata and controls for all games and only reviewed artwork; they are atomic snapshots derived from the parts.

Download [`kairodos-art.nsfw.zip`](../catalog/optional/kairodos-art.nsfw.zip) from this repository outside the app, then choose **Catalog management → Import catalog file** and select it using Android's file picker. It contributes only artwork, never titles or controls. **Catalog management → Update catalogs** checks the core catalog and sources belonging to installed catalogs. **Remove catalog** removes the imported artwork without touching games or explicit local overrides. Bundled GitHub artwork is part of the app and is not an installed catalog.

Regenerate with `python tools/build_catalog_packages.py` and `pwsh -File tools/BuildPublicDosCatalog.ps1`.

Small `.idx` files hold byte ranges for lazy shard loading; they follow their parent catalog distribution. The app does not parse the entire DOS catalog at startup.

Runtime installation reads the JSON and checks the product, catalog identity and supported format. It does not audit every record, controller mapping, artwork reference, image checksum or image dimensions. Those first-party content checks belong to generation and CI. Individual content is interpreted when used; a bad image does not reject an otherwise usable catalog.

Update checksums are calculated during transfer. A failed download or format check retains the previous catalog. Imported ZIPs stay intact in private app storage and activate by atomic rename, without extracting images or copying the archive again.

Artwork packages published with 0.9.10 also contain `runtime.json` (the small catalog header) and `artwork.idx` (one archive artwork path per line). Runtime reads these directly instead of parsing the publishing inventory or enumerating every ZIP entry again. CI verifies both against `catalog.json` and the complete archive contents. Update the app before importing these packages; 0.9.10 still reads older downloaded packages through their original header.

All catalog parts, import files, update manifests and artwork live in Git on `main`. App releases publish only APKs and the Play AAB. The import file contains artwork references; images remain in `catalog/artwork/` and use the existing artwork download flow. Its manifest points to the repository file on `main`, so committing a regenerated catalog with an incremented revision makes it available to installed-catalog updates without an app release.
