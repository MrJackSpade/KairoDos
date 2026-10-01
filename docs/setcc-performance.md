# Ticket #72: SETcc performance checkpoint

Historical measurement checkpoint. See the subsequent [adoption decision](setcc-adoption.md); the numerical uncertainty below remains applicable.

**Candidate remains under investigation; not adopted.** Both prototype runs were
higher than all three baseline runs, but baseline variance makes the size and
confidence of the improvement uncertain. Remaining correctness gates still apply.

## Five actual-app runs

| Order | Build | Display submissions/s | Median / p95 interval (ms) |
| --- | --- | ---: | ---: |
| A | Baseline | 19.0578 | 33.310 / 133.278 |
| B | SETcc prototype | 19.4972 | 33.366 / 133.229 |
| A2 | Restored baseline | 17.4661 | 33.336 / 166.485 |
| B2 | Same prototype | 19.6599 | 33.309 / 133.234 |
| A3 | Restored baseline | 18.7675 | 33.320 / 133.272 |

These are SurfaceFlinger submissions, not unique game FPS. Baseline mean was
18.4305 (sample SD 0.8477); prototype mean 19.5785 (sample SD 0.1151).
The observed mean difference is +6.23%, influenced by the low A2 result.
The lower prototype run is only 2.31% above the highest baseline. Do not claim a
proven 6.23% gain or statistical confidence from this small sample.

All captures used the real app over RGDS Wi-Fi, unchanged Duke at 320x200,
`core=dynamic`, `cycles=auto`, texturenb and the existing sound setup. Screenshots
show the rendered demo, not splash screens. All stayed awake and reported zero
lost perf samples. No game configuration, guest program, clocks or Kairo98 code
was changed. The prototype differs from the baseline APK in exactly one ZIP entry:
`lib/arm64-v8a/libdosbox_staging.so`. Matching packaged/symbol ELF IDs were checked.

## Timing limitation and harness correction

These five runs used the existing nominal 60-second warmup loop, followed by a
120-second simpleperf recording. That loop performs an ADB power-state check
before every sleep, so it takes longer than 60 wall-clock seconds. Actual launch
to first sample was **79.77, 80.53, 80.84, 80.27 and 80.22 seconds**, respectively;
first sample elapsed values are retained in the JSON. This is consistent across
runs but must not be represented as an exact 60-second warmup. Scene phase varies,
including A3 starting farther into the demo.

For future runs, `tools/capture_dos_demo.py` uses a monotonic 60-second deadline
and records actual warmup duration. Its command-line parsing and source were
checked; the revised timing loop has not yet been used for a device capture.
This does not retroactively change the recorded results. Verify the RGDS launch
prompt first, then supply `--adb`, `--serial`, and a new `--output` directory.
The helper uses RGDS display IDs and the installed simpleperf binary; run only one
capture at a time. Initial screenshot/thread discovery adds setup time after the
warmup, separately from the 120-second recorder interval.

## Host cost evidence

| Run | Emulation CPU seconds | Interpreter self seconds | Dynrec core self seconds |
| --- | ---: | ---: | ---: |
| A | 103.394 | 0.747 | 2.606 |
| B | 102.758 | 0.333 | 2.889 |
| A2 | 101.879 | 0.848 | 2.808 |
| B2 | 102.727 | 0.354 | 2.414 |
| A3 | 102.566 | 0.737 | 2.616 |

Reduced interpreter self-time repeats in both prototype runs. It is a small part
of total emulation CPU time; the rest is not automatically recoverable by SETcc.
Function-body samples exclude inline/callee work and are not cycle-exact costs.

CPU scaling_cur_freq reported 1992000 while debug armclk reported 1104000000
throughout. These disagree; neither establishes actual frequency. Cooling states
were zero, and full temperature/counter readings are retained. No frequency
settings were changed. The comparison does not claim all-game performance safety.

## Restoration and remaining gates

The original APK is installed again and its on-device SHA256 was verified:
`5a114b2b0db15ef2327b09f44daef61a2b4c8544792b7777c4b610e270f23db9`.
No profiling markers are enabled. The prototype remains only in the isolated
worktree and archived patch for further checks; production source is unchanged.

Protected mode/default 32-bit code, fault handling and more effective-address
forms remain unverified. #72 stays active before the queue advances. These
performance results are encouraging evidence, not permission to skip those gates.

Complete APK/ELF identities, per-run samples, counters, summaries and host costs:
[performance evidence](benchmarks/ticket72-setcc-performance.json).

