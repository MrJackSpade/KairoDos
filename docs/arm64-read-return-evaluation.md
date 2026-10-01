# ARM64 checked-read register returns

Decision: **reject this candidate**. No improvement was demonstrated by the
normal-PGO A/B/A comparison. Continue the remaining low-level investigation.

## Hypothesis and implementation

Cached checked reads store their result in `core_dynrec.readdata`, return a fault
boolean, and generated code reloads the result. The experiment returns a two-word
value/fault pair in x0/x1 instead. Cached reads avoid the scratch store/reload;
handler and page-crossing paths retain the original checked helpers. Other
architectures and all writes retain their original paths.

The corrected prototype explicitly truncates dword slow results to 32 bits and
keeps slow conversion out of line. Exact ELF inspection confirms that cached
byte/word/dword reads have neither scratch-result stores nor stack saves.

## Correctness findings

The initial 524,800-case probe used a 32-bit scratch stub and a distinct result
register. Inspection of the actual compiled core exposed an important mismatch:
the real scratch slot is pointer-sized, and a destination already in w0 elides
the move. The expanded probe reproduced stale upper bits in the first prototype.
That prototype's capture is excluded; a short native probe also ran during it.

The corrected probe uses the real `Bitu` width, nonzero upper scratch bits and
both same/distinct result registers. **1,049,600 generated-read checks passed** on
RGDS, covering byte/word/dword values, cached and handler paths, page crossings,
partial faults, wrapping addresses and fault-destination preservation. It
extracts the actual candidate helper and emitter code. Its simulated handlers
do not establish full-core paging or all-game compatibility.

## Normal-build comparison

The reference and corrected candidate were built in the same isolated tree with
PGO enabled. The existing profile-matching gate passed unchanged. Frozen APKs
differ only in `libdosbox_staging.so`; frontend payloads and signing identity
match. Installed APK and Duke configuration hashes match before/after each run.

Each fresh direct launch uses the unchanged 320x200, sound-enabled game, a
60-second warmup and a 120-second rendered-demo capture with light observation.

| Run | Surface submissions/s |
| --- | ---: |
| Reference A1 | 32.4212 |
| Corrected candidate B | 32.6942 |
| Reference A2 | 34.3185 |

Reference mean: 33.3698. Candidate: 32.6942, an observed **-2.02%** versus that
mean. One candidate bracketed by two references does not establish an exact
regression, but its small advantage over A1 disappears against A2. It fails the
adoption gate. These are submissions, not unique guest FPS. All three frame
histories are complete and recorded AudioFlinger underrun deltas are zero.

No production source change is adopted. The isolated decoder is restored and
the reference APK is back on RGDS. Further full-core fault/SMC and representative
game adoption tests are unnecessary for this rejected design; they remain
required for any candidate that passes performance gates.

## Reproduction

Apply [the corrected experimental patch](benchmarks/arm64-read-return-prototype.patch)
to a separate tree based on `db9a4272` (the production decoder is unchanged from
the preceding baseline). Generate the standalone probe:

```text
python tools/generate_arm64_read_return_probe.py <patched-source-root> <probe.cpp>
aarch64-linux-android26-clang++ -std=c++17 -O2 -static-libstdc++ <probe.cpp> -o <probe>
```

Run the binary on ARM64 Android. Preserve corresponding source and original
third-party notices. The patch is research evidence, not a production fix.
[Machine-readable evidence](benchmarks/arm64-read-return-evaluation.json) includes
frozen build identities, captures, exclusions and probe hashes. Raw local data
remain under `.tmp/lowlevel-sweep/`.
