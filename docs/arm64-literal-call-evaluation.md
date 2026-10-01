# Fixed-size ARM64 literal calls

Status: rejected. No production emitter change is adopted.

Time-valid diagnostic sampling attributes 6.146 CPU seconds to the original
four-instruction helper-address setup, plus 0.935 to its BLR. These are sampled
locations, not recoverable instruction latencies.

The isolated candidate keeps every call site exactly 20 bytes: LDR literal,
BLR, branch over an eight-byte address. It executes fewer address instructions
but introduces a data load and return-path branch. All lazy-flags patch sites
retain their original size. Replacement-call patching rewrites all five words;
it cannot rely on the original trailing BLR remaining in place.

The native comparison probe extracts both actual emitters and all current
DCODE-enabled lazy-flags patches. It passes 126,720 comparisons across both
literal alignments, all flag types, operand/shift edges and all NZCV patterns.
The actual core also passes Doom rendering, PCM, generated execution, reset,
pause/surface lifecycle, repeated sessions and JIT-mapping release. The PGO
matching gate passes unchanged.

## Fresh-launch comparison

| Order | Build | Surface submissions/sec |
| --- | --- | ---: |
| A1 | Reference | 33.8269 |
| B | Literal calls | 31.7212 |
| A2 | Restored reference | 33.9588 |

The candidate is below both references, so reject it. One candidate capture is
not a precise regression estimate. Each capture uses 60 seconds warmup and 120
seconds measurement, identical frontend payloads, unchanged game configuration,
and before/after installed-APK hashes. All frame histories are complete and
AudioFlinger underrun increments are zero. Screenshots show the rendered Duke3D
demo. Sparse hardware observations do not prove identical effective clocks.

[Results and identities](benchmarks/arm64-literal-call-evaluation.json) and the
[unapplied research patch](benchmarks/arm64-literal-call-prototype.patch) preserve
the experiment. Generate the native probe with
`tools/generate_arm64_literal_call_probe.py <baseline-tree> <patched-tree> <output.cpp>`;
compile using the NDK ARM64 compiler and run on the device. It is a preliminary
correctness gate, not a replacement for full-core fault/SMC and game tests if a
future candidate demonstrates a worthwhile performance gain.
