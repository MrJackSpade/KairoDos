# Installing catalog files

KairoDos ships a clean core catalog. Game files are always supplied by the user.

The separate **KairoDos adult catalog** contains the excluded records and their artwork. It is intended for adults and also includes titles conservatively withheld pending further review.

1. Download [kairodos-adult-v1.zip](https://github.com/MrJackSpade/KairoDos/raw/refs/heads/main/catalog/optional/kairodos-adult-v1.zip) from GitHub (also attached to releases starting with v0.9.6). Keep the ZIP intact.
2. In the library menu, choose **Import catalog file** and select that downloaded ZIP using Android's file picker.
3. Use **Update installed catalogs** to check only the catalogs you installed. No additional catalogs are discovered.
4. Use **Remove catalog** to delete its metadata and packaged artwork. This does not delete games or explicit personal overrides.

The app has no link, directory, name or update source for this download until you select the file. The package's own manifest identifies its product, identity, revision and update address. A replacement must keep that identity and source, increase the revision, and pass validation. Failed replacements keep the old archive.

## Reproducing the catalogs

Run `python tools/build_catalog_packages.py`, then `pwsh -File tools/BuildPublicDosCatalog.ps1`. The inputs are `catalog/core-review-v1.json`, the preserved complete DOS import in `catalog/complete-input-v1.zip`, the description review and controller-default ledgers, and artwork sources under `catalog/artwork/`. None of these complete inputs is an app asset.

`catalog/excluded-ids-v1.json` is the reproducible exclusion ledger, including associated content hashes. Only checksum-pinned, visually reviewed images in `approvedArtwork` may be bundled or referenced by the core. Other images are withheld from the core pending review; this is not a claim that all withheld images are explicit. Update the review inputs, regenerate, and run `python shared/tools/audit_core_catalog.py dos`. Release workflows also run that audit against both signed APK variants and the Play AAB before publication.

Optional package updates require incrementing the package revision in the generator. Publish the archive and its checksum manifest together. The ordinary core feed never references the optional feed.
