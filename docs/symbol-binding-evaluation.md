# Internal symbol binding (#76)

Status: adopted after the repeated performance comparison and representative
runtime gates below. This is an optimization adoption, not a release-readiness
or universal game-compatibility claim.

## Baseline identity and attribution

The installed RGDS APK was read back by hash before testing:
`4f894466208c6e4109a3e37d5cb677ca80709eae5d3e91c597d51fb4dac731aa`.
Its adopted PGO core matches debug ELF
`c791f5dcd07aea30ac137e8e86b1abb0781a42a686044bfd506dafc7dc5cb7fb`,
build ID `0a7982563ac3bb3f812e85d274e2155b990bdf43`. The recorded simpleperf
mapping independently reports that same build ID.

The core has 6,420 PLT entries: 500 external imports and 5,920 definitions
inside the core (3,956 strong and 1,964 weak). Its PLT occupies 102,752 bytes.
Calls through these entries remain in the actual optimized PGO binary; this
is not inferred from source or from a nonoptimized build.

A verified running Duke 3D rendered demo received a further 60-second warmup,
then a nominal 120-second CPU profile (119.936 seconds recorded, 185,603 samples,
zero reported lost samples). This attribution run is **not** a fresh-launch
performance comparison. Sampled process CPU time was 144.788 seconds; core ELF
CPU time was 77.778 seconds, excluding generated guest code outside that ELF.
PLT instructions accounted for 3.313 CPU seconds: 2.798 internal (2.505 strong,
0.293 weak) and 0.515 external. Internal examples include `get_ZF`, `get_OF`, `get_CF`, `get_SF`,
`mem_writed` and DMA helpers. Sampling skid and indirect cache/predictor costs
prevent interpreting this as an exact removable budget or a promised FPS gain.

Conservatively recognized GOT loads also received samples, especially `lflags`,
`paging`, `Segs` and `cpu_regs`. Their attributed cost is smaller. Data-symbol
binding is not changed in the first candidate; object identity and interposition
would need their own justification before a broader policy.

## Isolated candidate

Relink the preserved PGO objects with
`-Wl,-Bsymbolic-non-weak-functions`. No recompile, LTO, guest configuration,
PGO-profile, host-library or frontend change is included. The baseline relink
was independently compared byte-for-byte with the preserved ELF before the
candidate was allowed. An initial immediate hash assertion failed; subsequent
section, build-ID and full-byte comparisons all matched. That failed attempt
was not used as a candidate comparison.

The candidate removes all 3,956 strong internal PLT entries. All 1,964 weak
internal entries and 500 external entries remain; PLT size becomes 39,456 bytes.
The dynamic symbol names/types/bindings/visibility/defined status are identical.
Relocation multisets for objects, weak symbols and named external imports are
identical. Strong function-address relocations may bind locally, as expected.

The host uses `dlopen(..., RTLD_NOW | RTLD_LOCAL)` and resolves the seven C bridge
functions through `dlsym`. The bridge exchanges POD values and callbacks, not C++
RTTI or exception objects. The candidate keeps those exports and preserves weak
and data binding. No defined strong-function names overlap the packaged host or
shared C++ runtime. Static inspection does not independently prove every possible
runtime identity/exception path; the representative lifecycle checks below
exercise loading, callbacks, reset, repeated sessions and teardown.

The candidate APK keeps the existing package/signing identity, and ZIP payload
comparison permits exactly one changed entry:
`lib/arm64-v8a/libdosbox_staging.so`.

- Candidate APK: `17fb992f3c47c06cfb247a019a4bdaa13e04ade75526399fb342be9681c551ff`
- Debug ELF: `c115a28683e18f4f7898d6732277bb1ee2caca8d59033d1f0a6852ea590becc8`
- Packaged ELF: `56c34ad6c88ca81d633c2633c089c674767c1884b64bb91f2012dcf17197a347`

## Performance gate

Fresh-launch Duke 3D demo, audio enabled, unchanged 320x200 configuration;
60-second warmup followed by 120-second light-observer captures, repeated
baseline/candidate runs. Report surface submissions, not unique guest FPS.

- Launch configuration SHA256:
  `c343313b75d2d0c3805cff73b4956971de800c27a88bebb8a5b635e52485c39f`
- Game configuration SHA256:
  `1999e2a9be9ed2094649bd511d71c1217cd39fe33dc45dec6b5abafcb3a5fba5`

Kairo98 and unrelated controller/catalog work are untouched.

Fresh-launch A-B-B-A results:

| Run | Build | Surface submissions/s |
| --- | --- | ---: |
| A1 | Baseline | 30.7146 |
| B1 | Bound strong functions | 32.8594 |
| B2 | Bound strong functions | 32.7324 |
| A2 | Baseline | 29.5500 |

Means: **30.1323 -> 32.7959 (+8.8398%)**. Both candidates exceed both baselines.
All four had complete presentation histories, rendered-demo endpoint screenshots,
60.000-second warmups and zero reported AudioFlinger underrun increments.
Observed SoC temperatures were 67.5-69.375 C; sampled clock readouts were identical
across runs and recorded cooling states were zero. These are sparse observations,
not proof of constant clocks throughout every interval. The baseline spread,
small sample count and demo timing limit precision; this is not a universal FPS
promise. Linker layout changes accompany PLT removal, so the measured gain cannot
be assigned exclusively to time spent executing the removed stub instructions.

The normal Gradle build completed in 31 seconds with default PGO enabled. Its
core is byte-identical to the measured candidate, and all non-signature APK
payloads match. Normal-build APK:
`213bf68a69eea378dd616c6d54964f53a982491bb786117d57a5b54ee9ab5cc8`.
It is the artifact used for the runtime gates below.

## Runtime gates and decision

The installed normal-build APK and test runner hashes were checked before each
native fixture. Doom produced 1,098 frames and 3,490,816 PCM frames; Cannon Fodder
produced 807 frames and 1,872,896 PCM frames, both with nonzero audio peaks.
Both passed default dynarec execution, reset, second launch in the same process,
pause/resume, surface detach/reattach, paused stop and JIT mapping cleanup.
Doom also exercised upstream `core=auto` entering protected mode on its second
launch. Doom's screenshot shows gameplay; Cannon's shows its opening cinematic,
so the latter is a boot/lifecycle check, not a gameplay-performance claim.

Quake's original single-player build reached its rendered demo and a new game's
3D start room. Movement inputs changed the player view, and a subsequent direct
launch successfully switched to Duke. An initial Exit selection only opened its
confirmation dialog; the earlier map-name grep was insufficient to prove unload
because Android can map native libraries under the APK pathname. The confirmation
was completed separately. This exercises a different 3D/FPU workload, not a
Quake benchmark. The Doom/Cannon fixtures provide the explicit JIT cleanup gates.

Adopt `-Wl,-Bsymbolic-non-weak-functions` on the DOS core target in all build
types/channels. The measured benefit repeats; no core regression was observed
in these workloads. Do not substitute broad `-Bsymbolic`: weak functions, C++
type/object data and external imports retain their prior binding policy.
No game data, guest timing, emulated hardware, rendering or audio configuration
was changed. The host/core remain separate libraries. This is a DOS core symbol
policy, so no shared frontend build mechanism or Kairo98 modification is needed.

The normal-build APK remains installed over `com.loxifi.kairodos` on the RGDS.
The installed file's hash was read back and matches the Gradle artifact above.
After the confirmed exit, direct launch returned to Duke's unchanged 320x200
copy and its rendered demo was visually verified. No experimental diagnostic
APK is left installed.

## Evidence and reproduction

- [CPU attribution](benchmarks/ticket76-baseline-attribution.json)
- [Candidate identity](benchmarks/ticket76-candidate-identity.json)
- [Static binding comparison](benchmarks/ticket76-binding-comparison.json)
- [Four-run comparison](benchmarks/ticket76-duke-comparison.json)
- [Normal Gradle build identity](benchmarks/ticket76-gradle-identity.json)
- [Runtime gates and separate frontend finding](benchmarks/ticket76-runtime-checks.json)

`tools/audit_symbol_binding.py BASELINE_ELF CANDIDATE_ELF --output RESULT.json`
compares dynamic symbols, dependencies and object/weak/external relocations.
`tools/capture_dos_demo.py` with `--observation light` and the verified launch
prompt supplies each 60s+120s run; `tools/summarize_dos_demo.py RUN --clock-ticks 100`
checks frame-history completeness and audio-track continuity on this device.
Raw local captures, symbols, disassembly and link commands are retained under
`.tmp/ticket76/`. No game assets are included in the versioned evidence.

## Separate frontend finding

Library search triggered an input-dispatch ANR while selecting Quake, and a
fresh-process retry reproduced it. The main-thread Java stack enters
`SecondaryDisplayContent.setAppearance` -> `TextView.setText` -> measured text
layout, through `LibraryScreen.applyFilter`/`notifySelection`. Both failures
preceded game launch in a fresh app process; source inspection shows the host
loads the core only when starting an emulation session. The frontend and host
payloads are byte-identical to the baseline. This supports treating the search
failure as separate from core binding, but is not a full diagnosis of the UI
stall. The ART JNI-library inventory and a grep for a soname in process maps
are not sufficient to prove absence of a library loaded directly from an APK.

The existing `ACTION_VIEW` game-URI interface bypassed search and launched
Quake successfully. `AGENTS.md` now records this direct-launch procedure.
The search/layout issue still needs its own investigation; no shared UI or
Kairo98 change is bundled into this performance ticket. Full local traces are
retained as `.tmp/ticket76/anr-trace.txt`, `lastanr.txt`, `anr-logcat.txt` and
`retry-lastanr.txt`.
