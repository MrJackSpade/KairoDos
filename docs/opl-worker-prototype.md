# Ordered OPL worker prototype (#74)

Status: worker primitive and chip-level ordering test complete; an opt-in
Staging integration passed its first OPL3 game comparison but is **not accepted
or enabled by default**. #74 remains open. The design and acceptance requirements
are in `opl-worker-feasibility.md`.

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
not a retry/fallback mechanism. No production OPL lifecycle is wired to this
primitive yet, and it has no effect on either app's current runtime behavior.

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
marks. The application uses default `Track=false`; counter updates are discarded
at compile time. They therefore are not newly enabled benchmark instrumentation.

The normal adopted APK was restored after this batch and its installed hash
again matched `22eba5ada93003208297e1970706d8ee9d2ff25230e16ee6afb8c3ab4577eae2`.

## Performance builds prepared

Built baseline and worker variants from the same isolated sources with
`guestProfile=false` and `oplVerify=false`. Their only differing non-signature
APK entry is `lib/arm64-v8a/libdosbox_staging.so`. Packaged and saved debug ELF
Build IDs match for each variant, and both packaged cores exclude the
verification log marker. APK/core hashes and Build IDs are recorded in
[build identities](benchmarks/ticket74-performance-builds.json). These builds
have not been performance-tested or adopted.

## Next required work

Run controlled comparator-free A/B measurements on RGDS, including total CPU
and underruns. The passing mode/lifecycle checks permit that isolated experiment
to determine whether further prototype work is justified; they do not qualify
the worker for adoption. If the candidate loses or cannot repeat a gain, reject
it without expanding an unhelpful implementation. If promising, complete the
remaining independent timer/status, sample-boundary, routing and application
queue/lifetime stress evidence, plus another OPL workload and instrumentation
overhead checks, before adoption. The acceptance requirements remain those in
the design; this checkpoint alone does not justify normal deployment.
