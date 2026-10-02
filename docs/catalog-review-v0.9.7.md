# 0.9.7 catalog handoff

This version supersedes the metadata exclusions in 0.9.6. All 7662 game records retain their reviewed titles/descriptions and matching/control information in every distribution. PC-98 additionally preserves all 216 content hashes and 3,433 filename keys.

The positive artwork whitelist contains 29 paths. The separate artwork catalog contains 14907 withheld paths, including unreviewed images. These counts do not mean every withheld image is explicit; the remaining content-review uncertainty is deliberately kept outside the Play artwork catalog.

Generation losslessly reconstructs the full catalog from data, controls and both artwork parts. Core snapshot audits verify all metadata survives and only reviewed artwork references appear. APK audits inspect actual packaged parts and image checksums. The Play/GitHub Gradle switch was checked in both directions to catch stale asset inclusion. Both apps compile and the 30 Python regression tests pass across the two products.

Tag-triggered workflows produce and audit signed APKs and the Play AAB. No Google Play submission is authorized or performed here. Listing and rating review remain with the Play Store task.
