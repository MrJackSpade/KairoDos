# Ticket #72: SETcc experiment (in progress)

Not adopted. No measured performance benefit yet. The installed RGDS app remains
the normal baseline; the prototype was exercised only by a standalone runner in
`/data/local/tmp/kairo72`. No game data or Kairo98 source was changed.

The candidate follows #62's 1,997,470 SETcc interpreter fallbacks. The archived
[prototype patch](benchmarks/ticket72-setcc-prototype.patch) enables generic SETcc,
retains flag producers with AcquireFlags, normalizes nonzero helper results to 1,
and retains existing checked writes/exception handling. The old commented code's
`& 1` normalization is insufficient for flag masks returned by get_ZF/get_OF/etc.
Effective-address state is protected across the condition-helper call.

The isolated worktree build succeeded with `-PguestProfile=false`. The standalone
runner loads that actual Android library, uses the existing Staging callback ABI,
and supplies a synthetic COM program. The first attempt used a relative config
path that Staging rejected after changing working directory; it was stopped and
rerun with absolute paths. This was a harness setup failure, not a core result.

## Completed initial correctness gate

10,240 cases: all 16 conditions, all 64 combinations of CF/PF/AF/ZF/SF/OF,
eight byte registers plus two direct memory destinations, in real-mode 16-bit
code. Normal and dynamic cores produced byte-identical 409,600-byte snapshots.
An independent oracle additionally checked condition results, preservation of
other registers and arithmetic flags, and exact destination memory bytes.
Normal telemetry: zero translated/executed blocks. Prototype dynamic telemetry:
666 translated / 105,087 executed blocks. These counters establish generated
execution; they are not a speed comparison.

Evidence: [initial result](benchmarks/ticket72-setcc-initial.json).
Remaining: lazy flags, prefixes/address forms, protected-mode fault and SMC
handling, and the required uninstrumented A/B/A rendered-demo comparison. Do not
close #72 or apply the patch to production before these gates pass.

## Reproduction

- `tools/generate_setcc_guest_probe.py <directory>` writes `setcc.s`.
- NDK clang: `--target=i386-none-elf -c setcc.s -o setcc.o`.
- NDK ld.lld: `-m elf_i386 -Ttext=0x100 --oformat=binary setcc.o -o SETCC.COM`.
- Compile `tools/staging_core_probe_runner.cpp` with Android26 ARM64 clang++,
  `-std=c++17 -O2 -static-libstdc++ -I backend-dos/src/main/cpp -ldl`.
- Copy runner, prototype libdosbox_staging.so, its libc++_shared.so and COM to an
  isolated device directory. Set LD_LIBRARY_PATH to that library directory.
- Runner arguments: absolute library path, absolute config path, isolated config
  directory, installed Staging resources root (parent of `resources/`).
- Config uses output=texturenb, core=normal then core=dynamic, cycles=10000,
  mididevice=none and sbtype=none; autoexec mounts only the isolated directory,
  runs SETCC.COM and exits. Preserve RESULT.BIN as normal.bin/dynamic.bin.
- `tools/verify_setcc_guest_probe.py <capture directory>` verifies both outputs.

The harness audio configuration is for instruction correctness only; performance
comparisons must retain the game's normal sound configuration.
