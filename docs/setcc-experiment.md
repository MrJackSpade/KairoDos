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

## Extended correctness checkpoint

Twelve actual-core comparison suites passed, totaling **122,880 comparisons**:
materialized flags and lazy CMP32 with no prefix, 66, 67 and 66+67; plus lazy CMP32
followed by an XOR overwriting flags for those four prefix combinations. Every
suite matched complete interpreter snapshots and the independent oracle.
The lazy operand vector has eight distinct values repeated eight times; these
counts are comparisons, not that many unique input combinations. XOR's undefined
AF is excluded from the independent oracle in overwrite suites (whole snapshots
still matched the interpreter).

A further **2,048 self-modifying-code checks** matched both interpreter and oracle.
SETcc changes an immediate in an already translated function and in the current
block; the following execution must see 0 or 1, never the original 0x7f. These
exercise existing invalidation paths. This is not a complete paging/fault test.

Results: [extended evidence](benchmarks/ticket72-setcc-extended.json).
`tools/setcc_smc_probe.s` contains the exact synthetic source; assemble/link and
run it using the same harness procedure. Its output ordering is 64 flag combinations,
16 conditions, then external-target/current-block results. The guest program
contains no proprietary code. All runs used the same prototype core as the initial
checkpoint. No APK was installed and production source remains unchanged.

Protected mode, default 32-bit code, fault handling, additional effective-address
forms and the A/B/A performance gate remain open. The prefixes tested here operate
in 16-bit real-mode code; they are not a substitute for those remaining checks.

## Performance checkpoint

Five sound-enabled actual-app runs are recorded in [the performance report](setcc-performance.md). Both prototype runs exceeded the baseline runs, but the size of the gain remains uncertain. The original APK was restored. #72 remains open for the remaining correctness gates; no production adoption.
