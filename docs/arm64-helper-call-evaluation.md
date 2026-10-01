# ARM64 helper-call investigation

Status: rejected; production core source restored. The isolated build improved
repeatedly, but the matching current-tree pair did not reproduce that improvement.

## Question and measurements

Investigate low-level generated-code and memory-helper overhead using the RGDS
and the existing Duke3D 320x200 rendered demo, with sound enabled. Game files,
cycle settings, rendering settings, clocks, and Kairo98 remain unchanged.

The installed baseline APK is SHA-256
`5400302d81f89dac4fe1b33ef45a2434ecfd3c5a530f36ba02264fb1e1336ad0`.
Two separate 120-second hardware-counter captures target only the original
emulation thread (29682), not the mixer or presenter. Events are grouped and
restricted to userspace; raw per-CPU scheduling times are retained. These are
ongoing-demo diagnostic measurements, not fresh-launch performance comparisons.

| Measurement | Data/branch capture | Instruction-cache capture |
| --- | ---: | ---: |
| CPU cycles | 185,543,181,108 | 186,836,428,672 |
| Instructions | 113,753,479,722 | 107,744,630,313 |
| Cycles/instruction | 1.6311 | 1.7341 |
| L1 data-cache accesses / misses | 30,808,747,846 / 333,710,649 | — |
| Branches / misses | 13,257,711,076 / 773,667,807 | — |
| L1 instruction-cache accesses / misses | — | 76,781,164,184 / 590,277,676 |
| Instruction / data TLB misses | — | 279,545,597 / 1,081,829,788 |

The reported data-cache miss rate is 1.083%; branch miss rate is 5.836%.
These rates do not prove a bandwidth bottleneck, identify a particular branch,
or measure the latency of a miss. Hardware event semantics differ; in particular,
TLB misses are not synonymous with page faults or DRAM accesses. Different demo
phases prevent treating these two captures as identical workloads.

A separate 119.935-second branch-miss profile has 8,779 samples and zero reported
lost samples. Of the sample periods, 52.80% fall in the anonymous executable
region associated with the generated-code cache. Named hotspots include the
checked dword-read helper (5.80%), CPU dispatcher (3.44%), checked dword-write
helper (3.37%), byte-read helper (3.09%), and word-read helper (2.78%). Samples
often land on non-branch instructions: hardware sampling skid means these are
hot regions, not proof that the sampled instruction caused the miss.

## Isolated candidate

The ARM64 raw helper-call emitter always materializes a 64-bit target using
MOVZ plus three MOVKs, then BLR: five instructions, 20 bytes per call site.
The corrected candidate uses ADRP + ADD + BLR for checked-memory calls when the target page is representable by
ADRP, reducing those sites to three instructions, 12 bytes. Out-of-range targets
keep the original sequence. This reduces address-construction work and code
footprint; it does not eliminate the indirect branch or the memory helper.

All other calls retain the original emitter. Arithmetic call sites used by lazy-flags
patching retain the original five-word layout. Changing their size would let later
patching overwrite adjacent code.
Memory checks, fault paths, device handlers, writes, and guest instruction
semantics are unchanged.

The isolated candidate APK differs from the baseline in exactly one ZIP payload:
`lib/arm64-v8a/libdosbox_staging.so`. A standalone probe extracted from the actual
candidate emitter passed 156 checks on RGDS: signed ADRP range boundaries,
fallback target reconstruction, native compact/fixed calls, return values, and
all 16 NZCV combinations. This is preliminary emitter validation, not full-game
compatibility evidence.

The first prototype changed the general raw-call emitter, while keeping only
`gen_call_function_setup` fixed-size. It failed the rendered-scene gate with a
black DOS screen; its capture is excluded. `InvalidateFlags` and
`InvalidateFlagsPartially` also queue raw arithmetic calls for later replacement.
The corrected patch changes only the six checked-memory helper targets (seven
call sites), leaving the raw emitter and all arithmetic paths byte-for-byte
unchanged in source. Both versions' probe results alone cannot establish this
integration property; the failed full-app test is retained as evidence.

## Performance gate

Use fresh direct launches, verify the sound prompt, then run the existing
60-second warmup / 120-second rendered-demo capture. Compare baseline and
candidate with the same light observer; bracket candidate runs with baseline
runs. Surface submissions are not unique guest FPS. Inspect screenshots, frame
history completeness, audio underruns, clocks and thermal observations.

Do not adopt based on fewer emitted instructions alone. A reproducible improvement
and representative game/lifecycle checks are required. Rejected experiments must
restore the exact baseline APK and isolated source.

Raw captures, APK identities and candidate source are under `.tmp/lowlevel/`.

### Repeated results

| Order | Build | Surface submissions/s |
| --- | --- | ---: |
| A1 | Baseline | 32.8239 |
| B1 | Corrected memory-only candidate | 34.5503 |
| A2 | Restored baseline | 33.0384 |
| B2 | Same corrected candidate | 34.4931 |

Means: **32.9311 → 34.5217 (+4.8300%)**. Both candidate runs exceed both
baselines. Actual warmups were 60.000–60.172 seconds; all four frame histories
were complete and all AudioFlinger underrun deltas were zero. Endpoint screenshots
show rendered Duke3D scenes. The failed general-call prototype is excluded.

This is a repeated end-to-end improvement for this workload, not a universal FPS
promise or an exact attribution of every saved cycle. Compiler layout changes
accompany the emitter change. SoC temperatures differed across runs, and sparse
clock/thermal observations do not establish constant effective clocks. Two runs
per condition and varying demo phases limit precision. Machine-readable results
and raw hashes are in [the comparison](benchmarks/arm64-helper-call-comparison.json).

## Reproducing the emitter probe

Apply [the isolated patch](benchmarks/arm64-compact-calls-prototype.patch) to the
pre-experiment core in a separate build tree, then generate the test from that
tree's actual instruction definitions and emitter:

```powershell
python tools/generate_arm64_compact_call_probe.py <patched-tree> <probe.cpp>
aarch64-linux-android26-clang++.cmd -std=c++17 -O2 -static-libstdc++ <probe.cpp> -o <probe>
adb -s <serial> push <probe> /data/local/tmp/kairo-call-probe
adb -s <serial> shell 'chmod 700 /data/local/tmp/kairo-call-probe; /data/local/tmp/kairo-call-probe'
```

The corrected candidate hash is
`e6a7c0972867276c9f0d5e8d61236170ef7dce85cf486eb36efe18d518fd36ad`.
A read-only snapshot of its live code cache decoded 43,422 compact call sequences
targeting the six checked-memory helpers. These are static sites, including stale
cache contents, not dynamic execution counts. The snapshot and actual core's
`cache_code` pointer establish that the candidate path is used by the running
game, beyond the standalone probe. Raw generated code stays in ignored storage.

## Runtime gates

The corrected candidate passed the existing core fixture with Doom and Cannon
Fodder. Both produced video and nonzero PCM, executed generated ARM64 code,
survived pause/resume, surface recreation, reset, two sessions in one process,
and stopping while paused. Both released JIT mappings at teardown. Screenshots
show Doom gameplay and Cannon's animated opening, not Cannon gameplay.

The current-checkout APK was then built normally, including the already committed
frontend/controller changes. Quake reached its rendered 3D demo via direct launch,
original single-player selection, and Escape at optional joystick calibration.
Its native binaries differ from the isolated candidate: the host's inspected
code/data sections match, while the core has different constant layout/references.
The final APK therefore receives its own Duke verification capture; its rate is
reported separately from the isolated A/B comparison.

An initial current-tree capture reported 32.8954 submissions/s, but concurrent
frontend work changed the shared build output before it was frozen, and the
installed APK hash was not captured for that run. It is excluded from matched
performance decisions. The follow-up reference and candidate are frozen copies,
verified by installed SHA-256, and differ in exactly the core library payload.
Their emitted memory-call forms are checked using each packaged ELF's own exports
and a live executable-cache snapshot, without relying on mismatched debug symbols.

### Current-tree decision

| Frozen APK | Surface submissions/s |
| --- | ---: |
| Reference, original memory calls | 34.0090 |
| Candidate, compact memory calls | 33.4641 |

The candidate was **1.60% lower** in this pair. One pair is not a precise estimate
of regression, but it fails to reproduce the isolated build's benefit. Do not
publish the earlier +4.83% as a shipped speedup. Both current-tree runs rendered
the demo, retained complete presentation histories and had zero recorded audio
underrun increments. Live-cache inspection found zero compact helper-call sites
in the reference and 43,267 in the candidate: the missing gain is not explained
by failure to execute the experimental emitter.

The two production source files are restored to their original contents. The
patch remains only as a research artifact. The frozen reference APK retains the
current frontend while restoring the original core call sequence. Its SHA-256 is
`c781b38dc3c8227b5491dfeaeb928bf990bbccf6377702585dd723d6ad2200f6`.

The investigation establishes hot generated-code/memory-helper regions and
substantial branch activity, but does not yet resolve the cycles spent within
individual generated operations. The next useful attribution is the dependency
chain around checked reads (TLB lookup, helper call, scratch-result store/reload),
including sampled generated instructions. Neither aggregate miss counts nor this
rejected address-construction change establish that restructuring the TLB or
inlining memory accesses will improve performance.

[Runtime evidence](benchmarks/arm64-helper-call-runtime.json) records APK and
fixture identities. These are representative checks, not a universal game
compatibility claim, an acoustic-quality measurement, or a release license audit.
