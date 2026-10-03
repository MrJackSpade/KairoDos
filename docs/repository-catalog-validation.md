# Repository catalog validation

Catalog update delivery is independent of application releases. All four catalog parts and indexes, core snapshots, optional import archives and their manifests are tracked in Git. Artwork payloads remain under catalog/artwork. The optional artwork manifest uses a raw.githubusercontent.com URL on main, with revision 4 replacing the former release-download target. Imported copies retain the same manifest address and catalog identity.

Validation performed for both products:

- Regenerated from reviewed inputs; existing metadata, controls, four parts and core snapshots remained unchanged.
- Checked exact reconstruction of bundled core data from data/controls/reviewed art and all part indexes.
- Checked every core and optional feed manifest against its tracked archive's size and SHA-256; verified repository URLs on main.
- Checked every artwork reference resolves to a tracked repository image: 14,936 DOS paths and 6,123 PC-98 paths.
- Verified DOS public snapshots and the shared repository copies match.
- Ran package generator tests, including a references-only archive with an empty embedded-image index.
- Imported the actual new reference archives on RGDS using both existing apps. DOS imported in 1,334 ms and PC-98 in 506 ms. Install/update/rollback/overrides/removal fixtures passed for both.

The artwork import archives are 1,599,188 bytes (DOS) and 848,898 bytes (PC-98); images are downloaded through the existing repository artwork flow. No application code, signed binaries or release tags change for this correction. Source integrity checks do not constitute a new visual content review.
