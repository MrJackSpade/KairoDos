# PGO experiment plan (#70)

Status: training frozen; candidate evaluation pending. No PGO runtime
optimization has been adopted.
The normal baseline is the OPL-enabled APK from `fda400fe` with shared
`9be507e`; subsequent main commits through `8df2efd9` add audit artifacts.
LTO remains off, and `-O2`, CPU target and all normal runtime settings remain
constant. Unrelated workspace edits are excluded by the isolated build.

## Preselected workloads

Training, each in a fresh application process and its existing configuration:

- DOOM (1993): protected-mode integer/VGA workload; existing Gravis sound.
- Commander Keen 4: real-mode 2D/EGA and FM workload.
- Quake: protected-mode 3D/FPU workload.

Held out of the merged training data:

- Duke Nukem 3D: the main existing performance benchmark, 320x200.
- Duke Nukem II: a separate 2D workload.

This is diverse coverage, not a claim to represent every DOS machine or game.
No profile chosen from held-out performance results may be merged back into
training. Training uses full-session host-code counts, including startup and
shutdown; no proprietary game data, game executables or writable drives may
be published. Local profiles and any published provenance must describe the
exact sessions and weighting. Initial merge policy is equal input weight,
with comparable observed training duration per game.

## Instrumentation

The initial isolated native build added `-fprofile-generate -fprofile-update=atomic`
and the matching link flag. This instruments the source-built native core,
SDL and bundled libraries; pinned prebuilt dependencies remain unchanged.
A generation-only lifetime guard writes a per-process profile under the
app's existing per-game configuration directory on clean core exit. The
normal APK is retained for restoration between experiment batches.

The NDK 28.2.13676358/Clang 19 profile interface header says the runtime borrows
the filename pointer. The guard keeps its string alive through
`__llvm_profile_dump()`, then resets the filename before destroying the string.
Dump suppresses another automatic exit write. The entry wrapper itself is
excluded from instrumentation because its generation-only lifetime guard
intentionally changes its control flow. Other native functions remain covered.

Atomic counter updates were initially selected to avoid inaccurate counts under
thread contention, but were rejected after the measured overhead below.
Training speed is not candidate performance.
This follows the [Clang PGO interface and counter documentation](https://clang.llvm.org/docs/UsersManual.html#profiling-with-instrumentation).
The profile-use candidate must contain no generation counters or dump hooks.
Record and inspect any missing/outdated profile diagnostics instead of globally
suppressing them. Keep symbols and verify packaged/debug core identities.

## Kairo98 finding surfaced to the user

Current `Kairo98/kairo98/src/main/cpp/native_bridge.cpp` lines 277-281 pass a
block-local `std::string::c_str()` to `__llvm_profile_set_filename`, then leave
that block. Later calls at line 621 write the profile. This violates the NDK
header's documented borrowed-pointer lifetime and could make the optional
PGO-generation path unreliable. Normal builds do not use that guarded path.
The issue was surfaced to the user; Kairo98 was not modified. No claim is made
that this audit reproduced a crash or checked that runtime's implementation
beyond its public lifetime contract.

## Rejected atomic-counter attempt

The first Doom attempt remained in startup at both timed screenshots. A
10-second simpleperf trace recorded 1,082 samples with zero lost samples.
Of 10.152 sampled CPU seconds on the emulation thread, 8.687 seconds were
inclusive in `draw_text_line_from_dac_palette` (8.566 leaf), with another
0.677 leaf seconds attributed to `__aarch64_ldadd8_relax`. This identifies
instrumented text rendering as a dominant observer cost; it is not evidence
for a normal-build rendering optimization. The run eventually reached the
title screen, and exited/dumped successfully, but is quarantined and must
not be merged into the training profile.

The revised training build uses Clang's default non-atomic counter updates.
Counts in shared functions called concurrently may be approximate. This is
a profile-quality limitation, not a change to normal emulator settings or
an exact execution trace. The profile-use candidate must be evaluated on
held-out workloads with generation instrumentation removed.

## Accepted training sessions

The revised APK is `2bf2ddc50c753b4ee4e08b48a48b2f5cf4cc175cc01312bb98e87bb507310f76`.
All 433 compilation commands were checked for generation instrumentation,
absence of atomic updates and absence of LTO. Raw profiles remain ignored
local artifacts; their hashes and capture provenance are recorded separately.
Comparison against the preserved baseline compile database also confirms all
other command tokens and source/build paths match. Only the emulator shared
library differs between APK payloads outside signing metadata.

- Doom process 27936: native session 10:03:08–10:08:22 UTC on October 1,
  2026. Both timed screenshots show the rendered demo. Clean dump returned 0;
  LLVM read the profile successfully. Existing Gravis configuration retained.
- Keen process 29150: native session 10:09:13–10:14:24 UTC. The first timed
  screenshot shows the platforming demo; the last shows a demo transition
  panel. A short menu/keyboard interaction occurred after the timed capture
  while exiting (its wrapped title moves the app Exit row). No game settings
  were changed. Clean dump returned 0; LLVM read the profile successfully.
- Quake's initial timed window encountered its joystick-calibration prompt
  and is invalid as a gameplay capture. Escape skipped calibration without
  editing game settings. A replacement full window began only after a
  screenshot verified the rendered demo. Both replacement endpoints show 3D
  gameplay. Process 30362 ran from 10:16:24 to 10:25:28 UTC and dumped with
  result 0. Its full-session profile includes that startup/calibration wait
  and extra demo time; this is a profile-quality limitation and means the
  three profiles are not equal-duration full-session samples.

These are training observations, not optimization results. For example, the
instrumented Doom capture recorded an AudioFlinger underrun-field delta of
640. That does not establish a normal-build regression or emulated sample
correctness. Instrumentation overhead must be assessed separately with the
same game configuration; previous Doom Sound Blaster tests cannot substitute
for this session's Gravis configuration.

The three explicit raw paths were merged with equal input weights and without
`--sparse`. LLVM reports 13,861 functions. Frozen merged profile SHA-256:
`2fdbb662e38ec471be067947c87a5c5572d3307c0be2cac2048be3a8a2f4fa2c`.
The rejected atomic profile and both held-out games are excluded. Build,
profile and capture hashes are in [training provenance](benchmarks/ticket70-training.json).
The [generation-only patch](benchmarks/ticket70-generation.patch) is retained
for reproducibility; it is not applied to the production source tree.
The patch uses zero context (`git apply --unidiff-zero`) and passed an apply
check against the baseline source. After this batch, the normal APK was
restored over Wi-Fi and its installed SHA-256 verified as
`c4e58b4f12d9a8414d91bf97ee474ad7dc036e0d4de38761c089e0b5e7e62a39`.

Next: build an isolated profile-use candidate from the same source paths,
remove generation hooks/counters, inspect profile mismatch diagnostics, and
compare held-out workloads against the unchanged baseline with LTO off.
Keep the frozen training input independent of those results. Measure training
overhead with the matching Doom/Gravis configuration. Ticket #70 remains open.
