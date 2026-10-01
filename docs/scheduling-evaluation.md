# Scheduling / priority evaluation (#77)

Status: completed; priority change rejected. No product behavior changed.

## Baseline and hypothesis

RGDS (`RG DS`), Android, existing KairoDos APK SHA-256
`213bf68a69eea378dd616c6d54964f53a982491bb786117d57a5b54ee9ab5cc8`.
This includes adopted #76 symbol binding and prior PGO work. Unrelated local
catalog/controller changes are excluded from the installed build.

The actual Java emulation worker is `nice 0`, Linux scheduler policy 0,
`cpuset:/top-app`, affinity CPUs 0-3. Main/UI and AudioTrack service threads
have other priorities; their values must not be mistaken for the emulator's.
The native renderer and another child inherit the truncated worker name;
identify the original Java worker, not every thread named `KairoDos-emulat`.

Kairo98's native bridge sets its worker to -16 before creating its presenter.
That is prior art, not evidence that the same change benefits DOS.

## Protocol

Fresh direct ACTION_VIEW launch of the installed Duke Nukem 3D archive.
Verify and answer the sound-card prompt with N; unchanged 320x200 configuration,
sound enabled, 60-second warmup and 120-second rendered attract-demo capture.
Use `tools/capture_dos_demo.py --observation light`, with additional /proc task
stat/schedstat/cgroup snapshots approximately every ten seconds. Both variants
use the same observation overhead. Surface submissions are not unique game FPS.

A: unchanged worker nice 0. B: root shell `renice -n -16 -p <worker-TID>` after
all native workers are created at the launch prompt. Verify resulting nice
value from /proc, and preserve all other worker priorities. This process-local
experiment changes neither APK nor game files. Every fresh launch restores A.
If the experiment justifies adoption, an actual app implementation requires a
separate build/verification, including inheritance and lifecycle review.

Scheduler counters separately report CPU execution and runnable queue delay.
Use post-warmup deltas, reporting their own elapsed window (which differs slightly
from the surface/audio capture windows); sleeping is not scheduling delay.
Capture temperatures, cooling states, frame history completeness and AudioFlinger
underrun deltas. Repeat A-B-B-A before evaluating the throughput effect.

## Reproduction and limits

After verifying the launch prompt:

```powershell
python tools/capture_dos_scheduler.py --adb D:\android-sdk\platform-tools\adb.exe --serial 192.168.1.118:5555 --output <new-directory>
python tools/summarize_dos_demo.py <directory> --clock-ticks 100
python tools/summarize_dos_scheduler.py <directory>
```

The first three runs used the same sampler logic from an ignored local wrapper;
the fourth uses its parameterized version committed under tools/. Sampling reads
are performed from the workstation through ADB and are equal for both conditions.
The ten-second cadence and light capture do not resolve individual wakeup tails
or every transient clock/thermal change. Aggregate scheduler wait gives a budget,
not an assurance that every millisecond can be removed by priority.

No inference of improved priority on arbitrary devices or other workloads is
justified by this one device/demo. A negative adoption decision leaves all game
compatibility, UI/audio policy and installed binary behavior unchanged.


## Results and decision

| Run | Worker nice | Surface submissions/s | Worker CPU seconds | Runnable wait seconds | Scheduler window seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| A1 | 0 | 31.696 | 98.463 | 4.605 | 116.349 |
| B1 | -16 | 31.538 | 98.705 | 4.366 | 117.045 |
| B2 | -16 | 31.902 | 99.538 | 4.150 | 116.637 |
| A2 | 0 | 32.194 | 98.845 | 4.202 | 116.033 |

Baseline mean **31.945**, candidate mean **31.720** submissions/s: **-0.70%**.
There is no demonstrated performance gain; the change is smaller than the
within-condition spread. Both candidate results are below the second baseline.
Runnable delay is about 3.6-4.0% of elapsed time in all four runs; raising priority
does not remove it. Worker execution occupies about 85% of elapsed time.

All frame histories were complete, all AudioFlinger underrun deltas were zero,
and all start/end screenshots were visually checked as rendered Duke3D scenes.
The sparse clock snapshots matched across runs and cooling states remained zero.
These measurements do not prove priority has no effect on other hardware or
under artificial contention. They do reject adopting this global priority change
on the strength of the current RGDS workload. No affinity, realtime scheduling,
system governor, game timing or unrelated configuration changes were attempted.

The final A2 fresh launch restored the ordinary worker priority; the installed
APK remains the original #76 build. No replacement APK or additional game
regression run is needed for a rejected process-local experiment. The unchanged
binary retains its prior runtime verification. Kairo98 is untouched.

Machine-readable results, per-thread totals, priority verification and raw-data
hashes: `docs/benchmarks/ticket77-scheduling-comparison.json`. Raw captures remain
locally under `.tmp/ticket77/{a1,b1,b2,a2}`. Scheduling is not the large missing
performance budget suggested by the earlier PC98 improvement.
