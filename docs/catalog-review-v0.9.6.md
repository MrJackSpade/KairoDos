# Catalog release handoff: v0.9.6

Both apps use the same shared catalog installer and the same features for GitHub and Google Play. No Play submission was made.

## Catalog partition

| Product | Core records | Excluded entries | Associated excluded content hashes | Reviewed core images |
| --- | ---: | ---: | ---: | ---: |
| KairoDos | 7,550 content IDs | 112 content IDs | Included in entry count | 29 |
| Kairo98 | 2,103 name-index entries; 129 content hashes | 1,239 name-index entries | 87 | 30 |

All 109 existing DOS adult markers and all 797 PC-88/PC-98 adult markers are excluded. Additional exclusions cover censored titles, supplementary title review and uncertain records. The exact inputs, reasons and resulting identities are versioned in `catalog/core-review-v1.json` and `catalog/excluded-ids-v1.json`.

Only the 59 checksum-pinned images explicitly reviewed for this release remain in the cores. All other artwork is withheld from the cores pending review; withholding does not mean every image is explicit. The supplemental title review is conservative and is not a comprehensive audit of every game's contents. A Play listing/rating review is still needed; no age-rating approval is claimed.

Separate optional ZIPs contain the excluded metadata and artwork. Their update addresses are confined to the ZIPs, GitHub-side publication files and installed copies in private app storage. Neither app, core feed nor Play listing contains a source, name, link or recommendation for them. Generic file import, installed-catalog updates and removal use the shared frontend. Installed catalogs are applied in catalog-ID order after core updates and before explicit personal overrides.

## Verification

- Thirty existing Python metadata/artwork regression tests pass.
- Both Android products compile, including PC-98's with-images and without-images variants.
- The RGDS ran isolated fixtures through both actual product adapters: fresh state, invalid imports, installation, packaged artwork, restart, name/hash matching, later revisions, rollback for checksum/identity/source/schema/path failures, explicit title/artwork overrides, removal, user-file preservation, and checks restricted to installed sources. Both actual optional ZIPs also passed import validation on the device.
- Both locally signed debug APKs passed the clean-core artifact audit. Existing package IDs were updated with `adb install -r`; signing keys were preserved.
- New core cache filenames and a core-v2 marker prevent the previous combined snapshot from being loaded by this build. No dedicated upgrade migration was added. Existing cached images without active catalog references cannot supply excluded metadata.
- Tag workflows retain both signed APK variants and the Play AAB. Their distribution audit inspects the actual signed outputs against the exclusion ledger and reviewed image checksums before publishing. These CI release results must be read from the tagged runs; local debug checks are not a substitute for them.

## Release artifacts for the Play Store task

- [KairoDos v0.9.6](https://github.com/MrJackSpade/KairoDos/releases/tag/v0.9.6)
- [Kairo98 v0.9.6](https://github.com/MrJackSpade/Kairo98/releases/tag/v0.9.6)

The existing workflows publish the signed APKs and AABs at those releases. Each release also provides the separate adult-catalog ZIP and its update manifest. Use the Play AAB after the workflow's signing and content checks pass, then perform the separate listing and rating review. Installation instructions for catalog files are in [catalogs.md](catalogs.md), outside the apps.
