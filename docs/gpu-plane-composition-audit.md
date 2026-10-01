# DOS video-plane composition audit (#66)

## Decision

No runtime change. The measured Duke 320x200 path does not perform the
independent text/graphics-plane composition optimized in Kairo98. Moving that
operation to a GPU cannot accelerate an operation absent from this path.
Other DOS modes do contain related work, but these captures do not establish
a bottleneck in those modes or justify changing their behavior.

## Reference and actual DOS path

Kairo98 commit `145da7679398336f99422fe6ebd8da9878db170e` uploads independent
text and graphics indices plus per-line palette selections, then composes them
on its presenter thread. Its acceptance included pixel comparison and CPU
fallbacks. That architecture is reference material, not evidence DOS has the
same composition cost. Kairo98 was only inspected.

In Staging's `src/hardware/video/vga_draw.cpp`, the `M_VGA` setup selects
`draw_linear_line_from_dac_palette` when ReelMagic mixing is disabled. The
measured call stacks contain this routine under `VGA_DrawSingleLine`. It reads
one index stream, handles wrapping and screen disable, and expands the DAC
palette to RGB. There is no second text or graphics layer in that loop.
Its palette cost was separately audited in #65; counting it again as plane
composition would double-count the same potential saving.

`src/hardware/video/reelmagic/video_mixer.cpp` defaults to
`RMR_DrawLine_Passthrough`, which forwards directly to `RENDER_DrawLine`.
Disabled mixer setup restores that passthrough. The app adapter
`backend-dos/src/main/cpp/kairo_renderer.cpp` receives an already rendered RGB
framebuffer and submits it when dirty; it does not compose DOS planes.

`VGA_ChainedVGA_Handler` in `vga_memory.cpp` maintains guest VRAM and a linear
cache, including first-line wrap replication. That is memory mapping/cache
maintenance, not a PC98-style text/graphics overlay. CPU guest reads and writes
still need correct VRAM semantics; treating these costs as removable shader
composition would be incorrect. VRAM handling was audited separately in #60.

## Measurements

`tools/profile_video_planes.py` reads the existing six RGDS simpleperf records
used in #65, with each record's matching debug ELF and emulation-thread ID.
It records self and inclusive symbol attribution for explicit families.
No new device instrumentation, build, clock change or game modification was
needed. Offline analysis adds no runtime instrumentation overhead beyond the
original simpleperf captures. Capture identity and ELF hashes are in
[the measurement record](benchmarks/ticket66-video-planes.json).

The baseline is the unmodified 320x200 title demo with normal audio and cycles
auto, described in `duke3d-performance.md` and the September 30 baseline JSON.
The five #72 captures compare baseline/SETcc builds; their warmup timing
limitations remain those documented in `setcc-adoption.md`. Each profile is
approximately 120 seconds. This audit makes no new FPS comparison and does not
interpret display submissions as unique guest frames.

Across all six captures:

- Text, hardware-cursor, ReelMagic mixer and EGA memory-handler families have
  zero attributed self and inclusive samples.
- VGA memory handlers have 0.242-0.495 seconds inclusive CPU per capture.
  Even removing this entire category would represent only about 2.0-4.1 ms
  CPU per wall second; that is deliberately an overestimate, since mapping,
  guest memory semantics and cache writes are not removable plane composition.
- Palette-family inclusive CPU is 2.232-2.949 seconds. This includes callees;
  #65's narrower audited loop measurement remains the relevant palette budget.

Zero samples alone is not proof of zero execution: sampling misses brief work,
and inlining or incomplete unwinding can obscure functions. The no-composition
conclusion for the benchmark also relies on the observed scanline routine and
its source path. No statistical upper bound is claimed for unobserved families,
and no performance claim is made for text/EGA/ReelMagic workloads not measured.

## Other modes and requirements for any future implementation

| Path | Actual work | Required preservation / fallback |
| --- | --- | --- |
| EGA planar memory | `VGA_ChainedEGA_Handler` and `VGA_UnchainedEGA_Handler` combine bit planes into cached indices during writes using `Expand16Table` | Guest latches, read/write modes, masks, raster operations and read-after-write semantics stay correct on CPU; snapshot changes at the original scanout points. Do not defer guest-visible writes to an asynchronous GPU. |
| Text | `VGA_TEXT_Draw_Line`, `VGA_TEXT_Herc_Draw_Line`, `draw_text_line_from_dac_palette` expand glyphs and attributes and apply cursor/blink effects | Preserve font RAM changes, foreground/background, blink, underline, cursor, panning, wrapping and line-specific timing. This is text rasterization, not an always-present layer over graphics. |
| SVGA hardware cursor | `VGA_Draw_LIN16_Line_HWMouse`, `VGA_Draw_LIN32_Line_HWMouse` and indexed cursor drawing | Preserve AND/XOR/transparent operations, coordinates, clipping and pixel format; unsupported combinations retain CPU output. |
| ReelMagic | `RMR_DrawLine_VGAMPEGSameSize` and resize variants mix VGA with decoded MPEG | Preserve provider timing, VGA-over/under selection, transparent index, scaling and frame lifetime. Keep CPU fallback for unverified variants. |

Any future measured bottleneck in these paths requires its own narrow evidence
and implementation decision. Acceptance would compare every output pixel with
the CPU reference through mode changes, palette/font/VRAM raster effects,
cursor/blink transitions, wrap/pan, and the relevant ReelMagic sequences.
Captured inputs must remain immutable until GPU consumption. Unsupported modes
and mid-frame transitions must retain a correct CPU path without reinterpreting
earlier rows using later state. Generic GPU ownership/presentation belongs in
shared; Staging mode capture remains in the DOS adapter/core.

The requested applicability audit is complete. There is no supported
plane-composition optimization to implement from the current benchmark evidence.
Original games, runtime code and Kairo98 remain unchanged.
