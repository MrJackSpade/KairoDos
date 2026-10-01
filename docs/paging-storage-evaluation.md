# Core-owned paging storage

Status: retained after repeated normal-PGO comparison and representative runtime
checks. Final normal-product integration and deployment validation remains open
in the broader low-level investigation; do not treat this as its completion.

## Change and ABI audit

Checked reads first resolve `paging` through the dynamic global-offset table,
then load its cached page pointer and guest data. Under `KAIRO_STAGING`, marking
this single core-owned object hidden replaces the first dependent pointer load
with direct address formation. The actual candidate ELF confirms ADRP/ADD at
that point. Page checks, handler dispatch, page-crossing behavior, table contents,
guest memory layout and guest configuration are unchanged.

The host opens the core with RTLD_LOCAL and resolves seven C bridge functions.
Its source has no paging-object lookup. Dynamic-symbol audits of every packaged
native library find no external paging consumer. Comparing core dynamic names
and binding classes removes only `paging`; every other entry remains unchanged.
Do not broaden this to global data binding or weak C++ symbols. Non-Kairo builds
retain the original visibility. This is a local source change, not a new ABI
expectation for unrelated upstream consumers.

## Performance

Fresh direct launches, unchanged Duke3D 320x200 configuration and sound, 60-second
warmups and 120-second light-observer captures. All APKs have identical frontend
payloads and differ only in the core library. Installed APK and game configuration
hashes match at both endpoints of every capture.

| Order | Build | Surface submissions/sec |
| --- | --- | ---: |
| A1 | Reference | 32.8478 |
| B1 | Local paging object | 34.4247 |
| B2 | Same candidate | 34.4943 |
| A2 | Restored reference | 33.9838 |

Means are **33.4158 → 34.4595 submissions/sec (+3.1234%)**. Both candidate runs
exceed both references. Retain the baseline spread and small sample count when
reporting this result; it is not an exact latency attribution
or a universal FPS promise. It includes compiler/linker layout changes caused by
the visibility declaration. Surface submissions are not necessarily unique guest
frames. All histories are complete, recorded AudioFlinger underrun increments
are zero, and rendered Duke endpoints were inspected. Sparse clock/thermal data
does not prove constant effective frequency across the captures.

The candidate passes actual-core Doom and Cannon Fodder fixtures: rendered video,
nonzero PCM, generated ARM64 execution, pause/surface recreation, reset, two
sessions, stopping while paused and complete JIT-mapping release. Doom shows
gameplay; Cannon's screenshot shows its animated opening. These representative
checks do not prove universal game compatibility. The normal-PGO matching gate
passes without weakening it.

[Raw summaries, identities and runtime evidence](benchmarks/paging-storage-evaluation.json)
retain the measurements. The current RGDS installation is the tested normal-PGO
candidate; later experiments must overwrite that same package and restore the
verified final normal build when the full investigation concludes.
