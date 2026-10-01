# ARM64 result-mask flag experiment

Status: rejected. Production flag semantics and dispatch remain unchanged.

The logging-disabled diagnostic attributes 1.714 sampled main-thread CPU seconds
to get_ZF and 1.673 to get_SF. The experiment replaces their type-switch dispatch
inside dynrec condition helpers with width/sign mask tables for 49 result-derived
types. All other types delegate to the original functions. Composite conditions
retain their original short-circuit behavior. This exchanges control flow for
table loads; a reduction in work cannot be assumed from source alone.

The native probe passes 5,242,880 exact comparisons over every byte-valued type,
random and edge result bits, and five materialized flag patterns. It checks exact
mask-valued returns and invalid-type diagnostic counts, not just truth values.
The actual core passes Doom rendering, nonzero PCM, generated execution, reset,
pause/surface lifecycle, repeated sessions and released JIT mappings. The normal
PGO matching gate passes unchanged.

| Fresh-launch order | Build | Surface submissions/sec |
| --- | --- | ---: |
| A1 | Reference | 33.9588 |
| B | Flag masks | 31.5631 |
| A2 | Restored reference | 32.8478 |

Reject this candidate: it is below both references. These three runs use
60-second warmups and 120-second capture windows, identical frontend payloads,
unchanged game configuration and installed-APK hash verification at both ends.
All histories are complete and AudioFlinger underrun increments are zero.
Rendered Duke3D endpoints were inspected. One candidate run cannot establish
a precise regression, and sparse clock/thermal data cannot prove constant
effective clocks. Surface submissions are not necessarily unique guest frames.

[Results](benchmarks/arm64-flag-mask-evaluation.json) and the
[unapplied patch](benchmarks/arm64-flag-mask-prototype.patch) retain the evidence.
`tools/generate_arm64_flag_mask_probe.py <patched-tree> <output.cpp>` generates
the native equivalence probe. This rejects a runtime mask table; compile-time
specialization using a proven in-block flag producer is a separate possibility
requiring its own provenance, invalidation and generated-code tests.
