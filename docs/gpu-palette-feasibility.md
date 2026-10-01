# Ticket #65: GPU palette expansion feasibility

**Feasibility audit complete: no production implementation recommended from the
measured palette-only budget.** No application renderer or game configuration
changed for this audit. Initial Retroid evidence is preserved below; the final
RGDS measurements and qualified decision follow at the end. There is no measured
end-to-end game improvement to adopt.

## Narrow CPU attribution

`tools/profile_palette_cost.py` reads the existing real-game simpleperf captures
and their matching symbol files. The original baseline attributes about five CPU
seconds to VGA scanout/scaling in total. That total is not palette-only work.
All six captures' palette-function samples were in
`draw_linear_line_from_dac_palette`, with 2.182-2.828 self CPU seconds per roughly
120-second capture.

The three indexed-to-RGB loops occupy relative instruction ranges
`[0x74,0x8c)`, `[0xc4,0xdc)`, `[0x100,0x118)`. Each contains six ARM64 instructions:
load an index, loop bookkeeping, form its palette address, load RGB, store RGB,
and branch. The ranges were disassembled in both baseline/SETcc binaries and
their instruction words matched exactly. The analyzer only applies these ranges
to the two audited ELF hashes; it does not assume future compiler layouts match.

| Capture | Conversion-loop CPU seconds | Emulation-thread CPU share |
| --- | ---: | ---: |
| Original RGDS baseline | 2.465 | 2.387% |
| #72 A | 2.111 | 2.042% |
| #72 B | 2.626 | 2.556% |
| #72 A2 | 2.293 | 2.251% |
| #72 B2 | 2.697 | 2.625% |
| #72 A3 | 2.727 | 2.659% |

These are sampled estimates, not exact timings or disjoint measurements of
individual loads. Sampling skid can move attribution within a loop. Index reads,
addressing and output storage are included, and some work must remain to build
an indexed snapshot. Eliminating every sampled loop cycle with zero replacement
cost would give only about 2.1-2.7% ideal serial CPU speedup from **this component**.
This is not an upper bound for a larger renderer rewrite that also removes other
work, and it is not an FPS promise. The existing captures retain their documented
warmup and sampling limitations; the #72 captures' nominal warmup was around
80 seconds rather than 60. No new game-performance comparison was performed.

## Added-work budget

Let `P` be measured conversion-loop CPU seconds, `T` the recorded interval, `F`
the number of indexed frames uploaded per second, and `C` added CPU seconds per
upload. The optimistic upload-only budget is `P/T - F*C`; it must also pay for
index/history capture on scanouts that do not upload. The final model below
includes that otherwise omitted cost explicitly.

Across these captures, break-even added CPU work is:

- At 20 uploads/s: **0.880-1.137 ms/upload**.
- At 70 uploads/s: **0.251-0.325 ms/upload**.

Upload cadence cannot be inferred from display submissions or the VGA refresh
rate. Preserve unchanged-frame suppression; uploading every scanout would use a
different budget. Host conversion/copy savings must be measured separately,
not silently credited as palette-loop savings.

## Offscreen GPU cost probe

`tools/gpu_palette_cost_probe.cpp` uses the existing shared `EglContext` and
`GpuFrameQueue` at shared commit `20b9c7b0023cc28114a72da5501cb16bd9c2e909`.
It runs standalone under ADB; no app installation is needed. It uploads R8UI
indices and RGBA8 palettes, draws an exact nearest-lookup shader into the existing
queue framebuffer, and invokes the real queue snapshot/fence/flush path. Twenty
warmup iterations precede 120 measured iterations per case. Each iteration waits
for completion, and final CPU readback checks every producer-FBO pixel against
the scalar expected color. No display/window consumer is attached.

Retroid Pocket Classic, Adreno A12, NDK ARM64 API26 `-O2`:

| Size / palette history | Upload bytes | CPU submission ms | Upload wall ms | Snapshot wall ms | Completed wall ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 320x200 / one palette | 65,024 | 0.0911 | 0.0274 | 0.0463 | 0.2693 |
| 320x200 / palette per row | 268,800 | 0.1106 | 0.0345 | 0.0520 | 0.3213 |
| 640x480 / one palette | 308,224 | 0.1272 | 0.0432 | 0.0605 | 0.5124 |
| 640x480 / palette per row | 798,720 | 0.2175 | 0.1056 | 0.0800 | 0.6851 |

All 742,400 final pixels passed exact RGBA comparison. This checks palette lookup
and row selection in the producer FBO, **not** queued presentation ordering or
every DOS rendering mode. Snapshot calls completed without GL errors, but the
consumer's surface work, slot-return fences and real concurrent load are absent.
Index/palette snapshot construction on the CPU is also excluded. Synchronous
completion timings are not a prediction of asynchronous app latency. Short
offscreen runs do not establish sustained thermal behavior or game FPS.

A clock-only control using the same timestamp sequence measured 0.740 microseconds
wall and 0.419 microseconds thread CPU per iteration. Raw GPU values include this
cost. The earlier probe repetition is retained locally; no best-run selection was
used. A rebuilt executable initially lacked its executable bit after ADB push;
that attempt never ran and supplied no measurements.

Historical RGDS/Pure shared-snapshot measurements in
[the prior presenter evaluation](software-frame-presentation.md) were materially
larger (0.589-0.641 ms for Doom), but differ in core/path/protocol. They warn against
assuming the Retroid cost transfers to RGDS; they are not a substitute for an
RGDS indexed-palette probe or a valid direct subtraction from Staging CPU cost.

## Correctness requirements for a future implementation

- Capture indices and the **resolved palette at each emulated scanline/part's
  current sampling point**. DAC writes update `vga.dac.palette_map` during guest
  execution. A single final palette would recolor earlier rows incorrectly.
  Immutable palette versions plus per-row version IDs can avoid repeating an
  unchanged 1 KiB palette, but capture/deduplication cost must be included.
- Preserve DAC masks, attribute remapping and screen-disable black output. Index
  bytes alone are not a complete image description. Retain guest scanout/PIC
  timing, wrapping/panning, line stride, source geometry and pixel aspect rules.
- Keep the existing CPU route for unsupported formats/effects: direct RGB,
  composite CGA, text/planar paths not explicitly covered, hardware cursor,
  ReelMagic composition and deinterlacing. No unsupported mode may disappear.
  Frame transitions must retain enough sampled row state to reconstruct a CPU
  fallback when needed; do not discard already sampled rows or reinterpret them
  with a later palette. Mode changes/context loss require explicit lifecycle tests.
- Preserve dirty detection. Palette version changes that affect no used color
  should not blindly force an upload on every scanout. The cost of retaining
  index/used-color history belongs in the net estimate.
- EGL upload/shader/mailbox ownership belongs in shared frontend infrastructure;
  Staging-specific scanline/palette capture belongs in the DOS adapter. The
  present callback carries RGB pixels only, so changing the GLSL shader alone
  cannot remove the upstream CPU expansion. Do not change Kairo98 for this audit.
- Use exact CPU/GPU image comparisons for palette animation, split-screen palette
  changes, wraps, disabled output, mode switches and fallback. Preserve nearest
  lookup, channel order, alpha and color-space behavior. The current #52 mailbox
  correctness issue must not be hidden by this proposal.

## Initial evidence gap

The Retroid experiment establishes technical feasibility of both palette layouts,
not a net RGDS gain. Next, run the same isolated probe on RGDS and measure actual
indexed snapshot/capture cost and upload cadence for the target workload. The
result must fit the explicit per-frame budget, including fallback/history work,
before a separate implementation ticket is justified. This was the interim state;
the RGDS returned on October 1 and the probes below resolve that measurement gap.

Raw costs, ELF identities, break-even budgets and limitations:
[measurement record](benchmarks/ticket65-palette-feasibility.json).

## RGDS measurements

The RGDS returned at a freshly discovered Wi-Fi ADB endpoint. After separately
finishing #72's adopted-build installation and game smoke check, the game was
stopped before either standalone probe ran. Both probes were repeated without
changing clocks. The device reports Mali-G52. Before the repeat, scaling sysfs
reported 1992 MHz, debug `armclk` 1104 MHz and GPU 800 MHz; those CPU sources
disagree, so no actual-frequency or maximum-clock claim follows.

GPU CPU submission costs (two repetitions):

| Input | Upload/draw/shared snapshot CPU ms/frame |
| --- | ---: |
| 320x200, one palette | 0.636-0.661 |
| 320x200, per-row palettes | 0.936-0.955 |
| 640x480, one palette | 0.913-1.029 |
| 640x480, per-row palettes | 1.340-1.362 |

Each repetition passed every producer-FBO pixel comparison. GPU timing still
excludes a window consumer, CPU capture and mailbox integration. The clock-only
control is below one microsecond CPU per iteration, included in raw timings.

`tools/palette_snapshot_cost_probe.cpp` measures a separate plausible immutable
capture layout: copy index rows, retain palette versions, compare successive
histories, and exchange owned buffers. It also measures scalar palette expansion
and checks the captured output against that reference. Ordinary modes read one
shared palette table; per-row effects supply distinct sampled tables. An earlier
version unnecessarily materialized identical palettes per row; those initial
timings are excluded from the final model and retained locally for traceability.
These are synthetic feasibility costs, not instrumented Staging snapshot code.

| 320x200 case | Capture/history CPU ms | Scalar conversion CPU ms |
| --- | ---: | ---: |
| Static | 0.118-0.119 | 0.211 |
| Changing indices | 0.070-0.071 | 0.210-0.211 |
| Frame-wide palette change | 0.104-0.105 | 0.209-0.210 |
| Palette change per row | 0.222-0.225 | 0.224-0.225 |
| Only unused palette entry changes | 0.117-0.118 | 0.208-0.210 |

At 640x480, changing-index capture costs 0.226-0.228 ms and scalar conversion
1.003-1.006 ms. Adding one GPU upload/submission raises that changing-frame
candidate to 1.139-1.257 ms before consumer costs. At 320x200 the corresponding
candidate is 0.706-0.732 ms versus approximately 0.210 ms scalar conversion.
This does not mean static frames should be uploaded: their candidate upload
count is zero after history is established.

All five cases at both sizes passed exact captured-output checks. The conservative
history test correctly suppresses static frames but flags **all 120 unused-palette
changes as dirty despite zero RGB changes**. That is wasted work, not a color
error. A production design needs used-color or equivalent exact change tracking;
that additional work is not credited as free. Palette-generation counters and
other capture improvements could lower these costs, but have not been measured.

## Net estimate and critical-path distinction

For `S` scanouts/s and `F` changed uploads/s, use:

`CPU saving <= P/T - (S-F)*C_static - F*C_changed - F*C_gpu`.

Using **assumed**, not measured, cadence of 70 scanouts and 20 changed uploads/s,
the two RGDS repetitions estimate:

- Capture/history: 7.31-7.38 ms CPU/s.
- GPU submission: 12.72-13.21 ms CPU/s.
- Combined: 20.09-20.52 ms CPU/s, against the measured palette-loop budget of
  17.60-22.74 ms CPU/s. Estimated total CPU saving crosses zero: roughly
  **-2.92 to +2.64 ms CPU/s**, before unmeasured integration/consumer work.

Total CPU and the emulation thread's critical path are different. If GPU work
runs on an independent worker, the model removes only capture from that thread's
budget, leaving a hypothetical 10.23-15.42 ms CPU/s saving there. That is roughly
1-1.5% of one CPU's wall-time budget, **not measured game FPS**. Uploading on the
emulation thread would spend the GPU submission cost there too. The model does
not prove either placement speeds up games; threading, dirty cadence, palette
effects, queue ownership and existing copy savings need an integrated comparison
before adoption. Other renderer savings are outside this palette-only estimate.

## Final decision

The lookup operation is technically feasible, including row-specific palettes,
but this audit does not justify a general GPU palette rewrite. RGDS driver and
snapshot cost is significant, modeled total CPU benefit is near zero, the small
possible critical-path benefit remains unproven, and the capture design still
needs more precise dirty handling plus the compatibility requirements above.
Keep the current CPU path. Do not create or ship a renderer implementation from
these measurements. This is a no-go for the proposed optimization **on current
evidence**, not a claim that all GPU palette designs or resolutions must lose.

Acceptance evidence: palette-only attribution, same-device upload/snapshot and
capture costs, explicit net-cost model, measured timer controls, and a plan to
preserve per-scanline palettes and unsupported modes are all documented. The
ticket requests feasibility/no-go evidence rather than a renderer implementation;
that audit is complete. No measured end-to-end improvement is being withheld.

Final raw records and model:
[RGDS costs](benchmarks/ticket65-rgds-palette-costs.json). Original game files,
Kairo98, and runtime renderer code remain unchanged by #65. The RGDS retains the
separately adopted #72 APK; the probes did not replace it.
