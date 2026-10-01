# Ordered OPL worker prototype (#74)

Status: the opt-in integration has passed mode/DC, lifecycle, saturated-queue,
independent catch-up, ordered OPL mixer-input and ordinary timer/status/routing
checks. Repeated lighter benchmarks show a benefit, but another workload's
performance and observer follow-up remain. It is **not accepted or enabled by default**. #74 remains
open. The design and acceptance requirements are in `opl-worker-feasibility.md`.

## Shared worker

`shared/frontend/src/main/cpp/kairo/ordered_worker.h` provides one externally
serialized caller and one processing thread with fixed-capacity input/output
rings. Caller-side consumption occurs outside the queue lock. A submitter
waiting for input capacity drains available output, avoiding the cycle where
the worker cannot publish output while the caller cannot enqueue input.
`drain` consumes all issued operations in order. The worker must never depend
on locks held by the caller.

Destruction cancels pending work and joins any active operation; an owner that
needs pending results must drain first. Processor exceptions wake the caller
and propagate on submit/drain. Consumer exceptions are fatal to that stream,
not a retry/fallback mechanism. The opt-in Staging integration drains and joins
at teardown; normal builds leave that integration disabled.

## RGDS chip-level validation

`tools/test_opl_ordered_worker.cpp` links the actual imported Nuked `opl3.c`.
A synchronous chip and worker-owned chip receive the same generated operation
transcript. Every result's sequence, frame count and stereo sample value are
compared. Three input configurations exercise OPL2 library mode, both OPL3
banks, and changing four-operator mode. They also cover key on/off, rhythm,
repeated writes and more than three times the Nuked write-buffer capacity
before generation. These are not Staging port-routing fixtures.

Each configuration runs with capacities 1, 4 and 64. Capacities 1 and 4 use
periodically delayed processing. Mid-stream drains are followed by more work.
Each trial compared 194,680 sample values with zero differences; the nine
trials total **1,752,120 values**. Output is demonstrably nonzero, so matching
silence cannot explain the result. Processor exception propagation and 20
cancel/join cycles also passed. Two complete runs on the RGDS passed; the
second is preserved in
[the measurement record](benchmarks/ticket74-worker-ordering.json).

Built with NDK 28.2.13676358, API 26, `-O2`, static C++ runtime. Compile the
imported `opl3.c` as C, then link it with the C++17 test, including
`shared/frontend/src/main/cpp` and `third_party/dosbox-staging/src/libs/nuked`.
The standalone executable ran through paired Wi-Fi ADB. No app was installed,
no original game was modified, and Kairo98 was not changed.

This verifies operation ordering through the worker and actual synth library;
it does not verify `Opl::PortWrite`, guest status/timers, Staging dual-OPL2
routing, WakeUp, DC processing, mixer callbacks, reset, or audio delivery. It
is not a race-detector run or a performance benchmark. The chip-level test
must not be used as evidence of app-level equivalence or FPS improvement.

## Opt-in integration

`-PoplWorker=true` enables the Debug-only prototype;
`-PoplVerify=true` additionally drives a synchronous reference. Both default
off. CMake rejects verification without the worker and rejects either option
outside Debug. These are measurement flags, not user settings.

The OPL integration journals catch-up samples and buffered writes, keeps timer
and address routing on the original path, and drains completed blocks into the
existing FIFO. The exact repeated time addition and WakeUp handling remain.
ESFM and Gold do not create a worker. Teardown drains and joins before freeing
the chip. Bounded journal/result rings do not impose a new limit on the
pre-existing FIFO; total pending frames still need measurement.

One deliberate refinement to the design: after draining, mixer callbacks
generate their remaining demand directly on the existing mixer thread. The
OPL mutex prevents any new submission during that callback, so the chip has
one active owner. This preserves the original callback synthesis and timestamp
location without an unnecessary worker round trip for work already off the
emulation thread. Pending worker output is drained first; there is no
concurrent synth access. This handoff still requires lifecycle/stress tests.

The verification build initializes a separate chip using the same tone-generator
setup and dual-OPL2 startup write. Each generated sample is compared with the
corresponding synchronous sample after DC processing, and mixer-generated
remainder samples are compared too. Verification snapshots the persistent DC
filter state at worker startup; its independent filters then advance with the
reference stream. Verification-only DC state access leaves ordinary builds'
existing function unchanged. Exit logs report compared values and differences.
This comparator does not itself prove timer/status or mixer FIFO correctness;
those require integration fixtures. Performance runs must disable verification.

The OPL translation unit passes Android ARM64 syntax compilation in all three
configurations (off, worker, worker+verification) using the existing generated
headers and NDK. The isolated verification app and instrumentation APK also
built successfully with `-PguestProfile=false -PoplWorker=true -PoplVerify=true`.
The APK SHA256 is
`cc9593ba561cac6dfd16aff65fc27c083eaad6efb55f97ec235e1d706c3d178f`.
Build success alone is not runtime validation.

## First actual integration run

The verification APK was installed over the existing RGDS app using Wi-Fi and
`adb install -r`. `StagingCoreFixture` copied the installed Doom archive into
its own temporary drive. The fixture completed first launch, reset and second
launch, pause/resume, surface replacement, and paused stop. It also verified
dynarec execution and release of JIT mappings. The final screenshot was
inspected and shows rendered Doom gameplay, not an intro or DOS prompt.

| Core instance | Compared sample values | Differences | Worker-generated frames |
| --- | ---: | ---: | ---: |
| First launch | 1,656,670 | 0 | 242,333 |
| After reset | 1,661,540 | 0 | 267,196 |
| Second launch | 1,818,372 | 0 | 249,362 |

Total: **5,136,582 sample values, zero differences**. The fixture received
3,450,880 PCM frames with nonzero peak 8,542 and observed 1,032 video frames.
These counts are fixture activity checks, not FPS or speedup measurements.
This was OPL3 with DC removal off. Comparator overhead was enabled throughout.
The reference checks generated values and sequence, not an independent model
of guest timers, routing, or the final mixed callback stream. Other modes,
DC-on behavior, capacity metrics and detailed boundary fixtures remain required.

[The record](benchmarks/ticket74-doom-integration.json) includes APK identities,
fixture output and all three comparator summaries. Afterward, the exact
previous adopted SETcc APK was restored and its installed SHA256 verified as
`22eba5ada93003208297e1970706d8ee9d2ff25230e16ee6afb8c3ab4577eae2`.
The normal app therefore does not run the experimental worker or comparator.

## Mode coverage and queue pressure

The fixture now accepts `stagingOplMode` and `stagingOplDcBias`. These append
settings only to the fixture's temporary configuration; original game files
and normal per-game settings are not edited. The same reset, repeated-launch,
pause/surface/exit checks passed in all seven additional runs:

| Mode | DC removal | Compared sample values | Result |
| --- | --- | ---: | --- |
| OPL3 | on | 5,403,110 | Zero differences |
| OPL2 | off | 5,338,602 | Zero differences |
| OPL2 | on | 5,016,874 | Zero differences |
| Dual OPL2 | off | 5,005,040 | Zero differences |
| Dual OPL2 | on | 4,859,240 | Zero differences |
| ESFM | off | Not compared | Synchronous fallback and gameplay smoke passed |
| OPL3 Gold | off | Not compared | Synchronous fallback and gameplay smoke passed |

The new worker runs total **25,622,866 sample values with zero differences**.
Combined with the first OPL3-off run, the covered configurations total
30,759,448 compared values. These are repeated samples within one game's
workload, not that many independent coverage cases. All seven final screenshots
were inspected and show Doom gameplay. Logs confirm the requested mode at each
of three core starts and DC enablement at all three starts of each DC-on case.
Fallback smoke does not exercise ESFM native readback or Gold surround features
and is not an independent audio comparison. See
[mode records](benchmarks/ticket74-mode-integration.json).

The standalone worker test now enables compile-time `Track=true` statistics.
Every tested capacity (1, 4, 64) actually reached its full command and result
rings, and submit-side output draining occurred in every trial. All nine trials
again compared 1,752,120 sample values with zero differences. Cancellation now
explicitly waits until the output ring is full before destroying the worker;
20 such trials passed, along with processor-exception propagation. See
[pressure records](benchmarks/ticket74-worker-pressure.json).
These establish standalone queue behavior, not production OPL FIFO high-water
marks. Normal and benchmark builds use `Track=false`; counter updates are
discarded at compile time. Verification builds now enable these statistics
for the actual integration checks described below.

The normal adopted APK was restored after this batch and its installed hash
again matched `22eba5ada93003208297e1970706d8ee9d2ff25230e16ee6afb8c3ab4577eae2`.

## Performance comparison

Built baseline and worker variants from the same isolated sources with
`guestProfile=false` and `oplVerify=false`. Their only differing non-signature
APK entry is `lib/arm64-v8a/libdosbox_staging.so`. Packaged and saved debug ELF
Build IDs match for each variant, and both packaged cores exclude the
verification log marker. APK/core hashes and Build IDs are recorded in
[build identities](benchmarks/ticket74-performance-builds.json). Repeated
[full-observer comparisons](benchmarks/ticket74-performance.md) and
[lighter comparisons](benchmarks/ticket74-observer-overhead.md) are now
complete. The lighter pairs measured a 14.64% difference of mean submission
rates, with higher total CPU and no added AudioFlinger underruns. These are
not unique guest-frame counts or an adoption claim. The full observer bundle
substantially affected throughput; use those profiles for attribution.

## Actual integration queue pressure

`-PoplWorker=true -PoplVerify=true -PoplStress=true` builds a verification-only
one-entry queue and requests a 200-microsecond worker delay for every command.
Stress requires verification; both are Debug-only. Without stress, capacity
remains 64. Only verification builds track queue statistics and FIFO maxima.
No stress flag or new metrics are enabled in normal/performance builds.

The existing Doom fixture ran with DC removal on. Each row includes first
launch, reset, and a second launch, including pause/resume, surface changes,
paused stop, dynarec execution and JIT-release checks. All final screenshots
were inspected and show rendered gameplay.

| Configuration | Core instances | Compared sample values | Differences | Maximum existing FIFO frames |
| --- | ---: | ---: | ---: | ---: |
| OPL3, delayed worker, capacity 1 | 3 | 4,968,722 | 0 | 94,031 |
| Dual OPL2, delayed worker, capacity 1 | 3 | 4,842,752 | 0 | 125,113 |
| OPL3, no artificial delay, capacity 64 | 3 | 4,908,568 | 0 | 57,644 |

All nine core instances reached **both** command and result capacity, recorded
submit-side draining and waiting, and shut down with zero queued commands or
results and identical issued/consumed counts. Total: **14,720,042 additional
compared sample values, zero differences**. This closes the actual integration
queue-pressure/lifecycle check for these exercised paths; it is not a claim
about every possible scheduler interleaving.

The existing mixer FIFO is distinct from the two bounded worker rings. Its
large observed peaks are retained in the report; no global bound is claimed
and no frames were dropped to impose one. Comparator overhead, forced delay,
and lifecycle transitions make these verification runs unsuitable for normal
latency/performance conclusions. The worker does not introduce an additional
unbounded result queue; the shared rings remain fixed-capacity.

[Application pressure evidence](benchmarks/ticket74-app-queue-pressure.json)
contains APK/source/log hashes, fixture results, and all per-instance counters.
`tools/check_opl_queue_log.py <logcat.txt> --mode OPL3 --capacity 64
--require-pressure` checks the diagnostics; use `DualOPL2` and/or capacity 1
for the stress cases. Lifecycle results and screenshots are separate checks.
The updated source compiled with worker off, worker on without verification,
and verification enabled; both verification APK builds succeeded.

After this batch, the normal adopted APK was restored over Wi-Fi. Its installed
SHA-256 was verified again as
`22eba5ada93003208297e1970706d8ee9d2ff25230e16ee6afb8c3ab4577eae2`.

## Independent catch-up timeline and OPL callback stream

The verification reference now maintains its own render timestamp and FIFO.
Before the worker batches any catch-up commands, it executes the original
synchronous loop with the observed time and WakeUp result. Commands consume
those independently generated expected frames; missing or extra commands fail
verification. It checks timeline equality at catch-up entry/exit, compares
queued and directly generated frames in callback order immediately before
`AddSamples_sfloat`, checks callback length against the request, and checks
remaining FIFO counts after shutdown drain. This closes the earlier limitation
where the reference generated as many frames as the candidate requested.

OPL3/DC-on, dual-OPL2/DC-on and OPL2/DC-off each passed the Doom fixture's three
core instances and lifecycle checks. Across nine instances, **60,518 catch-up
calls** and **6,902,438 stereo frames handed to the OPL mixer channel** passed
the independent checks. There were **17,485,882 sample comparison operations
and zero differences**. That larger counter includes worker-output comparison
and comparison again when queued output reaches the callback; it is not a
count of unique sample values or independent test cases. Nonempty FIFO tails
also matched. All three final screenshots show rendered Doom gameplay.

`tools/test_opl_timing_boundaries.py` extracts the actual current
`RenderUpToNow` method and its pre-worker version from commit `9dcfd7f6`, then
compiles them for Android ARM64 with deterministic PIC time, WakeUp results,
and counting sinks. On the RGDS it passed **20,600 cases**, accounting for
905,987 reference frames and 16,822 worker blocks with zero count/timestamp
differences. Cases include the representable values immediately below, on and
above boundaries constructed by repeated addition; 63/64/65 and 127/128-frame
batch transitions; repeated/backward times; large timestamps; and wake/no-wake
and worker/no-worker paths. The independent game oracle checks actual synthesis
and callback ordering; the extracted-method test checks boundary arithmetic.
Neither test substitutes its own rewritten batching loop for the current code.

The reference and boundary tests do not independently model PIC, channel sleep,
or guest hardware timers. The callback comparison covers ordered OPL samples
at the mixer input, not downstream filtering or the final combined PCM from
all sound devices. Those mixer stages are unchanged by this prototype.

[Timeline and boundary evidence](benchmarks/ticket74-timeline-integration.json)
includes APK/source/log hashes, extracted-method provenance, fixture results,
all timeline counters and the native boundary result. Add `--timeline` to
`tools/check_opl_queue_log.py` to require these diagnostics. Worker-off,
worker-only and verification compile checks passed; the verification APK built
and ran on RGDS. The normal adopted APK was restored afterward and hash-verified
as `22eba5ada93003208297e1970706d8ee9d2ff25230e16ee6afb8c3ab4577eae2`.

## Ordinary timer/status and port-routing comparison

`tools/test_opl_port_routing.py` extracts the actual Timer, OplChip and ordinary
OPL port methods from the current source and pre-worker commit `9dcfd7f6`.
It compiles both on Android ARM64 with deterministic PIC time and recording
sinks. The current `QueueWrite` method is also extracted; its commands remain
pending while guest reads and state checks run, then are delivered in order.
The generated fixture uses real timer and port method bodies, not separately
rewritten routing algorithms.

On RGDS, the test passed with zero differences:

- 4,611 cases across OPL2, dual OPL2 and OPL3.
- 137,532 port writes and 237,360 status reads; 210,316 reads occurred while
  chip writes were still pending.
- 23,040 explicit expected timer-status checks: all 256 counter values, both
  timer periods, just below/on/above overflow, masking, reset and stop.
- Address/data mirrors, all register addresses with representative values and
  a deterministic mixed transcript, bank selection, dual-chip waveform/pan
  masking, cache/capture event arguments/order, GUS mirror calls, address
  latches, and read-induced CPU cycle accounting matched the reference.
- 37,098 deferred-write drains preserved chip-write address/value order.

The source audit found that only `WriteReg` differs among the 20 extracted
methods: its intended dispatch to the worker. Timer methods, status reads,
port routing, address selection and cache handling are byte-identical to the
pre-worker versions. The port-registration block is also byte-identical.
The existing `newm = selected_reg & 1` behavior is preserved, including an
attempt to clear register 0x105; this test does not silently fix that separate
pre-existing issue.

Scope limits matter: this is a native actual-method test with clock inputs and
sinks. DSP synthesis/catch-up, capture-file serialization and GUS internals
are replaced by sinks. It also calls some alias methods directly beyond the
OPL2 registered-port range; unchanged registration is checked separately.
ESFM/Gold stubs fail if reached, so these results do not claim support for
their native/control features. The earlier real game, synthesis, lifecycle
and bounded-worker tests supply the complementary evidence. No runtime app or
game files were changed for this test.

[Port-routing evidence](benchmarks/ticket74-port-routing.json) records method,
registration, generated-source and binary hashes. The executed device binary
hash matched the built artifact. The normal installed APK remained unchanged
and its SHA-256 was reverified as
`22eba5ada93003208297e1970706d8ee9d2ff25230e16ee6afb8c3ab4577eae2`.

## Next required work

The measured benefit justifies continuing, but does not qualify the worker for
adoption. Complete another OPL workload's performance comparison and the
remaining frame-collector overhead follow-up. Catch-up arithmetic,
command/frame counts, ordered OPL callback inputs, and ordinary timer/status
and routing invariants now have the independent evidence above.
The acceptance requirements remain those in the design; this checkpoint alone
does not justify normal deployment. #74 remains open.
