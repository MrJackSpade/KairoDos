# Ticket #71: inline reads rejected

The guarded inline ARM64 read prototype did not demonstrate a performance gain.
It was removed from the isolated worktree, and the exact original APK was restored
on the RGDS. No production emulator code changed.

## A/B/A measurement

All runs used the real app, the same Duke3D copy at 320x200, `core=dynamic`,
`cycles=auto`, `output=texturenb`, a 60-second warmup and 120-second rendered-demo
capture. All stayed awake and simpleperf reported zero lost samples. Wi-Fi power
saving was disabled before transfers. Both APKs have identical entries except
`lib/arm64-v8a/libdosbox_staging.so`; guest profiling was disabled.

| Build | Display submissions/s | Median interval | 95th percentile | Emulation thread CPU |
| --- | ---: | ---: | ---: | ---: |
| Original A | 19.367 | 33.313 ms | 133.242 ms | 82.41% |
| Prototype B | 16.025 | 33.442 ms | 166.456 ms | 80.71% |
| Restored original A2 | 19.097 | 33.326 ms | 133.282 ms | 82.93% |

The candidate's submission rate was 17.25% below the first reference; the restored
reference recovered to within 1.4% of its first result. These are **display
submissions, not unique game FPS**. One candidate run bracketed by two references
supports rejecting this prototype, not a precise causal slowdown estimate.
Scene phase, run variation and thermal differences remain limitations.

Read-helper self samples fell from 9.273 CPU seconds to zero, while unresolved
samples increased from 40.586 to 49.354 seconds (excluding the unresolved host
library). This does not prove all that time is generated code or explain the
regression. It does show why missing helper samples alone are insufficient to
claim an improvement. Do not infer a specific instruction-cache bottleneck from
the aggregate counters.

Hardware reports also have a discrepancy: `scaling_cur_freq` reads 1.992 GHz
while debug `armclk` reads 1.104 GHz in all three captures. Neither is proof of
actual frequency. DDR reports 920 MHz, GPU devfreq 800 MHz, and cooling-device
states are zero throughout. Debug GPU clock reports 198 MHz in A/B and both
198/800 MHz in A2. Raw summaries and counter readings are retained in the
[machine-readable results](benchmarks/ticket71-inline-read-results.json).

## Correctness evidence and limits

The expanded generated-code probe passed **221,184 checks on each of the RGDS
and Retroid Pocket Classic**. It uses the actual experimental emitter and checks
widths 1/2/4 at every page offset on pages 0/1/0xfffff, both actual destination
registers (w0/w1), x19 preservation, ignored upper address bits, all 16 NZCV
combinations, and live changes from direct mapping to null to a different backing
allocation. Crossing and null guards return a sentinel in this standalone probe.

This is not full-core fault/device-handler validation. Those checks, other-game
validation and audio timing gates were not completed: the candidate failed the
performance gate and is not being adopted. Do not label it generally correct or
ship the saved experimental APK based on these preliminary tests.

The [patch](benchmarks/ticket71-inline-read-prototype.patch) and
`tools/generate_arm64_read_probe.py` remain as reproducible research artifacts.
The generator takes a source checkout with that patch applied and an output C++
path; build/run instructions are in the [preparation notes](inline-read-prototype.md).

The restored APK SHA256 is
`5a114b2b0db15ef2327b09f44daef61a2b4c8544792b7777c4b610e270f23db9`.
The installed game configuration and game files were not modified.
