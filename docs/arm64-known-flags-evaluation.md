# Known-producer conditional branches

Status: retained; the final normal-product gate is complete. See the
[integration result](low-level-final-integration.md), which supersedes isolated
percentages when describing the shipped combined result.

The existing decoder queue tracks arithmetic producers whose flags remain live
within one translated block. When it contains exactly one CMP or TEST producer,
the candidate selects a condition helper specialized for that producer and width.
This removes runtime lazy-flag type dispatch and repeated composite-condition
helper calls. It preserves exact return masks, operand/result state and the
existing ARM64 call emitter. Only Jcc exits use the specialization. Parity,
SETcc, LOOP, partial-flag producers, shifts, rotates and unknown queue states
retain their existing paths. No game settings or code are changed.

The queue is cleared at block initialization and flag acquisition; partial
producers append entries, and a full producer replaces previous entries. The
branch closes the translated block, preventing later flag-elimination passes
from removing its live producer. Checked-memory, fault and invalidation paths
are unchanged.

## Correctness

`tools/generate_arm64_known_flags_probe.py <patched-tree> <output.cpp>` extracts
the actual helper/selector and original flag functions. Its ARM64 executable
passes **2,752,512 exact-value comparisons** for six producer/width combinations
and fourteen conditions, including edge and randomized flag state. Selection
checks cover queue sizes zero, one and two, every flag type and all conditions.
The first archived probe preceded verification-only counter additions; rebuilding
and running the reusable generator also passes the same comparison count.

A separate diagnostic build asserts the actual lazy-flag type at every executed
specialized helper. Recorded counters reach 63,963,136 in Doom, 12,582,912 in
Cannon Fodder and 677,380,096 in Duke without a mismatch. Doom and Cannon core
fixtures also pass rendering, nonzero PCM, dynamic execution, reset, pause/surface
handling, repeated sessions, paused exit and released JIT mappings. Duke passes
the full 60-second warmup and 120-second rendered-demo window. This diagnostic
build disables PGO as required by the existing guard; its throughput is not a
normal-build performance result.

## Normal performance

| Fresh-launch order | Build | Surface submissions/sec |
| --- | --- | ---: |
| B1 | Known producer | 35.9709 |
| A1 | Original reference | 32.6968 |
| B2 | Known producer | 34.6565 |
| A2 | Original reference | 33.7559 |

Normal PGO is enabled and verification is absent. Both candidate runs exceed
both references: means **33.2264 → 35.3137 (+6.28%)**. Every run uses the same
frontend, unchanged game configuration, 60-second warmup and 120-second capture.
APK/configuration hashes match before and after; histories are complete and
AudioFlinger underrun increments are zero. Rendered endpoints were inspected.

Two observations per condition and differing demo phases limit precision.
Surface submissions are not necessarily unique guest frames, and sparse thermal
or clock samples do not prove constant effective frequency. Do not add this gain
to other isolated percentages: the combined normal build must be measured.

See [results and identities](benchmarks/arm64-known-flags-evaluation.json) and
the [evaluated patch](benchmarks/arm64-known-flags-prototype.patch).
