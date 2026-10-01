# OPL worker performance experiment (#74)

The worker remains disabled by default. APK and ELF identities are in
`ticket74-performance-builds.json`. Both builds disable the sample comparator
and guest profiling; the only different non-signature APK entry is the core.

## Initial pair: frame history incomplete

RGDS, unchanged Duke 320x200 demo, 60 seconds warmup then 120 seconds capture.
Local raw captures: `.tmp/ticket74-a1` (baseline), `.tmp/ticket74-b1` (worker).
Both simpleperf captures completed with zero lost samples. AudioFlinger track
underrun counters did not increase in either run. This does not prove PCM
quality or equivalence for Duke.

| Measurement | Baseline | Worker |
| --- | ---: | ---: |
| App CPU, simpleperf task-clock / wall time | 118.81% | 124.67% |
| Main-thread sampled synthesis under PortWrite, CPU seconds | 5.404 | No samples |
| Main-thread PortWrite inclusive off-CPU seconds | 3.408 | 4.221 |
| Disjoint consecutive SurfaceFlinger histories | 1 | 2 |

CPU percentages use one CPU as 100%. Inclusive profile categories overlap;
off-CPU includes scheduling delay. No samples does not prove zero cost.
Attribution shows the intended work moved off the main thread, accompanied
by more application CPU and waiting. It does not establish a speedup.

**Do not compare the initially reported 21.294 and 22.358 submissions/second
as exact rates.** Host polling gaps of up to 4.031 and 3.906 seconds let the
127-entry SurfaceFlinger history roll over. These are observed lower bounds;
their interval distributions are incomplete too. They are excluded from any
adoption decision. Cumulative CPU and audio counters remain useful.

## Corrected collection protocol

`tools/collect_surface_history.sh` writes timestamp histories locally on the
device every approximately 0.5 seconds. Wi-Fi only transfers the finished
file. The sampler is bounded to at most 300 seconds. The capture script runs
it for 125 seconds; the summary uses the first approximately 120 seconds and
reports its actual interval separately from the CPU window.

`tools/summarize_dos_demo.py` checks overlap between consecutive histories.
Any disjoint histories suppress the exact rate and mark interval statistics
as incomplete. Counts describe surface submissions, not unique guest frames.
It retains both process tick accounting and simpleperf task-clock accounting
with their separate windows, along with temperatures, cooling states, and
AudioFlinger counter deltas. Raw clock sources disagree on this device;
their values alone do not prove physical maximum clock operation.

A 10-second RGDS sampler smoke test produced 16 complete history blocks over
9.43 seconds, maximum sampling gap 0.67 seconds, and no disjoint histories.
Both legacy captures were reprocessed successfully with the gap flags above.
Baseline and worker must both be repeated with this corrected protocol.

Local sampling also changes the query frequency and therefore measurement
overhead. Do not compare these rates against the legacy collector to infer
an application regression. Results remain instrumented surface-submission
measurements; adoption requires checking observer overhead as well as
repeating the baseline/candidate comparison.

## Two complete-history pairs

Run order was A2, B2, A3, B3. Each used the same two APKs and the same
60-second warmup / 120-second capture script. Start, midpoint, and end
screenshots were inspected: all showed the rendered Duke demo. Screenshots
and profiles remain local; summaries and raw-file hashes are recorded in
`ticket74-performance-captures.json`.

| Capture | Variant | Submissions/sec | App CPU (task-clock) | Emulation CPU (proc) | Mixer CPU (proc) | Worker CPU (proc) |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| A2 | Baseline | 17.941 | 115.632% | 80.500% | 24.493% | — |
| B2 | Worker | 18.954 | 120.924% | 78.326% | 25.600% | 5.595% |
| A3 | Baseline | 18.070 | 115.429% | 80.255% | 24.742% | — |
| B3 | Worker | 18.502 | 120.038% | 77.723% | 25.450% | 5.735% |

All four have complete overlapping frame histories, with maximum local
sampling gaps of 0.70–0.72 seconds despite host polling gaps near four
seconds. Actual frame windows were 119.50–119.98 seconds. All four simpleperf
captures report zero lost samples. AudioFlinger underrun counter delta was
zero in all four. Sampled cooling states stayed zero; SoC temperature ranges
were 47.777–52.500, 49.444–55.000, 50.000–55.000, and 50.625–55.555 C,
respectively. These observations do not rule out all scheduling or thermal
effects between runs.

Mean submission rates were 18.0056 baseline and 18.7277 worker, a **4.01%**
difference of means. Both worker runs exceeded both baseline runs, but this
is only two pairs under instrumentation, not a confidence interval or proof
of an equivalent improvement in unique guest frames. Total CPU increased.

The repeated CPU attribution supports the proposed mechanism: baseline
port-write synthesis accounted for 5.111 and 5.384 CPU seconds; neither worker
capture attributed synthesis samples to port writes. Port-write inclusive
off-CPU time increased from 3.703/4.041 seconds to 4.734/4.909 seconds. Thus
some work moved off the emulation thread, accompanied by more synchronization
and CPU overhead; it was not eliminated.

## Decision and remaining gates

Keep the worker opt-in and disabled by default. This is promising preliminary
performance evidence, not adoption. Before enabling it, finish the specified
timing-boundary/status/routing and application queue-pressure checks, measure
observer overhead, and repeat with another OPL workload. The earlier mode/DC
sample comparisons and standalone pressure tests remain useful but do not
replace those integration checks. #74 remains open; do not move to another
performance ticket while this experiment is unresolved.

After B3, the normal adopted APK was restored over Wi-Fi with `adb install
-r` through `InstallRgDs.ps1`. Its installed SHA-256 was verified as
`22eba5ada93003208297e1970706d8ee9d2ff25230e16ee6afb8c3ab4577eae2` and its
activity launched successfully. Kairo98 and game files were not changed.
