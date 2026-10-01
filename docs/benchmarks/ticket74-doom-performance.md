# Doom cross-check and frame-collector sensitivity (#74)

## Setup

RGDS over Wi-Fi, actual application, DOOM (1993) at its existing 320x200.
The APKs are the matched pair in `ticket74-performance-builds.json`; their
installed hashes were verified. Comparator, queue statistics and guest-code
profiling were disabled. Each run answered the verified sound prompt, warmed
up for 60 seconds, and captured for 120 seconds using light observation.

The installed game selected Gravis Ultrasound, which would not exercise OPL.
Its exact DEFAULT.CFG and GUS.SEL were backed up, then the game's bundled
Sound Blaster option was selected for all four runs. No executable, resolution,
or control was edited. After the runs, the two original files were restored,
the temporary SB16.SEL removed, and both restored hashes matched the backups.
The tested configuration selected music and effects device 3; the worker
also accumulated CPU time in both worker captures.

## Results

Order: A1, B1, B2, A2. CPU is percent of one core. Presentation rates count
surface submissions, not necessarily unique guest frames.

| Run | Build | Poll interval | Submissions/sec | App CPU | Main CPU | Mixer CPU | Worker CPU |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A1 | Baseline | 0.5 s | 27.287 | 118.233% | 89.424% | 23.613% | - |
| B1 | Worker | 0.5 s | 28.156 | 120.679% | 88.675% | 23.851% | 2.912% |
| B2 | Worker | 1.0 s | 29.600 | 121.804% | 89.658% | 23.853% | 2.919% |
| A2 | Baseline | 1.0 s | 28.983 | 119.655% | 90.754% | 23.577% | - |

Worker differences are +3.18% at 0.5 s and +2.13% at 1.0 s. These are small
and there is only one pair per polling interval: do not claim a precise Doom
speedup or a statistical confidence interval. No throughput regression was
observed in either matched comparison. Application CPU increases by about
2.1-2.4 percentage points; power consumption was not measured.

All histories overlap without gaps. Actual frame windows span 119.45-119.94 s.
Maximum sampler gaps are 0.68/0.67/1.17/1.16 s in run order. AudioFlinger
underrun-counter deltas are zero in all four runs; this counter is not an
independent PCM-quality test. Sparse SoC temperature samples range from
46.111 to 50.625 C, and all sampled cooling states are zero.

Start/end images were inspected. Seven show rendered 3D demo scenes; B2's
start shows the credits between demos, with 3D gameplay at the end. These
are whole attract-loop observations containing brief static screens, not
identical scene/frame transcripts or uninterrupted 3D renders. The boundary
and PCM equivalence fixtures, rather than these screenshots, establish the
audio ordering checks.

## Collector follow-up and decision

Reducing polling from 0.5 to 1.0 s coincides with +6.21% baseline and +5.13%
worker throughput. Run variation and attract-loop phase are not separated
from collector cost, so these are sensitivity observations, not an exact
measurement of collector overhead. The collector is still present; neither
rate represents a zero-observer measurement. Complete overlapping histories
show that the slower polling retains the required presentation data here.

The worker's direction of effect persists at both polling rates, and no
second-workload audio/throughput regression was observed. Combined with the
repeated Duke comparisons (two lighter pairs, +14.64% difference of means;
two fully profiled pairs, +4.01%) and the completed independent sample,
timeline, timer/status/routing, mode, queue-pressure and lifecycle checks,
this supports adopting the ordinary OPL worker. It does not support a universal
14.64% gain or a 60 FPS claim. ESFM and AdLib Gold remain synchronous.

Normal builds enable the worker with no comparator, stress delay or queue
statistics. `-PoplWorker=false` retains the reference build for diagnosis;
verification and stress remain Debug-only. Final build/deployment evidence is
recorded separately in `../opl-worker-adoption.md` before closing #74.

Raw-capture hashes, summaries, timing, configuration backup hashes and APK
references are in [the data record](ticket74-doom-performance.json). The
captured originals remain ignored local data; no game content is committed.
