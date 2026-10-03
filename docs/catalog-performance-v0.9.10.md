# Catalog application performance, v0.9.10

Measured on the RGDS with the actual complete core feeds and optional artwork packages, using isolated app storage and both real product adapters.

| Operation | KairoDos | Kairo98 |
| --- | ---: | ---: |
| Core application | 794 ms | 1,239 ms |
| Full artwork application | 4,615 ms | 1,666 ms |
| Complete artwork file import, including copying and saving | 8,308 ms | 3,962 ms |

Application timing excludes network transfer and persisting the received archive. The artwork measurement executes the same header/data/index read and atomic activation used after download; it follows a completed, synced local copy. Core timing includes the host's catalog reload, but neither timing includes the subsequent library UI refresh. These are device observations, not a universal timing guarantee for all storage, devices or custom catalogs.

Both hosts passed format rejection, deferred bad-record/image handling, import, image access, restart, matching, installed-source-only sync, checksum/identity/source/JSON rollback, removal and preservation of user-owned files and explicit metadata/artwork overrides. Progress, completion, failure, cancellation and unchanged-revision checks also passed. Local builds succeeded for both apps. The package regression tests and full source/package audits passed; signed release artifacts are produced and audited by the tag workflows.

Whole-record and per-image runtime audits were removed. Downloads hash bytes as they arrive. Packages retain their publishing checksum inventory, while runtime reads a small `runtime.json` and flat `artwork.idx`; CI checks that these agree with the full package. ZIP directory opening remains a measurable cost, especially for DOS's roughly 15,000 artwork files. No binary serialization is needed for this change.
