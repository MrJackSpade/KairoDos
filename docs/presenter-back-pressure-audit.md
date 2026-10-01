# Ticket #64: presenter back-pressure audit

**Complete: no production change warranted.** Controlled display stalls did not
propagate to the producer through the tested frame/surface locks. No queue
behavior was changed. #52's queued-frame correctness question remains separate.

## Ownership and locks in the actual host

| Operation | Lock and buffer ownership |
| --- | --- |
| Core video callback | Takes `frame_mutex`, writes/converts into `pending_frame`, updates its metadata, and notifies the worker. It does not acquire `surface_mutex`. |
| Presenter dequeue | Takes `frame_mutex`, swaps `frame` and `pending_frame` vector ownership, copies dimensions/timestamp, clears pending status, then releases the mutex. |
| Waiting for another frame | Condition-variable wait releases `frame_mutex` while asleep. |
| Native-window wait/copy/post | Runs after the frame mutex scope has ended, holding `surface_mutex` and its own `frame` vector. Producer writes into the other vector. |
| No surface | Worker records `NoSurface` and continues after dequeuing; no surface wait is held under `frame_mutex`. |
| Surface replacement | `nativeSetSurface` takes `surface_mutex`; it can wait for presentation, but the core callback does not take that mutex. |

This is a source-based prediction of low producer blocking, not sufficient
measurement by itself. Producer allocation/conversion costs and CPU/memory
contention are distinct from waiting for display submission through these locks.

## Controlled observation

The debug fixture accepts `presentationSurface=normal|delayed|unavailable`.
`delayed` injects 100 ms of sleep on the presenter thread, while it owns the
surface lock and after it releases the frame lock. That sleep is included in the
reported `windowLock` stage. It does not hold the producer's frame lock, change
vector ownership or alter pending-frame replacement rules. `unavailable` detaches
the native surface after warmup while leaving the core running in the fixture;
this does not claim the ordinary frontend keeps games running in the background.

Each capture uses a 60-second monotonic warmup and 120-second observation. The
full-image comparison added for #63 remains off, so it cannot introduce extra
producer work. Existing video timing instrumentation remains on; the debug delay
is reset after capture and in cleanup. Production release objects contain neither
the delay control nor its code. The fixture records generated-code telemetry
before/after the interval to verify guest execution continues even without posts.

The isolated measurement APK and test APK built successfully (33 seconds).
The actual host also compiled in debug/release modes with NDK ARM64 API26 `-O2`;
`llvm-nm -C` found no `video_profile`/presenter-delay symbols in the release object.

## Device evidence

Retroid Pocket Classic; original Duke archive copied into isolated private test
storage, retaining its 1024x768 setting. The original game is not edited. These
are not numerical comparisons against the distinct RGDS 320x200 baseline. The
core SHA256 is `d59650e19f6e515b357aee8136669439532d16ca7b76e91a3778382f6170c5f4`.
ZIP-entry comparison to #63's measurement APK found only the native host changed;
the core and frontend payloads were identical. Full build hashes and raw reports
are in [the measurement record](benchmarks/ticket64-presenter-back-pressure.json).

Each scenario completed the 60/120-second protocol. Power state sampled every
30 seconds stayed Awake. Screenshots verify the rendered demo in normal/delayed
cases. The unavailable case deliberately retains the last image on screen;
continued guest work is verified with generated-code execution telemetry and
ongoing callbacks, not motion in that stale image.

| Metric | Normal | Presenter delayed 100 ms | Surface unavailable |
| --- | ---: | ---: | ---: |
| Producer frame-lock wait p95 (microseconds) | 0.782 | 0.625 | 0.625 |
| Producer frame-lock wait max (microseconds) | 6.927 | 3.646 | 1.615 |
| Total producer frame-lock wait (milliseconds) | 1.976 | 1.444 | 1.560 |
| Window-lock stage median (milliseconds) | 0.217 | 100.285 | No calls |
| Produced callbacks | 6,149 | 6,255 | 6,322 |
| Pending replacements | 117 | 5,072 | 0 |
| Presented buffers | 6,033 | 1,183 | 0 |
| No-surface skips | 0 | 0 | 6,322 |
| Generated-code execution counter increase | 168,243,406 | 171,929,700 | 172,566,172 |

The imposed 100 ms wait is visible at the presenter, while producer lock waits
remain microseconds. The producer continues supplying frames as the presenter
falls behind; existing pending-frame replacement absorbs the difference. With no
surface, every dequeued frame is skipped and guest execution continues. The
normal run has one in-flight timing-boundary frame (presentations versus produced
minus replaced); independently sampled stages are not a transactional frame log.
CPU telemetry is coarsely published and read after the drain, so its delta proves
continued execution, not an exact instruction-rate or performance improvement.

## Observation cost and limits

After all game captures completed, the standalone
`tools/measure_video_profile_overhead.cpp` probe exercised the actual profiler
header on the same device. Two threads each ran 20,000 simulated frame iterations,
recording nine timing/count events per pair. Disabled/enabled/disabled trials were
repeated twice. Enabled wall times were 42.397 and 38.385 ms; disabled trials were
0.703-0.939 ms. Subtracting adjacent disabled means gives **2.075 and 1.883
microseconds per frame pair** for this dense concurrent workload.

This measures recording cost, including its internal mutex/vector work and
clock calls. It is not an integrated app-overhead percentage or a universal
upper bound. Actual low-rate scheduling/allocations can differ. Producer wait
measurements include timer cost and are near its floor; do not interpret them as
precise nanosecond-scale lock contention. The relevant finding is the separation
between a measured 100 ms display stall and microsecond producer waits.

The optional `presentationTiming=false` control was added and Kotlin-compiled
after these captures; it was not used for a fourth app run. All three recorded
captures have timing enabled. Release-object symbol inspection verifies the test
delay and timing observer are absent from release compilation.

## Decision and cleanup

No measured bottleneck justifies another presenter-thread or lock redesign.
The existing worker already isolates guest execution from the tested display
waits. Ordinary pixel conversion/copy cost, host CPU/memory contention and other
frontend lifecycle behavior are separate questions; this audit does not claim
they are free or that all devices have identical bounds. No production queue,
guest timing, game settings or Kairo98 behavior was changed.

Both APKs used the existing application ID for the app and its instrumentation
package. After captures, the exact original Retroid APK was restored with
`adb install -r`, and device-side SHA256 matched
`5a114b2b0db15ef2327b09f44daef61a2b4c8544792b7777c4b610e270f23db9`.
The temporary game directory was verified absent. #72's RGDS deployment remains
pending independently; the RGDS still did not advertise a Wi-Fi ADB service.
