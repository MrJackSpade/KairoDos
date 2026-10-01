# Ordered OPL worker prototype (#74)

Status: worker primitive and chip-level ordering test complete; **not integrated
or enabled in the app**. #74 remains open. The design and acceptance requirements
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

## Next required work

Integrate behind an off-by-default measurement option, retaining the existing
callback serialization and synchronous unsupported-mode paths. Add the actual
integration comparator and boundary/lifecycle fixtures from the design. Only
after those pass, measure comparator-free builds on RGDS and another OPL
workload, including total CPU and underruns. Adopt or reject from that evidence;
the current checkpoint alone does not justify deployment.
