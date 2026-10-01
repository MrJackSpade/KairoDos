# PGO adoption (#75)

Status: compiler and runtime gates passed; PGO is enabled by default for both
distribution channels and deployed to RGDS. This is an optimization adoption,
not a release-readiness claim.

## Portable frozen data

The measured profile from #70 is now represented by
`backend-dos/pgo/arm64-v1.proftext` and its manifest. Its 13,861 function records
retain the original CFG hashes, counters and value profiles. Only 3,332 literal
`M:/` occurrences became a canonical checkout marker. The checked-in artifact
uses LF with one final newline so Git cannot invalidate the manifest hash.
An indexed round-trip after newline normalization reproduced the original
LLVM text export exactly. LLVM reconstructs the
indexed profile using the current root, including consistent indirect-target
GUIDs. Exact export/reimport checks passed at original and relocated roots.

## Actual compiler matching

The CPU and VGA-drawing translation units were compiled as optimized LLVM IR
at `M:/` and `N:/`, using the same frozen counts. With relocated profiles,
their IR is identical after normalizing the expected paths and MD5-derived
indirect-call target IDs. A deliberately unrelocated profile at `N:/` loses
metadata:

| Unit | Correct entry-count / branch-weight records | Wrong-root records |
| --- | ---: | ---: |
| CPU | 57 / 174 | 55 / 168 |
| VGA draw | 15 / 91 | 9 / 38 |

An initial probe used the compilation database's backslash source argument,
which differs from Ninja's forward-slash command on Windows. That probe did
not test the actual static-function matching and was rejected. Corrected
probes use the source spelling verified with `ninja -t commands`.

## Full relocated build

The isolated frontend/core source from `fda400fe`, shared `9be507e`, plus the
opt-in integration built under `N:/` in 4m39s. All 433 native compile commands
contain profile use, no generation instrumentation and no LTO. Stale-function
profiles are errors; the build succeeded without them. The APK is
`4f894466208c6e4109a3e37d5cb677ca80709eae5d3e91c597d51fb4dac731aa`.

Relative to the measured #70 candidate, only the core and host shared-library
payloads changed outside signing metadata. The host `.text` is byte-identical.
Core `.text` remains 5,956,324 bytes: all 1,489,076 instruction addresses and
mnemonics match. Operand differences comprise 20,138 `add` and 2,999 `adrp`
instructions. This supports relocation equivalence but does not independently
prove that every relocated data target is correct; device regression gates
are still required. Do not describe the complete core as byte-identical.

## Build guard checks

The final helper uses transactional indexed-file creation. A standalone CMake
fixture exercised successful generation, unchanged-cache reuse, rejection of
diagnostic core settings, and rejection of a modified text profile even when
an older indexed cache exists. The APK above predates this cache-only
transactional adjustment; the final helper was separately exercised afterward.
The APK also predates text-newline canonicalization, whose lossless LLVM
round-trip and final-artifact configure guards were verified separately.

The profile/compiler identity checks apply only when PGO is requested. The
normal comparator remains available with `-Ppgo=false`. Source and checkout
hashes are included in the generated profile's flag path to invalidate object
builds when those inputs change. Unchanged profiles are not regenerated.

Evidence: [portability checkpoint](benchmarks/ticket75-portability.json).
Implementation notes: [profile README](../backend-dos/pgo/README.md).

## Final artifact and device checkpoint

The final LF-canonicalized profile and transactional helper were rebuilt. The
APK SHA-256 remains `4f894466208c6e4109a3e37d5cb677ca80709eae5d3e91c597d51fb4dac731aa`.
The compiler database names the final profile hash, and
`tools/check_pgo_profile_matching.py` verifies the expected static-function
counts in actual optimized CPU/video IR. Both wrong-root controls lose the
expected counts. Commit `4cc5c7c8` adds this check after an opt-in Linux CI build;
the first CI build succeeded but verification found AGP's duplicate IDE
database. Commit `a65f3ca3` excludes that convenience copy from discovery.
The duplicate-layout case passed locally, and Linux run
[36865823665](https://github.com/MrJackSpade/KairoDos/actions/runs/36865823665)
then passed both builds and both actual CPU/video matching checks, including
the wrong-root negative controls. No matching requirement was weakened.

The APK was installed over the existing package on RGDS over Wi-Fi and its
installed SHA-256 was verified. Existing isolated native fixtures passed:

| Fixture | Observed result |
| --- | --- |
| Doom | 926 frames, 3,495,936 PCM frames, peak 8,589; screenshot shows gameplay |
| Cannon Fodder | 812 frames, 1,880,064 PCM frames, peak 8,833; screenshot shows intro only |
| GLES | Shared contexts, fenced frames, orientation and display lifecycle passed |
| Input | Shared input routing and lifecycle passed |

Both game fixtures verified actual ARM64 dynarec execution on default launch,
reset and second launch; pause/resume, surface recreation, exit while paused,
and release of executable JIT mappings passed. These are correctness checks,
not performance samples. Cannon Fodder's intro does not establish gameplay
coverage, and nonzero PCM does not establish perceptual audio correctness.
The fixture uses private cache copies and leaves player drives untouched.

The first final-artifact Duke II capture used 60 seconds warmup and 120 seconds
capture in the verified opening room. It recorded 10.7823 surface submissions/s,
41.0135% process CPU, complete presentation history, zero audio-underrun-field
increase and no observed cooling intervention. These are one candidate sample,
not a completed comparison or a benefit claim. Six subsequent held Right inputs
moved the player and scrolled into the adjacent area. ADB then disconnected
during exit, after all measurement artifacts had been retrieved. The paired
baseline and remaining gameplay gates are pending device recovery.
See [the capture checkpoint](benchmarks/ticket75-duke2-checkpoint.json).

## Final portable Duke 3D comparison

The final APK was measured in B-A-A-B order on RGDS over Wi-Fi, with 60 seconds
warmup and 120 seconds capture per run. All eight endpoint screenshots show
rendered 3D gameplay. Existing launch/game configuration hashes match #70
before and after the sequence. Sound stayed enabled and resolution stayed
320x200.

| Run | Build | Surface submissions/s | Process CPU % |
| --- | --- | ---: | ---: |
| B1 | Portable PGO | 32.8062 | 121.0130 |
| A1 | Normal | 30.8873 | 120.5806 |
| A2 | Normal | 30.6174 | 120.4405 |
| B2 | Portable PGO | 31.8079 | 119.4307 |

Means are 30.7523 normal and 32.3070 PGO, an observed +5.0556%. Both candidate
samples exceed both baselines. All histories are complete; all recorded
audio-underrun deltas and cooling states are zero. SoC readings increased
from 50.625–54.375 C in B1 to 62.777–64.444 C in B2. This thermal drift and
two samples per build limit inference; the result is not a guaranteed fixed
gain or unique guest FPS. It supports benefit in the tested workload without
reusing #70's different binary as the final implementation measurement.

Full data: [portable Duke comparison](benchmarks/ticket75-duke-comparison.json).

## Additional gameplay check

The same final PGO APK launched original single-player Quake (launcher choices
1, then 1; joystick calibration skipped with Escape). The rendered demo ran,
then menu input started a new game. Before/after screenshots show forward
movement in the introductory map. The existing game selected VESA 1024x768;
no resolution or game-content edits were made for this check. The app flyout
exited normally; the retained log shows CD-DA, speaker, OPL, SB16, MPU-401,
capture, DMA and logging shutdown, with no fatal exception in the inspected
window. This is a 3D/FPU gameplay smoke check, not a timed Quake benchmark or
proof of every floating-point operation. Local evidence is under
`.tmp/ticket75/quake-*` (screenshots and `quake-exit-log.txt`).

## Secondary comparison and decision

The final portable APK's Duke II opening-room sample was compared with the
normal APK using the same configuration, verified scene, 60-second warmup
and 120-second capture. Baseline delivery was 10.7762 submissions/s versus
10.7823 PGO (+0.0563%, effectively unchanged). CPU was 44.3833% versus
41.0135%. Both histories are complete, audio-underrun deltas are zero and
observed cooling states are zero. This is one separated pair, not a repeated
performance estimate: the PGO sample was cooler (38.888–40.625 C versus
60–61.666 C), so the lower CPU observation is not an isolated causal gain.
This compatibility result complements the repeated primary comparison and
the earlier #70 secondary A-B-B-A. No further repeated secondary performance
claim is needed to justify adoption based on the primary workload's benefit.
Data: [secondary comparison](benchmarks/ticket75-duke2-comparison.json).

Adopt the frozen profile. The final primary comparison repeats the improvement,
Linux and Windows compiler matching is verified, the native fixtures pass,
and real/protected-mode, 2D/3D/FPU and input/lifecycle checks found no regression
in the covered workloads. These checks do not prove all-game safety.

Both Gradle and CMake default to PGO ON; `-Ppgo=false` retains the comparator.
CI builds that explicit comparator and then builds/checks the default without
a PGO override. Diagnostic/alternative core builds must disable PGO explicitly.
No guest configuration, game files, CPU timing policy, LTO setting, rendering
or audio semantics changed. No Kairo98 changes were made.

## Final build and deployment

Commit `b236c0b4` enables the default. The isolated build used the same clean
frontend/core source as the measured candidate plus the adopted build files,
excluding unrelated catalog/controller workspace edits. Building
`:kairodos:assembleDebug` without `-Ppgo` succeeded in 30 seconds. CMake cache
confirmed PGO/OPL worker ON and guest profiling, OPL verification and stress
OFF. Its APK is byte-for-byte identical to the tested portable candidate:

`4f894466208c6e4109a3e37d5cb677ca80709eae5d3e91c597d51fb4dac731aa`

`InstallRgDs.ps1` updated the existing package over Wi-Fi. Device-side
`base.apk` SHA-256 matched. Duke 3D was launched with its existing sound
selection, and the final screenshot shows the rendered 3D demo, not its
title screen. Local evidence: `.tmp/ticket75/default-build.log`,
`.tmp/ticket75/adopted.apk`, and `adopted-duke-rendered.png` in that directory.
The adopted normal build remains installed. No generation/comparator build
was deployed as the final app. Linux matching was already verified in runs
36865823665 and 36869915589; the default-toggle commit's routine CI is a
separate run, not the evidence for the byte-identical device artifact.

The implementation and deployment requirements for #75 are complete.
