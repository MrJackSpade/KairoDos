# PGO implementation checkpoint (#75)

Status: opt-in integration built; default remains off. Linux CI, wider runtime
regressions and measurements of the final implementation are still pending.
The normal non-PGO app remains on RGDS. This is not an adoption or release claim.

## Portable frozen data

The measured profile from #70 is now represented by
`backend-dos/pgo/arm64-v1.proftext` and its manifest. Its 13,861 function records
retain the original CFG hashes, counters and value profiles. Only 3,332 literal
`M:/` occurrences became a canonical checkout marker. The checked-in artifact
uses LF with one final newline so Git cannot invalidate the manifest hash.
An indexed round-trip after newline normalization reproduced the original
LLVM text export exactly. LLVM reconstructs the
indexed profile using the current root, including consistent indirect-target
GUIDs. Exact export/reimport checks passed at original and relocated roots.

## Actual compiler matching

The CPU and VGA-drawing translation units were compiled as optimized LLVM IR
at `M:/` and `N:/`, using the same frozen counts. With relocated profiles,
their IR is identical after normalizing the expected paths and MD5-derived
indirect-call target IDs. A deliberately unrelocated profile at `N:/` loses
metadata:

| Unit | Correct entry-count / branch-weight records | Wrong-root records |
| --- | ---: | ---: |
| CPU | 57 / 174 | 55 / 168 |
| VGA draw | 15 / 91 | 9 / 38 |

An initial probe used the compilation database's backslash source argument,
which differs from Ninja's forward-slash command on Windows. That probe did
not test the actual static-function matching and was rejected. Corrected
probes use the source spelling verified with `ninja -t commands`.

## Full relocated build

The isolated frontend/core source from `fda400fe`, shared `9be507e`, plus the
opt-in integration built under `N:/` in 4m39s. All 433 native compile commands
contain profile use, no generation instrumentation and no LTO. Stale-function
profiles are errors; the build succeeded without them. The APK is
`4f894466208c6e4109a3e37d5cb677ca80709eae5d3e91c597d51fb4dac731aa`.

Relative to the measured #70 candidate, only the core and host shared-library
payloads changed outside signing metadata. The host `.text` is byte-identical.
Core `.text` remains 5,956,324 bytes: all 1,489,076 instruction addresses and
mnemonics match. Operand differences comprise 20,138 `add` and 2,999 `adrp`
instructions. This supports relocation equivalence but does not independently
prove that every relocated data target is correct; device regression gates
are still required. Do not describe the complete core as byte-identical.

## Build guard checks

The final helper uses transactional indexed-file creation. A standalone CMake
fixture exercised successful generation, unchanged-cache reuse, rejection of
diagnostic core settings, and rejection of a modified text profile even when
an older indexed cache exists. The APK above predates this cache-only
transactional adjustment; the final helper was separately exercised afterward.
The APK also predates text-newline canonicalization, whose lossless LLVM
round-trip and final-artifact configure guards were verified separately.

The profile/compiler identity checks apply only when PGO is requested. The
normal comparator remains available with `-Ppgo=false`. Source and checkout
hashes are included in the generated profile's flag path to invalidate object
builds when those inputs change. Unchanged profiles are not regenerated.

Evidence: [portability checkpoint](benchmarks/ticket75-portability.json).
Implementation notes: [profile README](../backend-dos/pgo/README.md).

## Remaining gates

- Verify matching and build behavior on the supported Linux CI host.
- Exercise native/compiler-wide regression fixtures and actual gameplay
  beyond the limited opening-room comparison in #70.
- Measure the portable implementation on RGDS with matching baseline,
  configuration identities, verified scenes and the established capture protocol.
- Enable the same default for all build channels only after those gates pass;
  commit/push, update the existing app, and close #75 with the evidence.
