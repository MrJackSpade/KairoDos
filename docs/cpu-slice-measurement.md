# Ticket #61: interrupt-related slice fragmentation

Decision: **no interrupt/flag exit change justified**. Staging does not exhibit
the unconditional flag-instruction scheduler exits that the cited PC-98 change
removed. Opcode fallback is a separate, measured target for #62.

## What was measured

The diagnostic-only [patch](benchmarks/ticket61-slice-probe.patch) counts exits
from `CPU_Core_Dynrec_Run` and nested `CPU_Core_Normal_Run` calls, block-return
codes, CLI/STI/POPF helper calls, cycle-budget histograms, and IF/TF/pending-IRQ
state. It does not change instructions, flags, cycle accounting, interrupt
delivery, block size, linkage or invalidation. It does not read extra guest bytes.
All counters and periodic output run on the emulation thread.

A marker enables counting in a Debug-only `-PguestProfile=true` build. The normal
guest-translation metadata marker is absent. Complete counter records inside
the simpleperf interval span **119.074 seconds**. The analyzer checked monotonic
records/counters and histogram totals against completed calls. BlockReturn enum
values were verified against the matching ELF's DWARF.

## Exit counts

| Dynrec core return reason | Calls |
| --- | ---: |
| Cycle budget expired | 2,080,395 |
| Opcode fallback through normal core | 2,306,535 |
| IRET with IF enabled and IRQ pending | 3,852 |
| Callback | 9,399 |
| Trap, special-page fallback, SMC return, unclassified | 0 |
| **Total** | **4,400,181** |

The interrupt-specific returns were **0.08754% of dynrec calls**, or **32.35/s**.
All had IF enabled, a pending IRQ, and TF clear. Of **158,151 IRET block returns**,
**154,299 continued inside the core** rather than returning to the scheduler.
There were no normal-core interrupt/trap exits in this capture.

CLI, STI and POPF helpers were called 52,207, 46,045 and 13,012 times respectively.
Their complete before/after state transitions are in the
[machine-readable evidence](benchmarks/ticket61-cpu-slices.json). Counts include
unsuccessful helper calls; they are not claimed as retired instruction counts.

Opcode fallback accounted for **52.419%** of dynrec returns. The normal core was
called exactly 2,306,535 times, always with a one-cycle input budget. These calls
are nested inside the dynrec calls, not another 2.31 million scheduler slices.
The diagnostic distinguishes this fallback path from the explicit SMC-block
return; it does not identify the unsupported opcodes yet. That attribution belongs
to the dispatch investigation in #62.

## Budget and event interpretation

The full entry, remaining and register-delta histograms are retained in the JSON.
Most dynrec calls began with 257–4096 cycles; 254,122 began with one cycle.
The normal fallback always began with one. **These are scheduler budgets, not
retired instructions.** In particular, the normal interpreter's post-decrement
loop can change a one-cycle budget from 1 to -1. A register delta of two therefore
does not mean two instructions executed.

Fallback transfers residual budget into `CPU_CycleLeft` before calling the normal
core. The measured dynrec aggregate change to that pool was +2,592,401,279 cycles;
it must not be counted as discarded work or added to nested normal-core usage.

`PIC_IRQCheck` is pending IRQ state, **not** a claim that the timer/video/audio
event queue is empty when false. `PIC_RunQueue` assigns a budget up to the next
event deadline or the remaining millisecond budget. Thus the many cycle-expiry
returns with no IRQ pending are not evidence that the returns can be removed.

## Source comparison and safeguards

Kairo98 `a12897b` was reviewed locally. Its old `IRQCHECKTERM` unconditionally
ended an interpreter slice after flag operations; its guarded replacement checks
trap/reset/DMA/interrupt requirements. Staging's behavior is different:

- Dynrec CLI and POPF continue the block, subject to their existing exception checks.
- STI retains one following instruction before ending the translated block. A
  block boundary is not automatically a return to the outer scheduler.
- Dynrec IRET continues unless its pending-IRQ or trap condition requires return.
- The normal interpreter checks pending IRQ/trap state at the relevant flag paths.

Changing these paths without an independently demonstrated cause risks interrupt
latency, STI behavior and guest clocks. This capture establishes no such candidate.
It does not certify every other game or an explicit interpreter configuration.
No Kairo98 code was changed.

## Overhead and restoration

All three runs use the same real Duke copy at 320x200, `core=dynamic`, `cycles=auto`,
`output=texturenb`, 60-second warmup and 120-second rendered-demo capture on RGDS
over Wi-Fi. Device wakefulness is checked throughout; perf loss counts and full
hardware readings are retained. Display submissions are not unique game FPS.

Counting measured **17.206 display submissions/s**, versus **18.153/s** in the
same APK with the marker removed: an observed **5.22% reduction**, including run
variation. Disabled hooks and compiler-layout changes remain in that APK, so it
is not an optimization reference. The restored normal build measured **17.963/s**;
the small difference from the disabled diagnostic run is not an improvement claim.
All three captures reported zero lost samples and remained awake. Full results are
recorded in the machine-readable evidence alongside the counter window.

The source hooks and marker were removed. The exact original APK was restored:
`5a114b2b0db15ef2327b09f44daef61a2b4c8544792b7777c4b610e270f23db9`.
The diagnostic APK hash is
`52a25885009ca86b83507626fa23e2031d1d274def4019af4a18559640db674f`, with core Build ID
`0b00c733dbcd16d6e9ddfb88bbf27536124f41e9`. Do not ship it or treat it as faster code.

Reproduce analysis with `tools/analyze_cpu_slices.py PROFILE_DIRECTORY
--simpleperf-dir DIRECTORY --tid EMULATION_TID`. The input directory contains
`perf.data` and `slice-counts.jsonl`; output is `slice-summary.json`. This analyzer
matches the saved probe's event layout. No performance optimization is adopted
by #61; #62 receives the fallback and block-return evidence.
