# DOSBox Staging ARM64 profile

Default builds use the frozen native-code profile measured in
[#70](https://github.com/MrJackSpade/KairoDos/issues/70) and validated in
[#75](https://github.com/MrJackSpade/KairoDos/issues/75). PGO is **on by
default** for both distribution channels; use `-Ppgo=false` for the
unprofiled comparator or diagnostic/alternative-core builds. No generation
runtime is included.

`arm64-v1.proftext` contains LLVM function names, CFG hashes, execution counts
and value-profile records. It contains no guest binaries, game assets or
writable game drives. `profile.json` records its compiler, source, input
hashes and provenance. All material needed to consume this profile is in the
repository; proprietary games are not needed to build the app.

## Frozen-data conversion

The indexed input was exported with the NDK's `llvm-profdata`:

```text
llvm-profdata merge --text training.profdata -o original.proftext
```

The literal `M:/` source prefix was replaced with `@KAIRO_SOURCE@/`, and text
newlines were canonicalized to LF with one final newline for stable Git hashes.
No counts, CFG hashes, weights or training inputs were changed. At configure
time `PrepareProfile.cmake` substitutes the actual checkout prefix and uses
the selected NDK's `llvm-profdata merge` to recreate the indexed file. LLVM
therefore updates named indirect-call target GUIDs consistently with the
relocated function names. Do not rewrite numeric target GUIDs manually.

The conversion was checked by exporting the rebuilt indexed files again:
both original-root and relocated-root text matched the original exactly
after reversing only the path substitution. CPU and VGA-drawing translation
units also produced identical optimized LLVM IR after accounting for expected
path and indirect-target GUID changes; deliberately unrelocated profiles lost
static-function metadata. The Windows compilation database spells source
arguments differently from Ninja; compiler probes must use Ninja's actual
forward-slash source spelling.

The helper verifies the source-controlled text hash and recorded Clang
version. Profile and checkout hashes are part of the generated profile path
so changed inputs invalidate compiler commands. Unchanged indexed data is
reused. Out-of-date function profiles are build errors. Diagnostic/alternative
core builds must disable PGO instead of silently consuming an incompatible
profile.

## Limits

See [the evaluation report](../../docs/pgo-evaluation.md) for the full-session
training mix, non-atomic counter limitation, rejected attempts, observer
overhead and held-out results. Do not retrain or select profiles using those
held-out results. Cross-host CI verification, actual-game/native regression
checks and final-binary comparisons are recorded in
[the adoption report](../../docs/pgo-adoption.md). These checks establish no
observed regression in the covered workloads, not universal compatibility.
