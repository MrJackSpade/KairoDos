# Ticket #62: translated-block dispatch and chaining

Decision: preserve dispatch, linking and invalidation policy. Investigate generic
SETcc translation separately in **#72**, based on measured opcode fallbacks.
No performance optimization is adopted by this ticket.

## Protocol and diagnostic validity

RGDS over Wi-Fi, existing Duke at 320x200, dynamic core, auto cycles, texturenb,
sound enabled. Each new run used a nominal 60-second warmup (with wakefulness
checks) and 120-second simpleperf capture of the rendered title demo. Screenshots
confirmed the 3D scene. Display submissions are not unique game FPS.

The [diagnostic patch](benchmarks/ticket62-dispatch-probe.patch) adds counters
without changing guest instructions, guest block limit, link policy, timing or
invalidation. An ARM64 counter at each translated block entry uses scratch x10/x11,
preserving NZCV. The generated code grows, so cache footprint and execution costs
are perturbed; these counts are not a performance comparison against production.
A generated-code test passed 96,000 checks on RGDS: enabled/disabled, 32/64-bit
carry/wrap, all NZCV combinations and x0/x1/x19 preservation.

The normal interpreter counter captures its existing fetched decode token; it
performs no additional guest memory reads. Tokens include prefixes. The analyzer
checks monotonic counters, cache/link accounting and entry/return consistency,
then restricts snapshots to the emulation thread's perf sample range. Complete
counter snapshots span 120.076949 seconds; the perf recorder reports 119.952
seconds. This small difference reflects sample/snapshot boundary reporting; rates
below use the actual counter timestamps, not a presumed exact 120 seconds.

## Measured transitions

| Counter | Count |
| --- | ---: |
| Generated block entry attempts (before existing cycle check) | 313,000,647 |
| Host runcode invocations | 20,351,912 |
| Internal entry transitions (difference) | 292,648,735 (93.4978%) |
| Host cache lookup hits | 20,216,765 |
| Host cache lookup misses / translations | 48,196 |
| Link attempts / successes | 130,741 / 86,951 |
| Link failures: target not cached / no code flag | 43,788 / 2 |
| Primary blocks cleared by guest writes | 19,110 |
| Primary / cross-page blocks cleared by cache reuse | 28,150 / 135 |

Entry attempts include cycle-deadline exits, not just productive blocks. Link
return codes count unresolved-link stubs, not already-linked transitions.
Clear counts are block operations, not distinct guest writes. Page-release,
other-clear, special-page fallback, SMC return and invalidation-map fallback
counts were zero during this interval. None of this permits removing guest-write
invalidation or timing checks. Existing chaining is demonstrably active.

Host return reasons: normal 15,509,951; cycles 2,099,262; link1 92,798;
link2 37,943; opcode 2,441,832; IRET 160,519; callback 9,607.
The general normal-return category includes indirect control flow, state changes
and block limits; it is not automatically unnecessary work.

## Actual interpreter fallbacks

Of 2,441,832 opcode fallbacks, **1,997,470 (81.8021%) were SETcc**:
SETB 3,596; SETE 420,657; SETNE 128,657; SETL 1,077,526; SETGE 135,370;
SETLE 8,946; SETG 222,718. The decoder cases and old implementation are commented
out in Staging's dynrec. LAHF was not observed in this interval.

Other final tokens: LAR 146,363; group 0F BA 153,735 (suboperation not measured);
BSR 136,489; CMPS 7,629; SCAS 146. Prefix tokens such as 0F, 66, F2/F3 are not
additional instructions. Complete decode tokens are retained in the JSON.
These counts justify a SETcc experiment, not a predicted FPS gain. #72 requires
correct flags, register/memory semantics, faults, SMC handling and an uninstrumented
A/B/A comparison before any adoption. No game-specific changes are warranted.

## Normal-build host cost

The preceding fresh #61 normal build recording, with matching symbols, sampled
102.353534 emulation-thread CPU-seconds. Self costs: dynrec core 2.757576;
MakeCodePage 1.848485; normal core 0.858586; translation 0.262626;
PIC_RunQueue 0.474747 seconds. These are distinct sampled function-body costs,
not all recoverable dispatch overhead; inline work belongs to its caller and
unresolved generated code cannot be attributed to C++ dispatch. Missing standalone
LinkBlocks samples do not prove zero cost. Avoid summing inclusive frames.

## Reproduction

Apply the archived patch only to the recorded base in an isolated checkout and
build Debug with `-PguestProfile=true`; release builds reject profiling. Enable
only `kairo-dispatch-profile.enable` in the writable config directory. The regular
guest/memory/slice markers must remain absent. Capture `dispatch-counts.jsonl`
from `kairo-dispatch-profile.jsonl`, plus matching perf data and emulation TID.

Run `tools/analyze_dispatch_profile.py <directory> --simpleperf-dir <NDK/simpleperf>
--tid <actual tid>`. Generate the native counter test with
`tools/generate_dispatch_counter_probe.py <patched source root> <output.cpp>`,
compile with the NDK ARM64 Android26 clang++ (`-std=c++17 -O2 -static-libstdc++`),
and run on the device. Probe provenance remains the existing audited Staging tree.

The archived patch is diagnostic evidence, not applied production code. Normal
builds must use `-PguestProfile=false`. No Kairo98 source or app was changed.

## Comparison and restoration

| Build/run | Display submissions/s |
| --- | ---: |
| Preceding normal APK (#61) | 17.9626 |
| #62 diagnostic, counters enabled | 18.3611 |
| Same APK, counters disabled | 18.4689 |

Enabled was 0.5834% lower than disabled in this pair. This is an observed pair,
not a statistically isolated overhead estimate; scene progress and compiler/cache
layout vary. There is no optimization gain claim. Both new captures remained
awake and reported zero lost perf samples. Full rates, counters and hashes are
in [the evidence](benchmarks/ticket62-dispatch-chaining.json).

CPU scaling_cur_freq reported 1992000 while debug armclk reported 1104000000;
these disagree and neither proves actual frequency. Cooling states were zero;
reported temperature ranges are retained. No clocks were changed.

The diagnostic source was reverted only after verifying its exact saved patch.
The normal APK SHA256 is
`5a114b2b0db15ef2327b09f44daef61a2b4c8544792b7777c4b610e270f23db9`.
The restored installed APK hash was verified on-device; all kairo profiling enable markers were absent.
