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

Built `:kairodos:assembleDebug` from a clean isolated checkout of `fda400fe`
with pinned shared `9be507e`. The build completed successfully in 2m 44s.
Its generated CMake cache confirms worker ON and comparator, stress and guest
profiling OFF; verification/queue log markers are absent from the packaged
core. Unrelated catalog/controller workspace changes were excluded.

Installed over the existing `com.loxifi.kairodos` on RGDS via Wi-Fi using
`InstallRgDs.ps1`. The installed APK hash matched the artifact:

`c4e58b4f12d9a8414d91bf97ee474ad7dc036e0d4de38761c089e0b5e7e62a39`

Duke's existing 320x200 configuration booted to the rendered 3D demo.
The runtime log confirmed OPL3 worker initialization. Normal menu exit then
completed shutdown after 127,693 commands and 1,159,483 worker-generated
frames, with no fatal exception or verification instrumentation in the log.
This final run is a boot/exit smoke test, not another timed performance pair.

The adopted normal APK remains installed; no experimental/comparator APK was
left behind. Doom's original configuration and GUS selection were restored
and their hashes verified before deployment. Kairo98 was not changed.
Artifact, build and deployment evidence hashes are in
[the adoption record](benchmarks/ticket74-adoption.json). The implementation,
evaluation and deployment requirements for #74 are complete.
