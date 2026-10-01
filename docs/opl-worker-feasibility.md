# Remaining synchronous OPL synthesis (#67)

## Decision and measured budget

Proceed to a separate opt-in prototype and equivalence/performance experiment.
Do not enable a worker in production on the strength of this design alone.
OPL2, dual OPL2 and OPL3 generation can be separated from guest timer/status
state. ESFM and AdLib Gold retain their existing synchronous path pending
separate evidence for their readback and processing behavior.

`tools/profile_opl_cost.py` re-reads six existing matching-symbol RGDS captures;
[raw attribution](benchmarks/ticket67-opl-costs.json) records identities and
limitations. The 320x200 Duke title-demo profiles are approximately 120 seconds
each. Synthesis under `Opl::PortWrite` costs **5.313-5.636 CPU seconds** on the
emulation thread, about 44.3-47.0 ms CPU per wall second. Port-write inclusive
cost is 5.667-5.929 seconds. Off-CPU time in mutex-lock stacks beneath port
writes is 3.070-3.319 seconds; scheduling delay can contribute to that number.
These overlapping categories must not be added as independent CPU savings.

This establishes a worthwhile critical-path target, not a measured speedup.
Generation remains CPU work on another thread. Queue traffic, contention,
worker wakeups, mixer drain waits and device CPU capacity determine the net
result. The first prototype deliberately preserves callback serialization and
does **not** claim to remove those mutex waits. It cannot claim 10x performance
from a roughly five-percent emulation CPU category.

No new instrumentation ran in the game for this audit; this is offline
analysis of the existing simpleperf captures, whose protocol and warmup
limitations remain those in `duke3d-performance.md` and `setcc-adoption.md`.
Some system DSOs lack local symbols; emulator symbols match the recorded ELF
hashes. Attribution depends on unwinding and is not an exact invocation count.

## One complete write-to-sample path

Source: `third_party/dosbox-staging/src/hardware/audio/opl.cpp`, `opl.h`,
`src/libs/nuked/opl3.c`, and `src/audio/mixer.cpp`.

1. Guest OUT reaches `Opl::PortWrite`, which locks the OPL mutex and calls
   `RenderUpToNow` **even for address and timer writes**.
2. `RenderUpToNow` takes `PIC_FullIndex()`. If `channel->WakeUp()` actually
   awakens it, it resets `last_rendered_ms` and produces no catch-up samples.
   Otherwise it repeatedly adds `1000.0 / 49716` while the previous value is
   less than `now`, generating one frame per addition into `fifo`.
3. `RenderFrame` invokes `OPL3_GenerateStream(..., 1)`, then optional DC-bias
   processing. AdLib Gold adds its own processing. The current sample rate,
   floating-point boundary calculation and processing must remain unchanged.
4. Port routing updates the selected address or handles timer writes; normal
   sound-register writes call `OPL3_WriteRegBuffered`. That library already
   implements its own write delay and buffer-overflow behavior. An outer
   queue must replay this exact API, not substitute immediate register writes.
5. The existing `dosbox:mixer` thread requests frames through
   `MixerChannel::Mix` -> `Opl::AudioCallback`. The callback holds the OPL
   mutex, consumes FIFO frames first, generates any remaining demand, calls
   `AddSamples_sfloat`, and **then** assigns `PIC_AtomicIndex()` to
   `last_rendered_ms`. `Mix` releases the channel mutex before invoking the
   callback; `AddSamples` takes it again internally.

The host output thread does not eliminate step 2's synchronous synthesis.
Neither moving output delivery nor blindly adding another audio thread fixes
that call path.

## Guest-visible reads and special modes

- `OplChip::Write` handles registers 2, 3 and 4 using `PIC_FullIndex()`;
  `OplChip::Read` advances the 80/320 microsecond timers and returns overflow
  bits. Ordinary OPL status does not query generated samples or Nuked state.
  Keep these timers and reads on the emulation thread, including port-read
  cycle deductions and OPL2 status bits.
- Address latches, OPL3 bank-routing state, dual-chip routing/masks, register
  capture and GUS command mirroring remain synchronous and in existing order.
  Do not change their semantics while introducing a worker.
- ESFM native addressing, mode changes and `ESFM_readback_reg` touch the
  synthesizer. A timer-only mirror is insufficient. Keep ESFM synchronous;
  a future offload would require ordered read barriers or a proven full mirror.
- AdLib Gold control writes affect processing and mixer/channel state; retain
  its synchronous path. Do not silently skip its effects to gain speed.

## Bounded first prototype

Retain the current OPL mutex as the serialization point between guest port
writes and mixer callbacks. Add a worker owning the Nuked chip for ordinary
OPL modes only. It receives an ordered journal of `Generate(frame_count)` and
`Write(register, value)` operations. Each operation has an increasing sequence
number. Generation commands express the exact sample count resolved at the
original scheduling point, not a wall-clock timestamp interpreted later.

On `RenderUpToNow`, preserve WakeUp and the exact repeated floating-point
addition, but reserve and enqueue the corresponding generated frames instead
of generating on the caller. A following sound-register write is enqueued
after those frames. Address/timer writes still cause the original catch-up
boundary. Do not combine commands across a write or discard repeated writes.
Keep port-routing fields separate from the worker-owned chip.

Worker output is immutable, privately owned blocks with sequence and frame
counts. Only the caller owning the OPL mutex transfers completed blocks into
the existing FIFO. The worker never calls PIC, channel, mixer, capture or GUS
functions and never writes the shared mixer buffer. DC processing must remain
in sample order on the chip owner, with reset/lifetime behavior matching the
reference; verify it rather than silently disabling it.

On `AudioCallback`, while holding the existing OPL mutex:

1. Drain every previously issued operation and transfer all generated output.
2. Consume the existing FIFO in the original order.
3. After that drain, generate any additional requested frames on the existing
   mixer thread and feed them to `AddSamples_sfloat` in the original order.
   The prototype refines the original proposed worker round trip here: the
   existing OPL mutex prevents new submission, and the worker is fully drained,
   so this is an exclusive chip-ownership handoff. This work was already off
   the emulation thread; moving it again would add a wait without advancing
   the measured target.
4. Assign `last_rendered_ms = PIC_AtomicIndex()` at the original end point.

This retains mixer callback blocking. Releasing the mutex and moving the time
assignment to callback entry would be a separate timing change, not a free
optimization. The first experiment isolates port-write synthesis offload.

Bound journal entries and worker-owned output frames. On capacity pressure,
the caller drains completed output into the original FIFO while waiting for
space. A worker blocked on full output must always be unblocked by this drain
without needing the OPL, channel or global mixer lock. Do not simply block a
full input queue while holding the OPL mutex: that can deadlock against output
capacity and a mixer callback waiting for the same mutex. Chunk large frame
requests. Existing FIFO growth semantics remain; the worker must not add an
unbounded second backlog. Report high-water marks and capacity-induced waits.

Construction completes chip initialization before worker use. On reset,
shutdown or mode recreation, stop accepting commands, resolve pending work
according to the existing FIFO lifetime, join the worker, and only then free
chip/buffer storage. Worker progress must not depend on the global mixer lock,
which teardown already holds. Error fallback must drain to a known sequence
and return chip ownership synchronously; never run two owners or lose commands.

Keep Staging-specific timing, routing and synthesis in the DOS core/adapter.
Any new reusable worker/queue infrastructure belongs in shared; do not copy
PC98's entire synth bridge or add another product-local generic worker library.

## Equivalence and acceptance experiment

The PC98 history explains two necessary gates: `fc391bc` drains **after**
preparation can enqueue more work; `9b7dc78` gives worker output private
storage instead of letting it race other mixer contributions. Neither commit
is a performance measurement for DOS. Kairo98 is not changed by this work.

Use a synchronous reference and worker driven by the same recorded operation
order and time/WakeUp inputs. Compare every generated stereo sample and every
callback's ordered samples, not only a final aggregate hash. Also compare
guest-visible timer/status values and routing/capture events. Cover:

- OPL2, dual OPL2 and OPL3, both banks, key-on/off, rhythm, four-operator mode,
  stereo routing, dense/repeated writes and Nuked write-buffer wrap/overflow.
- Times below, on and above sample boundaries; repeated times; address-only
  and timer-only writes; FIFO empty/partial/full callback consumption; wake
  from silence; DC processing on/off.
- Delayed worker, saturated input/output capacity, simultaneous mixer demand,
  drain after additional generation, reset, teardown and repeated sessions.
- ESFM and Gold remain on the reference path; verify selection and smoke
  behavior, not just an assertion that fallback exists.

Run the actual integration with a debug-only reference comparator before any
timing comparison. Its overhead is not acceptable in performance captures:
disable it and measure any remaining counters' overhead independently.
Then perform repeated RGDS baseline/candidate comparisons at unchanged
resolution/configuration: 60 seconds verified demo warmup, 120 seconds capture.
Record emulation, mixer and worker CPU, port-write/callback waits, underruns,
queue bounds, presentation counts and scene identity. Do not equate submissions
with unique game frames or accept moving stalls into the mixer as a speedup.
Include another OPL workload and the relevant mode fixtures before adoption.

Reject or leave disabled if samples/status differ, audio underruns increase,
queues can deadlock/grow without bound, or performance gain does not repeat
beyond baseline variation. No quality, sample rate, guest timing or pacing
changes are part of this experiment. #67's trace/design/test-plan audit is
complete; implementation and adoption remain separate work.
