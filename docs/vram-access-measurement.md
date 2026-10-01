# Ticket #60: VRAM access cost

Decision: **no optimization justified by this workload**. Keep the existing
Staging handlers. No emulator, game, configuration, or Kairo98 changes were made.

## Measurements

Two uninstrumented reference captures from #71 were analyzed, each with the agreed
60-second warmup and 120-second rendered Duke demo at 320x200. Both used the exact
normal APK and matching core ELF, with zero lost simpleperf samples. No access
counters or diagnostic APK were used. The read-only runtime snapshot happened
after capture; it adds no measurement overhead to these recordings. Existing
sampling overhead remains common to both recordings.

| Emulation-thread sampled CPU seconds | Reference A | Restored reference A2 |
| --- | ---: | ---: |
| Entire emulation thread | 102.869 | 103.333 |
| VRAM handler body, including inlined operations | 0.495 | 0.283 |
| VRAM handler including callees | 0.505 | 0.293 |
| Named generic memory bodies, all destinations | 1.657 | 1.566 |
| Additional inlined generic access outside those bodies | 0.141 | 0.141 |
| Six checked dynrec memory helper bodies | 11.162 | 10.970 |
| Palette-to-pixel scanline conversion | 2.677 | 2.525 |

All sampled `vga_memory.cpp` instructions belonged to
`VGA_ChainedVGA_Handler::writed`: **0.27–0.48% of emulation-thread CPU**. Inclusive
cost includes one kernel sample per capture; it is not all device-access work.
No other VGA memory handler was sampled. Absence of samples does not prove zero
calls, especially for rare reads or other modes.

Generic access figures include ordinary RAM, not just VRAM. Even pessimistically
charging all named generic bodies, their extra inline work, and the VRAM handler
to VRAM yields only 1.99–2.29 sampled CPU seconds, and those operations cannot all
be eliminated. This is a bound on those measured bodies, **not** on every guest
address calculation or all work related to VRAM. Source-inline attribution avoids
mistaking disappearance through compiler inlining for zero work.

The six checked dynrec helpers are separate from generic `mem_writed` and friends.
#59's counters did not cover string/bulk helpers; therefore its absence of counted
VRAM handler calls was not evidence that VRAM was unused. Staging's
`dynrec_movsd_dword` calls `mem_readd`/`mem_writed`, which explains an access path
outside those six counters. Their much larger CPU total must not be relabeled as
VRAM cost. Palette conversion is also separate from guest VRAM access.

## Actual handler and constraints

A read-only snapshot of the restored normal process used offsets from its matching
ELF symbols and DWARF. All 16 pages in A0000–AFFFF had null read/write direct TLB
pointers and the same handler vtable: core-relative `0x996ac0`, equal to the ELF's
`VGA_ChainedVGA_Handler` vtable plus its 16-byte ABI header. Flags were `0x10`
(`PFLAG_NOCODE`). Live state:

- `mode = M_VGA` (8), chained and compatible-chain4 both true.
- VRAM allocation 4 MiB; address wrapping 256 KiB.
- `vmem_delay_ns = 0`: this is not a configured VRAM timing-delay problem.

`VGA_SetupHandlers` selects this class for compatible chained VGA; other modes
select different classes. Mode/bank changes clear the TLB. The current handler:

1. Preserves physical translation, bank offsets and address wrapping.
2. Uses an aligned dword host store already; unaligned addresses use byte handling
   because planar storage is not contiguous across every guest boundary.
3. Updates both planar backing and `fastmem`, including first-line replication.
4. Preserves the video-memory delay policy and optional change tracking.

A direct generic TLB pointer would bypass those semantics. Other VGA/EGA modes
also need latch, plane-mask, raster-operation and read-mode behavior. A future
fast path must retain the live mode/bank/wrap guards, crossing behavior, both
representations, invalidation and guest timing; it cannot assume all DOS video
memory behaves like the current Duke mode.

The cited Kairo98 `e8bd3c6` change was reviewed locally. Its direct VRAM word path
guards PC-98 physical windows and page boundaries before calling bank handlers;
its EGC shifter is a different device. Neither provides a DOS shortcut to copy.

## Evidence and reproduction

[Measurements, runtime state and sampled source addresses](benchmarks/ticket60-vram-access.json)
record the normal core Build ID `9982f89784e2630b949c1f7c06d336c7220185d7` and APK
SHA256. The source path is `src/hardware/video/vga_memory.cpp`; generic access is
in `src/hardware/memory.cpp` and `src/cpu/paging.h`; string helpers are in
`src/cpu/core_dynrec/operators.h` under `third_party/dosbox-staging/`.

`tools/analyze_vram_profile.py` takes `--simpleperf-dir`, `--symbolizer`, `--elf`,
one or more `--profile DIRECTORY EMULATION_TID` pairs, and `--output DIRECTORY`.
Use the matching unstripped ELF and the actual emulation thread, not the presenter
thread with a similar name. Re-running it reproduced both summaries exactly.

This establishes no useful VRAM bypass candidate in the measured workload. It
does not claim every DOS video mode is equally cheap. Revisit if a representative
profile shows a material handler cost; no implementation ticket is warranted now.
