# Software DOS frame presentation evaluation

Ticket: [KairoDos #42](https://github.com/MrJackSpade/KairoDos/issues/42).

## Decision

Keep software presentation as the default for ordinary DOS games. The existing
optional GLES path is useful for the emulated 3dfx renderer, but these RGDS
measurements do not establish an overall performance or latency advantage for
presenting ordinary CPU-rendered frames through GLES.

No guest renderer, executable, original archive, user configuration or save was
modified for this evaluation. No game data or screenshots are distributed here.

## What was compared

The software path converts XRGB8888 to Android RGBA on the emulation thread,
then hands the latest frame to an independent renderer. That renderer copies
rows into an `ANativeWindow` buffer and posts it. Its panel wait stays off the
guest execution thread.

The existing hardware option already provides a texture-upload prototype for
ordinary DOS frames: DOSBox Pure uploads its CPU pixels and draws them into the
host framebuffer. The shared `GpuFrameQueue` makes a fenced GPU snapshot and
presents the newest queued frame on another thread. It does not turn Doom or
Duke's guest renderer into a GPU renderer. `hardwarePublish` measures the host
snapshot submission, **not** all texture-upload work or completed GPU execution;
`coreRun` includes the core's upload/draw work in this mode.

Software negotiation remains available when the GLES initialization is
unsupported. The global default and per-game override are unchanged. Actual
driver context loss still requires relaunching with Software selected; see
[the hardware host and its lifecycle checks](hardware-rendering.md).

## Method and limitations

- RGDS, arm64, Android 14; 640 × 480 game display, approximately 60 Hz.
  Retroid testing was deferred at the user's request.
- Unchanged user-installed Doom and Duke Nukem 3D archives were copied into
  isolated private test cache. Duke used the archive's supplied SB16 profile,
  matching the normal app's preparation. Each run had fresh private system/save
  directories and a fresh Android surface, matching normal game sessions.
- Software/GLES/GLES/software order, 12 seconds of warmup followed by 30 seconds
  of observation per run. Native attract sequences exercise real guest
  rendering, but loading and demo phase can differ; these are not synchronized
  frame-by-frame comparisons or a timedemo score.
- Final optimized measurements use `-O2 -DNDEBUG`, verified against the release
  host's generated RelWithDebInfo compiler command. Instrumentation is in a
  Debug APK so private fixtures/JNI are available. The copied emulator core is
  identical in both modes. Profiling is disabled outside the fixture.
- Measurements include conversion, row copy, mutex/ANativeWindow waits,
  post time, host GPU publication and `core.run`. These are CPU wall durations;
  profiling overhead is included. Release has no profiling storage/JNI.
- Android SurfaceFlinger actual presentation timestamps measure buffer cadence,
  not unique game frames or simulation speed. Its bounded history can miss the
  end of a run when the surface disappears; observed spans and counts are
  retained. The analyzer rejects comparisons without substantial real presents.
- Android buffer ready-to-present time compares compositor latency. It excludes
  earlier guest/host queue time and is **not input-to-photon latency**. Software
  callback-to-post age is also recorded; there is no matching internal GPU
  age measurement, so the report makes no full end-to-end latency claim.
- Native pending-frame replacements are exact for the software mailbox. GPU
  mailbox replacements are not instrumented; its replacement fields are null.
  Approximately 70 Hz guest output into a 60 Hz panel necessarily omits some
  callbacks from display. That does not imply the guest runs slower.
- Frequent observations record process CPU ticks, SoC/GPU temperatures and
  thermal cooling states. CPU percentages are relative to one CPU, using the
  RGDS's verified 100 Hz process accounting. They include the fixture process
  and should not be interpreted as a percentage of all cores.
- The device was charging. Battery current cannot establish app power use;
  no power-saving claim is supported. The fixture emulates guest sound but
  does not run the app's AudioTrack consumer, so total app/audio performance is
  outside this comparison. Short observations are not a thermal soak test.

SurfaceFlinger's field order is verified against the Android source:
[FrameTracker::dumpStats](https://android.googlesource.com/platform/frameworks/native/+/cdb6b16dec3a541b455be99d075004cb2f0a0cd7/services/surfaceflinger/FrameTracker.cpp).

## Results

All eight final runs produced substantial real Android presentations, with no
EGL attachment errors. The [numerical record](benchmarks/rgds-video-presentation-20260930.json)
includes raw native summaries, observation coverage and derived statistics.
`CPU %` below is the approximate process use relative to one CPU.

| Game / source size | Run / mode | Callbacks/s | Presents/s | Interval p95 ms | CPU % | Android ready→present p50 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Doom / 320 × 200 | 0 / software | 70.10 | 60.05 | 16.74 | 97.5 | 26.32 |
| Doom / 320 × 200 | 1 / GLES | 70.10 | 60.05 | 16.72 | 117.9 | 36.01 |
| Doom / 320 × 200 | 2 / GLES | 70.10 | 60.05 | 16.73 | 118.2 | 29.22 |
| Doom / 320 × 200 | 3 / software | 70.10 | 60.05 | 16.74 | 98.7 | 38.41 |
| Duke / 1024 × 768 | 0 / software | 69.60 | 59.75 | 16.84 | 122.0 | 33.73 |
| Duke / 1024 × 768 | 1 / GLES | 69.13 | 59.69 | 16.84 | 134.2 | 28.73 |
| Duke / 1024 × 768 | 2 / GLES | 69.66 | 59.82 | 16.85 | 138.0 | 23.90 |
| Duke / 1024 × 768 | 3 / software | 69.53 | 59.69 | 16.88 | 120.8 | 30.65 |

Stage ranges below are the two runs of each mode, using **mean CPU wall time**.

| Game | Software conversion ms | Software row copy ms | GLES snapshot submission ms | Core.run software / GLES ms |
| --- | ---: | ---: | ---: | ---: |
| Doom | 0.147–0.149 | 0.130–0.137 | 0.589–0.641 | 1.04–1.32 / 2.68–3.12 |
| Duke | 2.00–2.04 | 2.01–2.28 | 1.10–1.18 | 7.90–8.87 / 11.13–11.76 |

Software callback-to-post median age was 23.76–23.90 ms for Doom and
22.67–23.10 ms for Duke. Doom's software window lock usually waited roughly a
panel refresh on the separate renderer thread; it did not slow the guest's
70 Hz callbacks. Software pending replacements were approximately 14% of
callbacks, consistent with the source/display rate mismatch. GPU replacement
counts are not available and are not reported as zero.

The paths had effectively the same panel cadence. GLES increased observed
process CPU use by about 20 percentage points for Doom and 13–17 for Duke.
Duke's measured compositor delay was lower with GLES, while Doom's varied in
both modes; this does not establish a consistent full-pipeline latency win.
SoC temperatures ranged from 48.3–57.2 °C in the Doom set and 50.0–61.7 °C in
the Duke set, with no nonzero thermal cooling state recorded. Charging, test
order and short duration prevent a power or long-term thermal conclusion.

Real captures showed correctly oriented gameplay, HUDs, colors and geometry.
The GLES shader uses nearest texel sampling; Android scales the software surface
with its own filtering, making its low-resolution Doom output visibly smoother.
Attract sequences were not phase-aligned, so these are visual smoke comparisons,
not pixel-identical fidelity assertions. Existing synthetic GLES orientation,
color and lifecycle fixtures provide the deterministic checks.

### Follow-up findings

The unoptimized normal Debug host took about 1.0 ms to convert Doom frames and
12.8–13.0 ms for Duke, compared with 0.15 and 2.0 ms above. This identifies an
avoidable native build cost rather than a reason to enable GLES by default.
[Issue #51](https://github.com/MrJackSpade/KairoDos/issues/51) tracks matching
Kairo98's existing optimized native Debug behavior through shared defaults.
These stage timings do not imply a corresponding whole-game speedup.

Code inspection also found that `GpuFrameQueue::render` leaves older Queued
slots available after choosing the newest snapshot. It can subsequently select
a lower serial if production pauses. [Issue #52](https://github.com/MrJackSpade/KairoDos/issues/52)
tracks a real-EGL regression check and shared queue fix; serial reversal was
not measured in this performance fixture. This is another reason to retain
software as the ordinary-game default while the optional GPU path is refined.

## Reproduction

Build from a drive alias on Windows, following [build-windows.md](build-windows.md):

```text
gradlew :kairodos:assembleDebug :kairodos:assembleDebugAndroidTest -PpresentationProfile=true
```

Install both APKs as updates, then run the observation script and instrumentation
concurrently. Use an actual user-installed archive path; nothing is downloaded
by this fixture.

```powershell
./tools/ObserveDosPresentation.ps1 -Serial '<device>' -Output '<new observations.jsonl>' -Seconds 205
```

```text
adb -s <device> shell am instrument -w -e presentationGame doom -e presentationArchive "/path/to/installed/Doom.zip" -e presentationSeconds 30 com.loxifi.kairodos.test/com.mrjackspade.kairodos.CatalogUpdateInstrumentation
```

Use `presentationGame duke` for Duke. Pull
`/data/user/0/com.loxifi.kairodos/cache/video-presentation-reports/<game>.json`
and analyze it with:

```text
python tools/analyze_dos_presentation.py <game.json> <observations.jsonl> --output <analysis.json>
```

The observer currently targets RGDS sysfs paths and requires permission to read
  the app's process accounting. It must be adapted and verified on other devices.
The fixture removes only its own private game/system/save cache after the core
stops; reports remain in its separate cache directory. Inspect retained fixture
cache after an interrupted run before retrying. Restore a normal build without
`-PpresentationProfile=true` after measurement.

## Discarded measurements

An early fixture reused a software-connected Android surface for EGL. Android
rejected its second API connection with `EGL_BAD_ALLOC`; the previous software
frame remained visible. Those GPU timings and matching captures were discarded.
Normal `showGame`/`showLibrary` creates/removes game surfaces; the fixed fixture
does the same and asserts surface destruction between runs. Preliminary `-O3`
measurements were also excluded from the final tables after verifying that the
release host uses `-O2`.
One Duke capture also overflowed SurfaceFlinger's bounded history during slow
host sampling; it was recollected with a single-request observer. The analyzer
rejects observation gaps longer than that history can retain.
