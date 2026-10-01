# OPL worker: observer sensitivity (#74)

The full observer bundle materially changes the measured application. Do not
use the earlier approximately 18 submissions/second as normal gameplay
performance or adopt the worker from that comparison alone.

`capture_dos_demo.py --observation light` retains the same device-local frame
history collector, 60-second warmup, 120-second capture, and start/end audio
and screenshot checks. It omits both simpleperf processes, the three
intermediate screenshots, and frequent hardware/power polling. Instead it
takes sparse CPU/thermal/power checkpoints. The summarizer records that
limitation explicitly; these are not continuously observed thermal extrema.

This measures sensitivity to the removed observer bundle. It does **not**
isolate individual observers or measure the remaining frame collector's
overhead. Light is still instrumented, not a zero-overhead reference.

The two APKs are unchanged from `ticket74-performance-builds.json`; installed
hashes were checked again. Raw-data hashes, timing metadata and summaries are
in `ticket74-observer-captures.json`. No game configuration, audio quality,
sample rate, pacing or application code is changed by this measurement mode.

## Repeated lighter comparison

Run order A4, B4, A5, B5; Duke 320x200 title demo. Start and end screenshots
were inspected and showed the rendered 3D demo in all four captures.

| Capture | Variant | Submissions/sec | App CPU (proc) | Main CPU (proc) | Mixer CPU (proc) | Worker CPU (proc) |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| A4 | Baseline | 27.414 | 114.245% | 85.736% | 23.252% | — |
| B4 | Worker | 30.561 | 120.572% | 86.120% | 23.487% | 5.019% |
| A5 | Baseline | 26.106 | 113.742% | 85.575% | 23.083% | — |
| B5 | Worker | 30.794 | 120.665% | 85.981% | 23.831% | 4.918% |

CPU percentages use one core as 100%. The mean submission rate is 26.7600
baseline versus 30.6772 worker: **+14.64% difference of means**. Both worker
runs exceed both baseline runs. Two pairs do not provide a confidence
interval, and submissions are not necessarily unique guest frames. Total
application CPU increases by approximately 6.6 percentage points; power
consumption was not measured. Main-thread utilization need not decrease
when the game uses the released time to do more work.

All frame histories overlap without gaps, with maximum collector intervals
of 0.66–0.68 seconds. Actual frame windows are 119.50–119.83 seconds. Both
simpleperf processes are absent by construction (null exits in metadata).
AudioFlinger underrun deltas are zero in all four captures. Sparse SoC
temperature ranges were 46.666–48.333, 49.444–50.000, 48.888–50.000 and
49.444–50.000 C; all sampled cooling states were zero. This does not establish
continuous thermal or scheduling equivalence.

The corresponding full-observer means were 18.0056 and 18.7277. That large
change demonstrates observer sensitivity; it does not isolate how much
comes from simpleperf versus frequent polling versus screenshots. Use the
full profiles for attribution and the lighter mode for subsequent throughput
comparisons. Do not claim that observer removal optimizes the installed app:
the observers were external measurement processes.

## Decision

The benefit persists in two lighter pairs and is larger than the observed
baseline spread. Continue the offload experiment. Keep it disabled by default
until timing-boundary/status/routing equivalence, actual application queue
pressure, and another workload's performance have been checked. Existing
mode/DC sample comparisons and standalone pressure results remain valid but
do not replace these outstanding checks. #74 remains open.

After B5, the normal adopted APK was restored using the Wi-Fi install helper.
Its installed SHA-256 was checked as
`22eba5ada93003208297e1970706d8ee9d2ff25230e16ee6afb8c3ab4577eae2`, and its
activity launched successfully. No Kairo98 or game files were changed.

## Separate pre-existing correctness observation

While reviewing the register path, `Opl::WriteReg` was found to assign
`opl.newm = selected_reg & 0x01` when `selected_reg == 0x105`. Therefore this
latch is set to one regardless of the written value. This exact statement
also exists in pre-worker commit `9dcfd7f6`; it is not introduced by offload.
The worker retains this behavior. Any change to that latch needs a separate
correctness investigation rather than being mixed into this performance A/B.
Do not silently change routing expectations in the worker equivalence tests.
