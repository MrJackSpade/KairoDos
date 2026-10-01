# Ticket #65: GPU palette expansion feasibility

**In progress. No application renderer or game configuration changed.** Palette
work is measurable, but same-device net benefit is not yet established. The CPU
cost comes from RGDS; the new offscreen GPU probe ran on Retroid. Subtracting one
device's measurements from the other's would not establish an improvement.

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
upload (index/history capture + palette versions + upload + draw/snapshot
submission). Palette-only net CPU saving is at most `P/T - F*C`.

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

## Next evidence and current decision

The Retroid experiment establishes technical feasibility of both palette layouts,
not a net RGDS gain. Next, run the same isolated probe on RGDS and measure actual
indexed snapshot/capture cost and upload cadence for the target workload. The
result must fit the explicit per-frame budget, including fallback/history work,
before a separate implementation ticket is justified. Until then, **do not move
palette expansion into production or claim a performance improvement**. #65
remains open; no source in either application's runtime was changed by this work.

Raw costs, ELF identities, break-even budgets and limitations:
[measurement record](benchmarks/ticket65-palette-feasibility.json).
