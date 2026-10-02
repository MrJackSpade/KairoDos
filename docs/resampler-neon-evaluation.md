# ARM64 interpolating resampler evaluation

Status: retained; the final normal-product gate is complete. See the
[integration result](low-level-final-integration.md), which supersedes isolated
percentages when describing the shipped combined result.

The logging-disabled diagnostic attributes 8.06 sampled mixer CPU seconds to
SpeexDSP's single-precision interpolating filter. Its existing NEON override
only covers direct filtering. The candidate packs the four independent
interpolation accumulators into NEON lanes. It preserves their input order,
cubic coefficients, final reduction, filter quality, rates and state handling.

The first prototype failed exact comparison after a rate change at quality zero.
The original compiler uses a differently rounded reduction when the oversample
factor is one. The corrected candidate retains the complete original function
for that path. The original prototype was never installed as the game candidate.

The corrected actual packaged cores pass 220 cases and 1,839,645 bit-identical
samples for **each** of the float and integer APIs: qualities 0–10, mono/stereo,
ten rate pairs, partial buffers, zeros, impulses, noise, reset, skip and rate
changes. `tools/speex_resampler_equivalence_probe.cpp` reproduces these checks;
build once normally and once with `KAIRO_RESAMPLER_INT16`, then pass the original
and candidate core libraries. Both libraries must resolve the matching Android
C++ runtime. Doom and Cannon Fodder core fixtures pass rendering, nonzero PCM,
generated execution, reset, pause/surface handling, repeated sessions, paused
exit and released JIT mappings. Cannon's inspected image is its animated opening,
not evidence of gameplay coverage.

| Fresh-launch order | Core | Submissions/sec | Mixer CPU, % of one CPU |
| --- | --- | ---: | ---: |
| A1 | Reference | 33.9838 | 24.0135 |
| B1 | NEON interpolation | 32.3873 | 21.4055 |
| A2 | Reference | 32.1733 | 23.9072 |
| B2 | NEON interpolation | 33.3974 | 21.3972 |

All runs use 60-second warmups and 120-second captures, identical frontend
payloads and unchanged Duke configuration. Installed hashes match before/after;
frame histories are complete and AudioFlinger underrun increments are zero.
Rendered endpoints were inspected. The mean mixer CPU reduction is **10.68%**;
whole-process CPU falls **2.18%**. Presentation rates overlap, with a mean change
of -0.56%; this is **not a demonstrated FPS improvement**. Surface submissions
are not necessarily unique guest frames. Sparse temperatures/clocks and two runs
per build do not establish a precise throughput estimate.

A separate quality-five stereo kernel benchmark saves roughly 39% process CPU
across four input rates in A/B/B/A order, with identical output counts. That is
kernel cost, not whole-game performance.

The installed test changes exactly one member of a copied dependency archive;
all other members are byte-identical. Production integration instead uses the
pinned source overlay under `third_party/staging-deps/ports/speexdsp/`. The normal
recipe successfully rebuilt that overlay, and a second unchanged bootstrap
performed no rebuild. The resulting paging-plus-audio packaged core
(`4ba37500e3b286a94e1d1f501eec6f53cbf935f48ad28439353d57060916e61b`)
also passes both exact API comparisons against the original core, with the
same case/sample counts. Combined normal-build performance and lifecycle checks are complete; see the
integration report above.

See [captured evidence](benchmarks/resampler-neon-evaluation.json) and the
[isolated experimental patch](benchmarks/resampler-neon-prototype.patch).
