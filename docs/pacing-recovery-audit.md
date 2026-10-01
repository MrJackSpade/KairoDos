# Host-delay recovery audit (#68)

Decision: **no runtime change**. Staging already recovers short overruns;
its explicit 20 ms backlog cap discards excess time after larger stalls.
No per-frame Android timing reset corresponding to the old Kairo98 bug was
found. Changing the cap is not justified as a performance optimization by
this audit. #68 is an investigation, not an implementation ticket.

## Actual paths inspected

- `third_party/dosbox-staging/src/dosbox.cpp`: `normal_loop`, `increase_ticks`
  and `KairoResetHostTiming`.
- `src/hardware/pic.cpp`: `TIMER_AddTick` advances guest `PIC_Ticks`, adjusts
  queued event indices and calls tick handlers.
- `src/hardware/timer.h`: monotonic host microsecond/millisecond clocks.
- `backend-dos/src/main/cpp/staging_bridge.cpp`: `KairoStagingPoll` resets
  host timing only when the frontend reports result 2 (resumed from pause).
- `kairodos/src/main/cpp/native_host.cpp`: `poll` returns 2 only after its
  explicit paused condition-variable wait. Ordinary polling returns 1.
- `src/gui/sdl_gui.cpp`: DOS-rate presentation follows emulated VGA frames;
  host-rate presentation has separate presentation bookkeeping. Neither is
  a wrapper that advances guest time on the Android panel's 60 Hz grid.

The normal loop consumes each pending millisecond with `TIMER_AddTick`.
When no pending ticks remain, `increase_ticks` schedules elapsed host time.
It stores the new host anchor and caps the scheduled backlog at 20 ms.
The excess is not retained as debt. If no millisecond has elapsed, it sleeps
for 1 ms rather than generating additional guest ticks ahead of the clock.
Auto-cycle adaptation changes work per guest millisecond, not this cap.
Fast-forward and explicit pause/resume are separate paths.

Kairo98 commit `6d5b746` was read locally. It removed a reset on **every** late
60 Hz frame while retaining re-anchoring after a backlog exceeding 50 ms.
Staging's policy likewise distinguishes bounded recovery from larger stalls,
but uses a different scheduling unit and threshold. Copying the PC98 50 ms
constant or imposing a 60 Hz guest clock is not warranted.

## ARM64 measurement

`tools/test_pacing_recovery.py` extracts the actual `increase_ticks`,
`normal_loop`, `TIMER_AddTick`, host-difference and pause-reset methods into
`tools/pacing_recovery_fixture.cpp.in`. The single scheduler sleep call is
redirected to a host-clock adapter: deterministic clock advancement for
boundary cases, real `sleep_for` on RGDS for physical-delay cases. The
scheduler arithmetic and branches and tick-consumption code are unchanged.

The executable was compiled with NDK 28.2.13676358, Android API 26, ARM64,
`-O2`, then pushed and run over Wi-Fi. Executed-binary SHA-256 matched the
built artifact. It passed 26 deterministic and eight real-host cases,
covering fixed and automatic cycle adaptation.

| Elapsed host delay | Guest ms scheduled | Permanently discarded ms |
| ---: | ---: | ---: |
| 1, 2, 5, 10, 15, 19, 20 | Same as delay | 0 |
| 21 | 20 | 1 |
| 25 | 20 | 5 |
| 50 | 20 | 30 |
| 100 | 20 | 80 |
| 250 | 20 | 230 |
| 1000 | 20 | 980 |

For every deterministic case, the subsequent 1,000 host milliseconds advanced
exactly 1,000 guest milliseconds. The total host/guest offset remained equal
to discarded time plus one pending millisecond: neither growing lag nor
sustained acceleration. Tick callbacks and queued-event index adjustment
matched the actual `PIC_Ticks` count.

Real RGDS sleeps requested 5, 10, 50 and 250 ms in both adaptation modes.
Measured intervals equaled those integer-millisecond requests in this run.
The 5/10 ms cases lost no guest ticks; 50/250 ms lost 30/230 respectively.
During subsequent recovery windows of 1,000-1,001 host milliseconds, guest
progress was 999-1,000 ms. Pending time of 1-2 ms explains the endpoint
variation. No later interval hit the cap; the full elapsed/scheduled/consumed
ledger assertion passed for every case. There was no continuing speedup to
recover the discarded 30/230 ms.

## Scope and next-step decision

These are actual-method scheduler tests with real guest tick advancement,
not an entire running DOS machine. Guest CPU execution, PIC dispatch, GUI and
peripheral callbacks are sinks. The tests do not measure Duke's natural
stall frequency, game throughput, final audio delivery, or behavior under
sustained CPU overload. They prove the bounded-recovery mechanism and its
persistent offset after the tested delays, not that large stalls never occur
in gameplay. No 60/120-second game throughput comparison or observer-overhead
claim is made; no app instrumentation or runtime change was introduced.

Retain the current scheduler. If future workload traces identify frequent
clipped intervals, the next specific investigation is to measure discarded
tick frequency and its causes in that workload before proposing a different
backlog policy. Extending the cap can create catch-up bursts and requires its
own timing/audio compatibility evidence; it is not a free FPS improvement.
There is no justified implementation ticket from this result alone.

[Machine-readable evidence](benchmarks/ticket68-pacing-recovery.json) includes
method/source/binary hashes and every result. Installed KairoDos remained the
adopted normal APK with SHA-256
`c4e58b4f12d9a8414d91bf97ee474ad7dc036e0d4de38761c089e0b5e7e62a39`.
No Kairo98, game files, cycles settings, audio buffers or display-refresh
settings were changed.
