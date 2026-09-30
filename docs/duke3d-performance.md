# Duke3D performance investigation

## Required comparison

The target is playable Duke3D on the RGDS, approaching the user's reported
60 FPS with DOSBox Staging under XFCE on this device. Keep the existing
1024x768 game configuration and game files. Do not substitute the PC-98
DOSBox-X configuration. Automatic cycles are the requested product default.

Use the real Android app and audio output. Answer the existing sound-card
prompt with N, wait 60 seconds, then capture 120 seconds of the rendered title
demo. Record screenshots, SurfaceFlinger presentation timestamps, guest
`PIC_Ticks`, CPU cycles, thread CPU time, CPU/GPU/DDR clocks, and thermals.
Surface submissions are not independently measured unique game frames.
Demo scene alignment varies; small differences require additional evidence.

## September 30, 2026 results

| Configuration | Surface submissions/s | Guest clock / wall clock |
| --- | ---: | ---: |
| Imported fixed 125000 cycles, steady rendered demo | 7.83 | 0.351 |
| Automatic cycles, original scanline path | 1.85 | 0.988 |
| Automatic cycles, indexed scanline reuse | 3.53 | 0.958 |
| Indexed reuse plus experimental inline ARM64 RAM reads | 3.41 | 0.962 |

Clock ratios are separate 30-second samples within the capture. The fixed
cycle run did not maintain real-time DOS execution and is not a valid speed
solution. None of these results meets the playability target.

Retained changes: automatic cycle default and guarded indexed scanline reuse
(`a6424f2`). The reuse path avoids palette expansion and expanded RGB comparison
for unchanged VESA indexed rows while preserving VGA scheduling. Its native
correctness test verifies 351 reused rows against scalar reference output.

Discarded experiments:

- Expanded RGB row cache: moved the cost into memory comparison.
- Custom NEON equality: did not beat libc in the device microbenchmark.
- Inline ARM64 RAM reads: passed 73,728 device checks, including guarded page
  boundaries and TLB invalidation, and removed the profiled read-helper cost.
  The complete game capture did not improve, so the implementation was removed.
  Its larger generated code and scene differences prevent inferring a benefit
  from the vanished helper samples alone.

## Remaining bottleneck

After indexed reuse, the emulation thread consumed approximately 74% of one
CPU, the mixer 24%, and the presenter 1%. CPU samples put guest CPU execution
and its memory/flag helpers ahead of video conversion. Roughly 21% of sampled
callchains were incomplete around generated code; do not treat inclusive
percentages as a complete accounting of JIT execution.

A separate 120-second `simpleperf --trace-offcpu` capture of the running demo
measured approximately 96.45 CPU seconds and 24.58 off-CPU seconds on the
emulation thread. Of the off-CPU time, 20.90 seconds were normal cycle-pacing
sleep and 2.70 seconds were OPL port-write mutex waits. Display submission was
not the large blocking path. The slight sum above 120 seconds reflects sampled
CPU accounting, not additional wall time.

The original XFCE ARM64 emitter, memory helpers, and opcode implementations
match the imported implementation in the paths inspected. XFCE's
`cleanbuild.sh` selects Cortex-A55, `-O3`, frame-pointer omission, and IPO.
Android uses optimized `-O2` with assertions disabled; earlier generic O3/LTO
experiments were discarded and do not establish the result of an exactly
matched Cortex-A55 build. There is no measured Linux baseline yet. Obtain one
before claiming the Linux/Android discrepancy has been explained.

## Hardware findings

- Four Cortex-A55 cores; CPU reported 1992 MHz throughout captures, without
  active thermal cooling states. PMU effective frequency was around 1.83 GHz.
- Approximately 3 GiB physical RAM. Rockchip PMUGRF fields identify LPDDR3.
- Android reports a 920 MHz DDR clock. The XFCE DDR firmware's decoded LPDDR3
  target is 1056 MHz; the `1560MHz` firmware filename describes other memory
  types and is not proof of 1560 MHz LPDDR3 operation.
- Linux runtime DDR frequency remains unmeasured. The nominal difference is
  about 15%, insufficient by itself to explain the claimed FPS gap.
- Android's DMC driver is unbound. Do not force-bind it or change firmware/clock
  registers without identifying the cause and a supported operating point.

## Local evidence

Ignored artifacts are under `.tmp/real-duke-{auto,indexed-reuse,fast-read}/`.
Each includes screenshots, samples, counters, guest-clock samples, and matching
unstripped symbols. The blocking profile is `.tmp/duke-offcpu.perf.data` with
`.tmp/duke-offcpu-summary.json`. Always match the ELF Build ID to the capture;
address offsets used by the read-only guest-clock helper change between builds.
The performance worktree is `.tmp/staging-migration` on `perf/duke3d`.
Unrelated controller/catalog work in the main checkout must be preserved.
