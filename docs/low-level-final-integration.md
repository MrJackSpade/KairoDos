# Low-level performance sweep: final integration

## Scope

This sweep evaluates generic DOSBox Staging changes using Duke3D as the fixed
benchmark. It preserves the game files, 320x200 resolution, sound, dynamic core
and automatic-cycle configuration. Kairo98 and the shared frontend are unchanged
by this investigation. The normal APK is built from an isolated committed source
snapshot so concurrent catalog/controller work cannot alter the comparison.

## Changes and rejected experiments

The retained core changes are:

1. Local binding for the core-owned paging object.
2. NEON execution of independent Speex interpolation accumulators, preserving
   exact output and retaining the original oversample-one path.
3. Jcc helpers specialized for a single known live CMP/TEST producer.
4. Local binding for the core-owned lazy-flag object.

Checked-read register returns, cached block-code entries, fixed-size literal
calls and runtime flag-mask tables failed their normal-build performance gates
and are not included. Earlier failed inline-read and compact-call changes remain
excluded. The [candidate ledger](low-level-performance-investigation.md) and
[current profile](low-level-final-profile.md) record the evidence-based exclusions
for the remaining targets. A larger register allocator or startup code-placement
redesign remains untested; it is not represented as a completed optimization.

## Final comparison

The [machine-readable report](benchmarks/low-level-final-comparison.json) retains
complete per-run summaries and exact artifact identities. Timing uses normal PGO, no guest logging,
fresh launches, 60 seconds of warmup, and 120 seconds of rendered-demo capture.
Surface submissions are not necessarily unique guest frames. The isolated gains
must not be added together.

| Ordered capture | Original core | Paging + audio + known conditions | Also local lflags |
| --- | ---: | ---: | ---: |
| A1 | 34.1788 | | |
| B1 | | 34.6716 | |
| C1 | | | 35.1227 |
| C2 | | | 36.7586 |
| B2 | | 34.3310 | |
| A2 | 33.7820 | | |
| Mean submissions/sec | **33.9804** | **34.5013** | **35.9406** |

The final observed change is **+5.77%** against the matched original core.
Before local lflags binding, the integrated gain is only **+1.53%**, substantially
less than the isolated candidate gains. Adding local lflags measures **+4.17%**
over that combined build. Process CPU utilization averages 120.893% originally
and 117.850% finally (about **2.52% less CPU**, with 100% representing one core).
Mixer utilization is reported separately in the full summaries.

Both final observations exceed both original observations, but two runs per
condition and differing demo phases limit precision. All six captures have
complete histories, unchanged APK/configuration identities within each run,
rendered endpoints, and zero recorded AudioFlinger underrun increments. This
does not measure unique guest FPS or prove audio sample correctness by itself.

## Correctness and deployment

Meaningful checks throughout the sweep include 1,049,600 corrected read-path
probe cases, 32,768 generated-link checks, 126,720 literal-call checks, 5,242,880
flag-mask comparisons, and 2,752,512 known-condition comparisons. These checks
also apply to rejected experiments and are not claims that those changes shipped.
The resampler comparison checks 1,839,645 bit-identical samples for each of float
and integer APIs across 220 cases, including rate changes and resets. Separate
instrumentation verifies live CMP/TEST producer identity in Duke, Doom and
Cannon Fodder; it is disabled in the normal APK.

The actual final normal artifact passes both Doom and Cannon lifecycle checks.
Doom records 1,093 rendered frames and 3,546,112 PCM frames; Cannon records 813
rendered frames and 1,876,992 PCM frames. Both have nonzero audio peaks. These fixtures
exercise generated execution, audio, pause, display recreation, reset, repeated
sessions, paused exit and JIT mapping release. Doom renders gameplay; Cannon
renders its animated opening. They do not prove compatibility with every DOS game.

The final APK is installed over `com.loxifi.kairodos` on the RGDS using the normal
streaming update path. Its verified SHA-256 is
`7bb57809dd2949a8c6ed7284352dd2f06633c9414d00a9c54b55d44df30427bc`.
It is the exact full Gradle normal APK used for C1/C2 and the final fixtures,
not a profiling or assertion build. Source-content verification confirms that
the adopted header matches its recorded build input; the rest of the core is
the committed `99c4c766` baseline. [Deployment and source evidence](benchmarks/low-level-final-deployment.json)
retain the exact identities and fixture results. The preserved game configuration
hash remains `1999e2a9be9ed2094649bd511d71c1217cd39fe33dc45dec6b5abafcb3a5fba5`.
