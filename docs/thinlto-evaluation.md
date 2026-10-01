# ThinLTO-only evaluation (#69)

## Matched build experiment

Both APKs use the current adopted runtime at `fda400fe`, shared `9be507e`,
including the ordinary OPL worker. Subsequent commits before this experiment
only add audit/deployment documentation and tools. Unrelated catalog and
controller work in the main checkout was excluded by using the isolated
checkout created for the adopted APK.

The candidate adds only `add_compile_options(-flto=thin)` and
`add_link_options(-flto=thin)` to the DOS native CMake build. It retains `-O2`,
ARM64 target, debug symbols, frame-pointer policy, OPL settings and every other
compile option. The patch is preserved in `benchmarks/ticket69-thinlto.patch`.
The prebuilt pinned dependency archives are unchanged; source-built core,
SDL and bundled libraries participate in this ThinLTO experiment. The Android
frontend is not rebuilt with LTO.

`tools/check_thinlto_builds.py` verified:

- All 433 compile commands have identical source paths and token sequences
  after removing the candidate's one `-flto=thin` flag.
- Shared-library link inputs and other compile/link flags match.
- The only differing non-signature APK entry is
  `lib/arm64-v8a/libdosbox_staging.so`.
- Unstripped debug ELFs retain `.debug_info`; each matches its own packaged
  core's Build ID. SHA-256 hashes identify APKs and both core forms.

| Artifact | Baseline | ThinLTO | Difference |
| --- | ---: | ---: | ---: |
| Packaged core bytes | 26,052,688 | 25,382,408 | -670,280 (-2.57%) |
| `.text` bytes | 6,367,004 | 6,204,956 | -162,048 (-2.55%) |
| Unstripped ELF bytes | 141,937,080 | 129,096,120 | -12,840,960 |

This establishes smaller code/artifacts, not a runtime improvement. The
candidate built successfully with the same NDK 28.2.13676358 and SDK 36.
The measured 1m 42s was an incremental rebuild, so it is not a fair build-time
comparison against the earlier clean baseline build.

## Measurement

RGDS over Wi-Fi, Duke 3D's existing 320x200 Sound Blaster configuration,
60 seconds warmup then 120 seconds capture. Run order: baseline A1, candidate
B1, candidate B2, baseline A2. APK installed hashes were verified after each
change. No game files or cycles, audio-buffer or display settings were edited for this experiment.

All runs use light observation with one-second device-local frame-history
polling, sparse CPU/thermal checkpoints and start/end screenshots. Full
simpleperf profiling was omitted because its measurement effect was already
shown to be large in the preceding OPL experiment. That observer follow-up,
including 0.5 versus 1.0 second frame polling, is recorded in
`benchmarks/ticket74-observer-overhead.md` and
`benchmarks/ticket74-doom-performance.md`. It does not establish zero cost for
this collector or isolate each observer's overhead on ThinLTO code.

## Repeated runtime results

| Capture | Build | Submissions/sec | App CPU | Main CPU | Mixer CPU | OPL worker CPU |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| A1 | Baseline | 31.566 | 120.647% | 86.312% | 23.412% | 4.969% |
| B1 | ThinLTO | 29.165 | 119.542% | 85.733% | 23.283% | 4.900% |
| B2 | ThinLTO | 30.783 | 120.146% | 85.916% | 23.434% | 4.960% |
| A2 | Baseline | 31.477 | 121.144% | 86.751% | 23.401% | 4.979% |

Mean rates: baseline **31.5215**, ThinLTO **29.9744**, a **-4.91%** difference
of means. Both candidate runs are below both baseline runs. Baseline spread
is 0.089 submissions/s; candidate spread is 1.618. Two runs per build are
not a confidence interval or a universal compiler-performance result, but
there is no repeatable improvement to adopt in this workload.

These are surface submissions, not necessarily unique guest frames. All
start/end screenshots show the rendered Duke demo. Frame histories overlap
without gaps; actual frame windows are 118.96-119.96 seconds, with maximum
collector gaps of 1.16-1.18 seconds. Sparse SoC temperatures span
44.375-49.444 C and sampled cooling states are zero. AudioFlinger underrun
counter deltas are zero in all four runs; that does not independently prove
PCM or guest correctness. CPU percentages use one core as 100%; neither
power consumption nor a precise cause of the slowdown was measured.

## Decision and restoration

**Reject ThinLTO for the current baseline; keep the existing flags.** The
smaller library did not translate into a measured runtime gain. No CPU tuning,
-O3, profile-guided optimization or game-specific workaround was added to
rescue the result. This completes #69's isolated evaluation; no implementation
ticket is justified. It does not establish that ThinLTO loses on every DOS
workload, and broad compatibility tests were not run for an unadopted change.

The candidate patch was removed from the isolated build checkout after its
artifacts were preserved. Main's build files were never changed. The normal
OPL-enabled APK was restored over Wi-Fi, hash-verified, and used for A2:

`c4e58b4f12d9a8414d91bf97ee474ad7dc036e0d4de38761c089e0b5e7e62a39`

It remains installed. Kairo98 was not changed. No experimental APK remains
on the device. Raw capture hashes, full summaries, compile/link checks,
section sizes, core Build IDs and APK identities are preserved in
[the data record](benchmarks/ticket69-thinlto.json).

To reproduce the build comparison, build `:kairodos:assembleDebug` at
`fda400fe` with the pinned shared revision and dependencies, preserve the
baseline APK, unstripped ELF, `compile_commands.json` and `build.ninja`, then
apply the saved two-line patch and repeat from the same path. Run
`tools/check_thinlto_builds.py --artifacts <directory> --ndk <NDK path>
--source-revision fda400fe --shared-revision 9be507e9d50f07f9ebbe4574864d8e66300b62c9`.
The directory must have `baseline/` and `thinlto/` children with those artifacts
and `thinlto.patch` at its root. Debug symbols are retained locally rather
than committed as binaries.
