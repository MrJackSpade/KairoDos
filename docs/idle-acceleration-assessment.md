# Issue #57: idle-loop acceleration assessment

## Decision

**Do not implement or enable idle-loop skipping on the current evidence.** This is a no-go for the measured candidates, not a claim that generic acceleration is impossible for DOS workloads.

The [#56 measurements](benchmarks/ticket56-polling-analysis.json) identified only 0.091 sampled CPU seconds in isolated VGA status-polling blocks out of 103.30 sampled emulation CPU seconds. The separately measured 0.465 seconds in OPL read-delay blocks are finite counter delays, not a wait for a state change. First iterations in mixed blocks and missing guest-caller attribution limit those estimates. Dominant inspected loops perform useful work; no large, side-effect-free RAM wait loop has been established. An all-game detector would add overhead and correctness obligations without a measured high-value candidate.

No production source, guest code, settings, cycle policy, event timing, or APK is changed by this assessment. No new speedup is claimed.

## Why these candidates do not qualify

| Concern | Inspected source / behavior | Requirement and decision |
| --- | --- | --- |
| I/O side effects | `src/hardware/video/vga_misc.cpp::vga_read_p3da` resets attribute-controller and PCjr flip-flops, and derives blanking bits from PIC time | Repeated register values do not imply a pure read. Exclude these VGA loops from a generic RAM-idle detector. A separate hardware-aware approach would need to preserve each observable side effect and transition; the current measured cost does not justify it. |
| Time-sensitive reads | `src/hardware/audio/opl.cpp::Opl::PortRead` deducts cycles and updates `CPU_IODelayRemoved`; `OplChip::Read` updates timers | The six/27-read loops discard returned status but still consume guest time. Removing reads would alter timing. Their changing EBX counter is also not a fixed point. Exclude them. |
| Memory and code changes | RAM can be changed by event callbacks or DMA; translated code can invalidate and fall back through `BR_SMCBlock` | A future detector must prove stable ordinary RAM and code, account for writes through every relevant path, and invalidate candidates on mapping/mode/code changes. A register snapshot alone is insufficient. |
| Interrupts and event insertion | `src/hardware/pic.cpp::PIC_RunQueue` services due events, selects the next cycle budget and runs IRQ handling; event insertion can shorten the current slice | Never skip across the next observable event. Preserve pending/masked IRQ state, IF transitions, traps, exceptions and callback ordering. Do not assume the initially observed deadline remains valid after side effects. |
| DMA | `src/hardware/dma.cpp::perform_dma_io` uses physical reads/writes with EMS/page mapping; channel callbacks and transfer state are separate from guest CPU registers | Conservatively disable a candidate while relevant DMA can run unless all DMA/device mutations and deadlines are tracked. CPU-store instrumentation alone is not enough. |
| Residual cycles | Dynrec block-entry cycle checks, `dyn_reduce_cycles`, `BR_Cycles`, linked blocks and interpreter fallback govern the actual scheduling boundary | Preserve Staging's existing instruction/block position and cycle overshoot at the boundary. Only whole proven invariant periods could be elided; execute the remainder through the existing core. Do not transplant interpreter instruction-cost arithmetic. |

Source paths in the table are relative to `third_party/dosbox-staging/`. Additional reviewed paths: `src/cpu/core_dynrec.cpp`, `src/cpu/core_dynrec/decoder.h`, and `src/cpu/core_dynrec/decoder_basic.h`.

## PC-98 lesson retained

Kairo98 commit `9a8efd81a744858d6d1a3708c5ed6b4361bf8854` corrected consuming the entire remaining slice during idle detection. Its checker instead preserves residual instructions and cycle overshoot; its synthetic test exercised 4,096 budget/barrier cases and exposed 911 failures in the original implementation. That history is a correctness warning, not proof that the same algorithm or timing model applies to Staging. Kairo98 was read only for this assessment and has not been modified.

## Conditions for reconsideration

First measure a material candidate on representative DOS workloads. A bounded initial design would accept only a proven repeatable cycle of ordinary RAM reads and pure register operations with stable complete architectural state, positive deterministic cycle period and unchanged memory/code/mapping generations. Reject I/O, device memory, writes, variable external state, traps, exceptions, active DMA, privileged/time-reading operations and untracked callbacks. Integrate with the real Staging scheduler and invalidate the proof at each event or relevant state change. Linked-block execution must be covered; observing only the outer dispatcher is insufficient.

This is a set of acceptance constraints, **not an implemented or verified detector**. There is no implementation ticket because the measured prerequisites have not been met. Generic host measurement infrastructure may be shared; CPU/event eligibility and skipping would belong in the Staging core, not the Android frontend or Kairo98.

## Required differential tests if a candidate is later implemented

Run the same synthetic guest programs and deterministic event schedules through an unmodified Staging dynrec and the proposed detector. Use the interpreter only as an additional reference where its timing model permits comparison; it is not a substitute for matching the unchanged dynrec's boundaries.

1. **Residual boundary sweep:** use loops whose registers temporarily change then return to the same state. Sweep budgets from one cycle through many loop periods, including budgets shorter than a block and just before/at/after an event. Compare EIP, registers, flags (including lazy-flag materialization), memory, remaining cycles and overshoot at every event and after exit.
2. **Memory mutation and invalidation:** have a scheduled event change the polled RAM, change an operand/code byte, remap a page, or trigger an access fault. Compare exit iteration, data seen, exception state and code invalidation; no stale candidate may survive.
3. **I/O and device reads:** include VGA status/flip-flop reads, OPL timer reads, port writes and time-reading instructions. Require detector rejection and identical port-event order, read results, cycle bookkeeping and device state.
4. **Interrupts and traps:** exercise masked/pending IRQs, IF transitions, interrupt return, single-step and exceptions before/within/after a loop. Compare delivery boundary, saved return state and event ordering.
5. **DMA:** modify polled memory through DMA, cross an EMS/page boundary, reach terminal count and invoke a channel callback. Require rejection while DMA is untracked and identical memory, channel state and interrupt behavior.
6. **Dynrec integration:** exercise linked blocks, cache eviction, self-modification, fallback instructions, real/protected mode and guest session reset. No candidate may outlive its code or machine state.
7. **Measured adoption gate:** compare detector disabled/enabled on candidate and noncandidate games, including audio/video timing and output. Include detector overhead even when nothing qualifies. Require repeatable benefit and matching correctness evidence before enabling it by default.

These tests are specified for a future implementation and **were not executed as part of this no-change design ticket**. Existing #55/#56 profiler tests validate analysis tooling, not idle-skipping correctness.

## Next action

Close #57 with this no-go decision and proceed to #58's instruction-fetch investigation. Reopen idle acceleration only when new measurements identify a substantial qualifying wait loop; do not infer one from host CPU utilization or the PC-98 speedup.
