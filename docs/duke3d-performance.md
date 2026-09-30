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
| Indexed reuse with Cortex-A55, O3, frame-pointer omission and ThinLTO | 3.57 | 0.959 |

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
- Cortex-A55/O3/ThinLTO build matching the reference optimization choices:
  no demonstrated game-level improvement over 3.53 submissions/s. Build
  settings were restored; do not ship a higher ARM ISA requirement based on
  this inconclusive result.

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
Android uses optimized `-O2` with assertions disabled. The controlled A55/O3/
ThinLTO comparison used the same game, auto cycles, and indexed reuse; it did
not explain the performance gap. It still uses Android's Clang and libraries,
so it is not equivalent to running the Linux executable. There is no measured
Linux baseline yet. Obtain one before claiming the discrepancy is explained.

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

## Archived XFCE installation

The user supplied <https://archive.org/details/rgds-xfce-0.3>, while warning
that later revisions may differ. The ZIP contains a 63,279,464,448-byte disk
image. Its length and ZIP CRC were verified during local extraction; no SD
card was flashed or changed.

The actual archived files provide a more specific reference than current
GitHub source:

- `/usr/local/bin/dosbox` is AArch64 Staging `0.83.0-alpha (6f2f5)`.
- Its IBM-PC launcher selects `svga_s3`, loads `ibmpc.conf`, and makes the
  window fullscreen through the window manager. That configuration specifies
  `core=dynamic` and fixed `cycles=30000`.
- `/home/trixie/dosbox/DUKE3D/DUKE3D/DUKE3D.CFG` specifies ScreenMode 2,
  ScreenWidth 320, ScreenHeight 200. Its SHA-256 is
  `42d30fdfed7098a1606272f280687bae6f0c688f33cdc6293814fea3b851a424`.
- DEMO1.DMO and DEMO2.DMO match the current archive exactly. DUKE3D.EXE has
  the same length but differs in 124 bytes. Neither executable was patched.

1024x768 contains 12.288 times as many pixels as 320x200. This is a substantial
workload difference, but the archived configuration does not prove the settings
used in the user's later, unarchived revisions. The user authorized isolated
320x200 and 640x480 benchmarks while retaining the installed game's 1024x768
configuration. These comparisons use copies of the current writable session,
changing only ScreenMode/ScreenWidth/ScreenHeight and preserving auto cycles,
the current executable, audio, and other game settings.

## Local evidence

### Low-resolution comparisons and CPU doubling

Using the same 60-second warmup and 120-second demo capture, the isolated
320x200 session produced 16.50 surface submissions/s; 640x480 produced 7.97/s.
Guest clock ratios were approximately 0.999 and 0.987 respectively. The user
subsequently requested playing at 320x200; the installed CFG was backed up
locally as `.tmp/duke-before-play-320.cfg`, then only its three display fields
were changed to ScreenMode 2, ScreenWidth 320, ScreenHeight 200.

The renderer investigation confirmed a redundant expansion: `output=texture`
enables Staging pixel/scan doubling. The native video-size getters reported
640x400 for a 320x200 game. `output=texturenb` disables that doubling and
produced an actual 320x200 buffer. The Android wrapper still converts colour
order and copies rows at source size; ANativeWindow/SurfaceFlinger handles
display enlargement. No game executable changes or GLES rewrite were needed.

The isolated no-doubling run produced 20.33 submissions/s, median interval
33.31 ms, p95 133.11 ms, with guest clock ratio 0.9985. CPU frequency remained
1992 MHz and DDR 920 MHz, with no active cooling state. This is approximately
23% higher submission throughput in one comparison, not proof of a constant
game FPS or a 60 FPS result. Native-size output is retained in the launch
adapter. Evidence: `.tmp/real-duke-320-single/`; the previous captures are
`.tmp/real-duke-320/` and `.tmp/real-duke-640/`.

The Duke default controller preset now uses its existing keyboard controls,
matched by catalog content identity and preserving explicit per-game overrides.
The shared keyboard makes modifiers momentary on tap and latched on long press
(Caps retains its lock behavior). Android instrumentation verifies Shift/Ctrl
tap, long press, unlatching, cancellation, and controller JSON round trips.

Ignored artifacts are under `.tmp/real-duke-{auto,indexed-reuse,fast-read}/`.
Each includes screenshots, samples, counters, guest-clock samples, and matching
unstripped symbols. The blocking profile is `.tmp/duke-offcpu.perf.data` with
`.tmp/duke-offcpu-summary.json`. Always match the ELF Build ID to the capture;
address offsets used by the read-only guest-clock helper change between builds.
The performance worktree is `.tmp/staging-migration` on `perf/duke3d`.
Unrelated controller/catalog work in the main checkout must be preserved.

## General performance follow-up: current 320x200 app

The user clarified that Duke is a benchmark for general DOSBox/configuration/
integration efficiency, not a target for game-specific shortcuts. Do not trade
other games' correctness for Duke throughput. The installed 320x200 choice
supersedes the initial 1024x768-only comparison requirement above.

Measured the ordinary installed app, including audio and the secondary keyboard,
with 60 seconds of warmup and 120 seconds of capture. CPU sampling includes
blocking stacks, hardware counters, clocks, thermals, and scene screenshots.
Symbols matched the installed emulator Build ID
`9982f89784e2630b949c1f7c06d336c7220185d7`.

- Baseline: 19.53 surface submissions/s, median interval 33.34 ms,
  p95 133.20 ms. These are not independently counted unique game frames.
- `/proc` CPU accounting: emulation 82.61% of one core, mixer 24.30%,
  presenter 2.18%, audio delivery 1.48%. The four-core device is not globally
  CPU-saturated; the serial emulation path is the major consumer.
- Call-chain attribution over the capture: approximately 82.24 sampled CPU
  seconds in guest execution/helpers, 5.00 in VGA scanout/scaling, 5.95 in FM
  audio on the emulation thread, and 10.02 elsewhere in emulation/events.
  Sampling estimates differ from `/proc` totals and include sampling overhead.
- Major emulation blocking stacks: 10.06 seconds of cycle-pacing sleep and
  3.17 seconds waiting for the OPL port-write mutex. Neither is evidence by
  itself that sleeping or synchronization should be removed.
- CPU stayed at 1992 MHz, DDR at 920 MHz, with no active cooling state.
  Hardware counters reported about 1.83 GHz effective CPU clock, consistent
  with earlier runs. No fresh evidence of thermal throttling was found.

### Discarded VGA reuse experiment

The earlier indexed-row optimization covers VESA part drawing, while mode 13h
still performs palette expansion in its scanline path. That conversion was a
measured cost, so an isolated experiment extended the existing guarded history
check to contiguous, enabled VGA rows. Palette, dimensions, previous-frame
history and renderer-cache state remained required; scanline timing was not
changed. The existing scalar-reference test passed (351 reused rows).

The equivalent demo run measured 19.93 submissions/s, p95 133.12 ms, emulation
82.36% and presenter 2.22% CPU. The roughly 2.1% throughput difference is too
small to establish a reliable gain given scene alignment and run variation.
The experiment was rejected, its source reverted, and the original APK restored
on RGDS. It was not shipped or deployed to Retroid. Cross-game compatibility
was not established for this discarded patch; cache unit checks alone would
not have justified shipping it.

The next evidence gap is within guest execution (including memory and lazy-flag
helpers), not a demonstrated expensive Android presenter. OPL generation and
resampling also merit investigation, but the dependency recipe already enables
ARM NEON: do not assume a missing SIMD build flag without checking the binary.
Any retained general optimization needs equivalent-workload comparisons and
regression coverage appropriate to the changed subsystem. Do not lower audio
accuracy, skip guest timing events, patch games, or change game-specific cycles
to turn this benchmark into an apparent success.

Compact results: `docs/benchmarks/rgds-current-320-profile-20260930.json`.
Full ignored evidence: `.tmp/duke-current-320-profile/` and
`.tmp/duke-vga-cache-profile/`, including the discarded patch and matched symbols.
## Ticket #55: guest-address hotspot attribution (September 30)

Completed a valid 60-second warmup / 120-second rendered-demo capture on RGDS at 320x200, `core=dynamic`, `cycles=auto`, `output=texturenb`. The optional Debug-only translation recorder and `tools/analyze_guest_profile.py` resolve sampled generated-code addresses using translation/invalidation timestamps. Linked blocks are covered without counting only outer dynrec entries. Default builds compile out the hooks; guest code and memory reads are unchanged.

Evidence: [sanitized measurements](benchmarks/ticket55-guest-hotspots.json), [procedure and limitations](guest-profiling.md). Raw perf/translation data and screenshots remain in ignored `.tmp/ticket55-guest-profile-run3`; the same-APK logging-disabled comparison is `.tmp/ticket55-profile-disabled`. Matching installed APK SHA-256 and core Build ID are recorded in the evidence. Four mapping tests pass, including invalidation/address reuse, linked blocks/helper attribution, ambiguous mappings, and interval boundaries.

Of 103.30 sampled emulation CPU seconds, 40.58 mapped directly to generated guest blocks and 21.26 through their nearest mapped helper caller. There were 1,841 attributed opcode/address block forms. Only 0.61 seconds remained symbolized as `unknown` without a guest mapping; other unattributed work has host symbols but lacks a usable guest caller. Counts represent estimated CPU time, not executed instructions.

The largest combined block, guest `0x29f7c0`, accounts for 5.76 seconds, of which 5.73 are helper work dominated by OPL synthesis. It is not evidence that this block itself burns 5.76 seconds in a tight guest loop. Next is `0x2b5613` at 2.30 seconds, with arithmetic/flag helpers. Preserve this distinction when investigating polling in #56.

| Same measurement APK | Logging enabled | Logging disabled |
| --- | ---: | ---: |
| Display submissions/s | 18.287 | 18.928 |
| Emulation thread, % of one CPU | 82.52 | 82.66 |
| Median submission interval | 33.33 ms | 33.31 ms |
| p95 submission interval | 149.93 ms | 133.31 ms |

The enabled run had 3.39% fewer display submissions. This single pair measures the observed difference, not logging overhead independently of run-to-run variation. Both retained maximum observed CPU/RAM clocks (1.992 GHz / 920 MHz) with no active cooling-device throttling. Logging is suitable for locating hotspots, not claiming small speedups; validate later changes without it. Earlier normal-build cadence was 19.53 submissions/s and also differs in code layout and capture conditions.

Two preliminary captures were invalid because the device slept. The second sleep was explicitly recorded as `power_button`, not timeout; those runs were excluded. Both accepted runs checked wakefulness throughout and preserved rendered start/intermediate/end screenshots. The marker was removed and the exact normal APK restored afterward, verified by SHA-256. No runtime optimization is adopted by this ticket. Next: #56 must inspect candidate loops' state and exit conditions before labeling any workload as polling.

## Ticket #56: polling classification (September 30)

Used the accepted #55 capture, expanded to all 1,841 attributed block forms, and a read-only 4 MiB snapshot of the normal app's guest RAM. All recorded opcode starts and instruction boundaries matched. Capstone 5.0.7 screened 113 sampled 32-bit backward edges shorter than 512 bytes, then 143 shorter than 4 KiB. Candidate spans overlap; the screening tool does not label them idle automatically. Three inspector tests and the four mapping tests pass.

Evidence: [polling analysis](benchmarks/ticket56-polling-analysis.json). Snapshot, disassembly and complete block forms remain local under `.tmp/ticket56`; raw artifact hashes are recorded. No game/core settings or production code changed.

| Inspected region | State and exit condition | Associated sampled CPU time |
| --- | --- | ---: |
| `0x2647fb`, `0x264805` | Reads VGA `0x3da` bit 0; waits first while set, then while clear | 0.091 s (0.088% of sampled emulation CPU) |
| `0x29f7d8`, `0x29f7ee` | Reads OPL port but exits on EBX counter, initially six or 27 | 0.465 s (0.450%); fixed I/O delay, not status polling |
| `0x294a44..0x294ab5` | Saturates/writes sample values, advances output pointer, decrements count | 3.111 s; productive work |
| `0x2b5613..0x2b5684` | Indexed reads, arithmetic state updates, packed writes, 320-byte destination steps | 2.303 s; productive work |
| `0x2b5160..0x2b51f4` | Output writes, coordinate changes, finite output count | 1.576 s; productive work |

The VGA loops sit among palette writes. `vga_read_p3da` derives bit 0 from emulated horizontal/vertical blanking and resets attribute-controller/PCjr flip-flops. The OPL loops discard read results; `Opl::PortRead` nevertheless consumes emulated cycles, changes I/O-delay bookkeeping and updates timers through `OplChip::Read`. These are not side-effect-free RAM wait loops. Array searches and linked-list traversals among other no-store candidates also change their index/node and terminate on a match or exhaustion.

The isolated loop-block costs omit first iterations embedded in mixed setup/write blocks. Adding the VGA port-setup block changes its associated cost to 0.101 s. Do not count the 6.091 s in the mixed OPL entry/write blocks as polling: their time includes the already-identified synthesis work. Snapshot operand-only changes are not excluded, and 41.46 sampled CPU seconds lack guest caller attribution. The 82 mapped 16-bit forms (2.97 s), unsampled code and larger call-spanning waits are outside this branch screen. Consequently these are measured costs for identified loops, **not an exact whole-emulator polling percentage**.

Decision: the dominant inspected candidates do not establish a large RAM-polling bottleneck like the historical PC-98 case. No idle skipping is justified. Complete the separate correctness assessment in #57, then continue the remaining cost investigations. This conclusion does not imply that polling acceleration can never help another DOS workload.

## Ticket #57: idle-acceleration decision (September 30)

[Source-reviewed assessment and differential test requirements](idle-acceleration-assessment.md): no-go for the measured candidates. VGA polling has controller side effects; OPL counter delays change emulated timing. No material pure-RAM wait loop has been established. The assessment covers memory/code changes, IRQ/event ordering, DMA and residual Staging dynrec cycles, including the PC-98 residual-cycle correction as a reference. Future test requirements are explicitly not claimed as executed tests. No runtime change or new benchmark was necessary for this design decision; continue #58.
