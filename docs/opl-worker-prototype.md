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

## Next required work

Complete the remaining mode, boundary and lifecycle fixtures from the design.
Only after those pass,
measure comparator-free builds on RGDS and another OPL
workload, including total CPU and underruns. Adopt or reject from that evidence;
the current checkpoint alone does not justify deployment.
