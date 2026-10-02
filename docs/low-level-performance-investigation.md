# Low-level performance investigation

Status: complete. All ledger targets have a measured result or evidence-based
exclusion; the final normal-product result and deployment are recorded in the
[integration report](low-level-final-integration.md).

## Completion gate

Review every target below against current measurements. Give each supported
candidate its own controlled experiment and disposition. A negative experiment
does not close the remaining targets. Revisit the hotspot distribution after an
accepted change. Finish only when the reasonable measured targets have been
tested or excluded with concrete evidence, then verify, commit, push and deploy
the resulting normal build. Keep Duke's game files, resolution, sound and cycle
configuration fixed. Report surface submissions separately from unique FPS.

## Candidate ledger

| Target | Evidence to establish | Status |
| --- | --- | --- |
| Checked-read return dependency | Time in cached lookup, helper, scratch store and generated reload; preserve exception and page-crossing paths | Register-return candidate rejected after normal-PGO A/B/A; [evidence](arm64-read-return-evaluation.md) |
| Generated ARM64 register/instruction sequences | Time-valid sampled generated instructions, redundant moves/materialization or spills | Fixed-size literal calls rejected after normal-PGO A/B/A; [evidence](arm64-literal-call-evaluation.md). Adjacent register forwarding low priority at 0.191 sampled seconds |
| Flag calculation | Current cost of lazy flags, repeated computation and flag helper calls; preserve partial flags | Runtime mask tables rejected; [evidence](arm64-flag-mask-evaluation.md). CMP/TEST Jcc specialization retained after isolated normal-PGO comparison, runtime producer verification and the final combined product gate; [evidence](arm64-known-flags-evaluation.md) |
| Dispatcher and block linkage | Current dispatcher cost, link misses and exit causes; preserve invalidation and cycle accounting | Cached code-entry candidate rejected after normal-PGO A/B/A; [evidence](arm64-link-entry-evaluation.md) |
| Remaining interpreter fallbacks | Recheck previously observed LAR, bit-test group and BSR fallbacks after SETcc adoption; test individual generic translations only if material | Final normal profile: 0.251 self / 0.327 observed inclusive CPU seconds out of 104.492 main-thread seconds. No material fallback target established; [attribution and limits](low-level-final-profile.md) |
| Data layout and memory access | Identify a specific dependent load/cache or TLB cost before restructuring anything | Paging-only local binding retained after repeated normal-PGO comparison and Doom/Cannon lifecycle gates; [evidence](paging-storage-evaluation.md). Final combined result is +5.77%, not the sum of isolated gains |
| Lazy-flag state address dependency | Normal compiled flag helpers still use a GOT load before reading `lflags`; packaged host/runtime have no import of this core-owned object | Retained after B/C/C/B normal-product comparison: +4.17% over the combined build, with unchanged ABI except removal of the unused data export and passing representative lifecycle checks; [evidence](lazy-flag-storage-evaluation.md) |
| Code layout and branch behavior | Hot code footprint and branches, reproducible across normal builds | Fixed-size literal calls regressed; earlier compact-call gain failed final-build reproduction. All 710,218 recognized diagnostic call sites are outside direct-BL reach; current normal address-space gaps are too small for the cache. Arbitrary code reordering has no identified target; [attribution and limits](low-level-final-profile.md) |
| Other material current hotspots | Inspect whole-process profile, including audio worker, before excluding remaining reasonable targets | NEON interpolation retained: isolated mixer CPU reduction of 10.68%, no isolated FPS gain; [evidence](resampler-neon-evaluation.md). Normal-recipe exact float/integer comparison and final integration checks pass. Remaining OPL/palette/layout targets have [explicit dispositions](low-level-final-profile.md) |

## Prior experiments to retain

- Guarded inline reads regressed roughly 17%; see `inline-read-results.md`.
- Compact checked-memory calls improved in one isolated build but failed the
  current-tree comparison; see `arm64-helper-call-evaluation.md`. They remain
  rejected, not a shipped improvement.
- Strong internal symbol binding, SETcc generation and the ordered OPL worker
  are already adopted. Do not count their gains again.
- Scheduling priority, audio buffering and changed-row copying already have
  separate completed investigations; reopen only for new evidence.

## Reproducibility

Freeze each APK and its exact corresponding ELF before installation. Verify the
installed APK hash before and after capture. Concurrent frontend work previously
changed mutable build outputs; use isolated native builds and identical frontend
payloads for comparisons. A sampled PC in a reused JIT cache requires time-valid
translation metadata. End-of-run snapshots cannot establish earlier contents.
Instruction sampling has skid and cannot alone establish individual latency.

The normal core uses PGO. The existing build guard requires PGO to be disabled
for guest-profiling builds; such diagnostic instruction distributions are clues,
not interchangeable normal-build timings. Compare marker enabled/disabled using
the same diagnostic APK and validate candidates with normal PGO settings before
adoption. Do not weaken the profile-matching gate to make an experiment build.

Use fresh launches, verified rendered scenes, 60 seconds of warmup and 120 seconds
of capture. Repeat promising comparisons with bracketing baselines. Check complete
frame histories, audio underruns, clock/thermal observations and representative
games plus generated-code/lifecycle correctness. Confirm any gain again in the
normal product build before adoption. Keep raw guest bytes in ignored storage.

## Initial attribution, October 1

The frozen restored APK is `c781b38dc3c8227b5491dfeaeb928bf990bbccf6377702585dd723d6ad2200f6`.
The saved unstripped ELF failed `.text`, `.rodata` and build-ID equality, so
normal-baseline attribution uses the exact packaged ELF. Its stripped static
functions remain unresolved; do not fill those gaps with a mismatched ELF.
The 119.942-second full-observer recording lost zero samples. Its main thread
has 97.687 sampled CPU seconds, including 10.081 in the three checked-read
helpers, 3.828 in `CPU_Core_Dynrec_Run`, and 44.141 unresolved anonymous-code
seconds. The latter are not all proven JIT instructions by this recording alone.

The separate time-valid instruction diagnostic resolves 35.945 direct JIT CPU
seconds, with no missing code bytes or undecodable sampled instructions.
Families include 13.829 seconds in address/immediate/register moves and 9.497
in loads. These are sampled instruction locations, not recoverable-latency
estimates. The diagnostic APK is
`49e02161103b0fbb793ffff22075b6dee8a7e71643c29030762122eb7a2508d9`.
Its enabled/disabled runs both use 60-second warmups and 120-second captures,
light frame observation plus separate 199 Hz CPU sampling. Both lost zero perf
samples, retained complete frame histories and had zero capture-window
AudioFlinger underrun increments. Surface submissions were **21.065 enabled /
30.212 disabled**: logging materially perturbs throughput. Do not count that
difference as a product speedup or assume the diagnostic instruction mix exactly
matches the PGO build.

Exact five-instruction call recognition attributes 6.146 sampled seconds to
helper-target setup and 0.935 to the corresponding indirect-call instructions.
Loads based on x12 (the backend's block-link temporary in `gen_jmp_ptr`) account
for 3.131 seconds; confirm exact sequences before attributing every such load to
links. Adjacent 32-bit CPU-register store/load pairs account for only 0.191
sampled seconds, so the simple forwarding peephole is lower priority. Broader
register allocation is a different change and is not established by that count.
See [aggregate attribution](benchmarks/low-level-instruction-attribution.json).

The first candidate returns value/fault in ARM64 registers on cached reads and
delegates slow paths to the existing helpers. An on-device probe extracts the
actual candidate read generators and helper code. It passed 524,800 width,
cached/handler, page-crossing, partial-fault, wrapping-address and fault-destination
checks. This is not full-core compatibility evidence. Production source remains
unchanged while normal-PGO reference/candidate APKs are evaluated. The candidate
build passed the existing PGO matching gate without weakening it.

Compiled-code review subsequently exposed a gap in that initial probe: the real
scratch slot is pointer-sized, and a destination already in w0 elides the final
move. The expanded probe reproduced stale upper bits on the first prototype's
dword slow path. That prototype and its timing are excluded. Explicit dword
truncation fixes the result; the expanded 1,049,600-case probe covers both same
and distinct destination registers with nonzero upper scratch bits. Slow result
conversion is also kept out of line so the cached word/dword paths do not acquire
the stack saves observed in the first compiled prototype. The corrected candidate
subsequently failed the normal-PGO performance gate and remains rejected; see
the checked-read evaluation above. No read-return change is adopted.
