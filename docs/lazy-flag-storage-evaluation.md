# Core-owned lazy-flag storage

The normal known-producer helpers still resolve the core-owned `lflags` object
through a GOT pointer load. Under `KAIRO_STAGING`, declaring that object hidden
allows direct address formation. This is a visibility-only change: lazy-flag
layout, producers, evaluation, faults and invalidation remain unchanged.
Non-Kairo builds retain upstream visibility.

The packaged candidate differs from the complete combined product APK only in
`libdosbox_staging.so`. Dynamic-symbol comparison removes only `B lflags`; no
symbol is added. The host uses the existing C callback bridge and does not import
this object. The candidate is built normally with PGO enabled and diagnostic
verification disabled. Source, ELF, APK and packaged-core identities are retained
in the final comparison evidence.

| Fresh-launch order | Build | Surface submissions/sec |
| --- | --- | ---: |
| B1 | Combined, original visibility | 34.6716 |
| C1 | Hidden lflags | 35.1227 |
| C2 | Hidden lflags | 36.7586 |
| B2 | Combined, original visibility | 34.3310 |

Both candidate observations exceed both references. The means are 34.5013 and
35.9406 submissions/sec, approximately **+4.17%**. The candidate has appreciable
run-to-run spread; this is a small-sample observed result, not a precise universal
FPS gain or isolated load-latency measurement. Visibility also changes compiler
and linker layout. Every run uses a fresh launch, 60-second warmup and 120-second
capture, unchanged Duke configuration, complete frame history, matching before/
after APK and configuration hashes, and zero AudioFlinger underrun increments.
Rendered endpoints were inspected.

Final representative-game checks and deployment are recorded in the
[integration report](low-level-final-integration.md). No setting or game code
change is needed to use this optimization.
