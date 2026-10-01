# Ordinary OPL worker adoption (#74)

The default build now enables ordered OPL port-write catch-up synthesis for
OPL2, dual OPL2 and OPL3. ESFM and AdLib Gold retain synchronous synthesis.
The imported Nuked engine, sample rate, DC behavior, timer/status handling,
guest timing, game files and Kairo98 behavior are unchanged by the runtime fix.
Reusable bounded worker infrastructure remains in shared; Staging timing and
chip ownership remain in the DOS core.

## Evidence and limits

- [Independent correctness, modes, saturated queues and lifecycle](opl-worker-prototype.md).
- [Repeated Duke comparisons and observer sensitivity](benchmarks/ticket74-observer-overhead.md).
- [Second workload and reduced collector frequency](benchmarks/ticket74-doom-performance.md).

Duke's two light-observer pairs averaged 26.760 -> 30.677 surface submissions/s
(+14.64%). Doom showed +3.18% and +2.13% at two polling frequencies, with no
observed audio-underrun increase. These are instrumented presentation counts,
not unique guest FPS or a guaranteed gain across games. Total CPU increases;
power was not measured. No observed correctness regression is not a proof
against every possible workload.

The comparator and stress mode remain disabled and Debug-only. Normal builds
also omit queue statistics. `-PoplWorker=false` is the diagnostic reference
build override; it is not an exposed application setting. Both distribution
channels use the same default behavior.

## Deployment

Pending the normal build and RGDS smoke test. Do not close #74 or claim this
version installed until its APK hash and device result are recorded here.
