# Android audio-output policy (#78)

Status: adopted; shared adaptive queue policy measured and tested on RGDS.

## Baseline attribution

The baseline is the #76 APK recorded in #77. A diagnostic build changes only
`classes4.dex`: native core/host, game data and settings remain identical. It adds
two-second aggregate timings around native audio reads and blocking AudioTrack
writes, plus actual buffer/mode, submission/playback-head and underrun counters.
It does not change the baseline buffer policy.

RGDS Android 14 / API 34 negotiates 48 kHz stereo PCM16. AudioTrack reports a
15,392-byte minimum allocation, 4,096-frame capacity/size, performance mode NONE.
That capacity is 85.33 ms, not a measurement of end-to-end latency. AudioFlinger
shows a normal mixer with 960-frame HAL/normal blocks and no FastMixer. Its own
track-latency estimate includes downstream buffering and differs from application
submitted-minus-playback-head counters; neither is an acoustic loopback test.

Baseline verified Duke3D rendered demo: 60-second warmup and 120-second light
capture. 62 complete diagnostic windows cover 126.440 seconds inside the slightly
larger AudioFlinger snapshot span. Native reads take 0.401% of writer wall time;
blocking writes take 91.313%. Read calls average 66.59/s, including 18.82 empty
reads/s, which sleep for 4 ms. Submitted rate is 48,002.1 frames/s. No partial
writes or reported underruns. Maximum observed read 5.90 ms, write 62.52 ms.
A blocking write's wall time is not CPU consumption. The writer is separate from
the emulator worker; #77 also measured its small CPU share.

The Staging mixer produces into its bounded final_output queue. The Android reader
is its only consumer; SDL dummy playback remains paused. The guest/main thread
must not be treated as blocked merely because this independent writer waits.

## Candidate rationale

Kairo98 requests roughly 50 ms effective buffering through AAudio, while allowing
the device to clamp it. Test an analogous 50 ms effective AudioTrack queue limit,
retaining the original allocation. Separately test whether requesting LOW_LATENCY
is actually honored on this hardware. Diagnostic launch extras are temporary and
will not be shipped.

Android documents that `setBufferSizeInFrames` limits writes within the existing
allocation, can be clamped, and can increase underruns when reduced. Record the
actual result rather than assuming the requested size was granted:
https://developer.android.com/reference/android/media/AudioTrack#setBufferSizeInFrames(int)

## Repeated results

| Run | Effective frames | Surface submissions/s | Read calls/s | Empty reads/s | AudioFlinger latency estimate |
| --- | ---: | ---: | ---: | ---: | --- |
| Baseline A1 | 4096 | 31.944 | 66.59 | 18.82 | 178.94 ms mean, 2 endpoint samples |
| Fixed 50 ms B1 | 2400 | 31.582 | 24.59 | 0 | 149.32 ms mean, 15 samples |
| Baseline A2 | 4096 | 30.946 | 67.80 | 19.39 | 162.91 ms mean, 15 samples |
| Adaptive 50 ms B2 | 2400 | 31.640 | 24.59 | 0 | 149.03 ms mean, 15 samples |

All four captures have complete frame histories, zero new AudioFlinger underruns,
zero diagnostic partial writes, and approximately 48,000 submitted PCM frames/s.
Actual rendered start/end screenshots were inspected. No FPS improvement is
claimed: frame results are within observed variation. The adoption rationale is
lower output queuing/latency estimates and less empty polling, not faster emulation.

The repeated baseline's latency samples span 140.00-184.86 ms; the adaptive run
spans 144.61-149.67 ms. Comparing their sample means gives a **13.88 ms reduction**,
with a lower observed upper range. The original 2-point baseline suggests a larger
reduction but is too sparse for the same comparison. This is not a fixed 35 ms
acoustic latency claim. The platform mixer reported zero output gain during these
measurements; PCM was generated/drained, but no listening/loopback claim is made.

LOW_LATENCY was requested in a separate launch with original queue sizing; the
actual mode remained NONE (0), with no FastMixer. The request is not adopted.
It would not substantiate a lower-latency path on this RGDS.

### Observation limits

A1 used the initial timing-only diagnostic APK. B1/A2 used the same APK with
optional queue/mode probes; B2 additionally exercises the actual shared fallback
helper. All native binaries and non-DEX payloads match the original baseline.
All probes aggregate twice per second or slower (timing logs every two seconds).
A1 has only endpoint AudioFlinger dumps; the later runs add roughly ten-second
dumps. These build/observation differences rule out attributing tiny FPS changes
to the policy. They do not explain the actual negotiated queue-size reduction.
The JSON evidence records identities and every latency sample, including low
baseline values rather than selecting only full-queue snapshots.

## Production policy and safety gate

`AudioTrackBufferPolicy` lives in the shared frontend. The DOS AudioTrack adapter
creates one instance per track and calls its check after writes. It requests at
most 50 ms, never enlarges an already smaller buffer, and retains the original
allocation and negotiated size. Once a per-track underrun count increases, it
restores the original size permanently for that track, with a one-second check
cadence. A fresh track can try the smaller queue again. It changes no sample rate,
PCM generation, emulated clock, mixer algorithm, game configuration, or priority.
Kairo98's AAudio implementation and submodule pin are untouched.

The real-device `AudioOutputPolicyFixture` confirmed 4096 -> 2400 frames during
healthy streaming, deliberately stopped feeding for 1.5 seconds, observed a real
underrun, and verified restoration to 4096 and successful pause/resume without
shrinking again. It passed both the diagnostic build and normal APK.

The normal APK contains no diagnostic tags or experimental intent extras, verified
by scanning its DEX payloads. Native core SHA-256 remains
`56c34ad6c88ca81d633c2633c089c674767c1884b64bb91f2012dcf17197a347`.
Normal APK SHA-256:
`5400302d81f89dac4fe1b33ef45a2434ecfd3c5a530f36ba02264fb1e1336ad0`.

Diagnostic source patch: `docs/benchmarks/ticket78-audio-probe.patch` (apply to the
original #76 MainActivity with the shared helper available; never ship the probes).
`tools/summarize_audio_output.py <capture-directory>` summarizes epoch-format
`KairoAudioMeasure` logcat saved as audio-log.txt, using the capture's PID/time window.
Raw captures remain under `.tmp/ticket78`; summarized evidence and hashes are in
`docs/benchmarks/ticket78-audio-comparison.json`.


## Normal-build runtime verification

The installed APK hash was read back and matches the normal build above. The
forced-underrun fixture passed again on that APK. Direct frontend launches of
Doom and Duke3D reached rendered 3D scenes; Cannon Fodder's Sound Blaster choice
reached its animated opening. The Cannon check is an intro/audio smoke test,
not a gameplay/performance claim. Three output snapshots per game show the
2400-frame limit, advancing server-frame counters and no underruns.

Shared helper commit: `ed74ea0` in MrJackSpade/Kairo. Only the two policy hooks,
that shared pin, the fixture and evidence are part of the DOS change. Unrelated
catalog/controller edits remain uncommitted and are excluded from the APK.
The exact policy applies to both distribution channels, with no debug-only
product behavior. The diagnostic patches remain test artifacts only.
