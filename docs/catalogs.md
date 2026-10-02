# Catalog parts

`catalog/parts/data.json` holds sanitized display and matching metadata for every game. `controls.json` holds controller, machine and launch settings. `art.json` contains positively reviewed artwork references. `art.nsfw.json` contains all remaining artwork references, including unreviewed material.

Both GitHub APKs include all four files. Only the with-images APK bundles image payloads. The Play AAB omits `art.nsfw.json` and image payloads. Functionality, package identity and signing remain the same. Core online snapshots contain metadata and controls for all games and only reviewed artwork; they are atomic snapshots derived from the parts.

Download `kairodos-art.nsfw.zip` from the GitHub release outside the app, then choose **Import catalog file** and select it using Android's file picker. It contributes only artwork, never titles or controls. **Update installed catalogs** checks only imported sources. **Remove catalog** removes the imported artwork without touching games or explicit local overrides. Bundled GitHub artwork is part of the app and is not an installed catalog.

Regenerate with `python tools/build_catalog_packages.py` and `pwsh -File tools/BuildPublicDosCatalog.ps1`.

Small `.idx` files hold byte ranges for lazy shard loading; they follow their parent catalog distribution. The app does not parse the entire DOS catalog at startup.
