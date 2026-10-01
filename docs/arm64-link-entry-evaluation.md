# ARM64 cached block-entry evaluation

Status: rejected. Production emulator source is unchanged; the normal reference
APK was restored to the RGDS after this experiment.

Time-valid generated-code sampling identifies the original two-load block-link
sequence: 3.131 sampled CPU seconds at the target-code load, 0.487 at the target
block load, 1.060 at address setup and 2.925 at the branch. These are sampled
locations, not instruction latency or a forecast of recoverable time.

The isolated candidate caches both outgoing code addresses in each CacheBlock.
Generated exits perform one pointer load instead of two. LinkTo, incoming-edge
invalidation and block initialization update the cached address alongside the
existing bookkeeping target. All seven generated exit sites use it. Existing
field offsets remain unchanged; the block grows from 128 to 144 bytes.

An on-device native probe executes the actual extracted link emitter and cache
link/invalidation methods: 32,768 checks cover both exits, target reuse,
cross-page companion invalidation, self/cyclic links and all NZCV combinations.
Its page-handler bookkeeping is simulated. Separately, the actual candidate
core passes the Doom fixture with rendered gameplay, nonzero PCM, generated-code
execution, reset, two sessions, pause/surface lifecycle and released JIT mappings.
These checks do not establish universal guest compatibility.

## Normal-PGO performance gate

All APKs have identical frontend payloads and differ only in the core library.
Each installed APK and unchanged Duke configuration is checked before and after
capture. Fresh launches use 60-second warmup and 120-second capture windows.

| Order | Build | Surface submissions/sec |
| --- | --- | ---: |
| A1 | Reference (`read-a2`) | 34.3185 |
| B | Cached entry | 33.3083 |
| A2 | Restored reference | 33.8269 |

The candidate is below both bracketing references. One candidate run cannot
establish a precise regression, but there is no demonstrated gain to justify
the larger block layout and additional invalidation invariant. Reject the
candidate. All three histories are complete and AudioFlinger underrun increments
are zero. Endpoint screenshots show rendered Duke3D scenes. Sparse thermal and
clock observations do not prove identical effective clocks throughout each run.

[Machine-readable results](benchmarks/arm64-link-entry-evaluation.json) retain
identities, capture summaries and fixture evidence. The
[research patch](benchmarks/arm64-link-entry-prototype.patch) is not applied to
production source. Remaining performance candidates are still open.
