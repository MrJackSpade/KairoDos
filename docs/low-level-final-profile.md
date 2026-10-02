# Normal-build low-level profile

The final integration measurements are recorded separately from this sampled
profile. This profile uses the normal PGO core at `99c4c766`, with paging local
binding, NEON interpolation, and known CMP/TEST condition helpers. Guest-code
logging and verification instrumentation are disabled. Exact binary identities
and aggregate samples are in [the evidence](benchmarks/low-level-final-profile.json).

## Where the CPU time goes

The 119.927-second recording contains 28,950 samples, with none lost. Main-thread
sampled CPU time is 104.492 seconds. About 50.764 seconds fall in the anonymous
executable region whose size matches the dynrec cache allocation. This is a
region-level attribution, not a time-valid identification of individual guest
instructions. Another 0.734 seconds remain unresolved outside that region.

| Main-thread location | Sampled CPU seconds |
| --- | ---: |
| Dynrec executable region | 50.764 |
| Checked byte/word/dword reads, combined | 10.683 |
| Dispatcher | 4.025 |
| VGA line drawing | 2.995 |
| Checked dword writes | 2.879 |
| Known condition helpers, combined | 2.251 |
| Carry-flag helper | 1.910 |
| Scale1x conversion | 1.231 |
| Interpreter, observed inclusive | 0.327 |

These are sampled instruction locations, not estimates of removable latency.
PGO inlines code: the dispatcher includes work previously attributed separately
to `MakeCodePage`, and VGA line drawing includes palette work. Comparing only
function names with the earlier non-PGO diagnostic would misstate the changes.
Scale1x is the retained one-to-one conversion, not renewed CPU enlargement.

The mixer accounts for 27.126 sampled CPU seconds, including 13.759 in the
inlined OPL generator and 4.834 in interpolation. The existing OPL worker accounts
for another 5.387 seconds. Its work is already off the main emulation thread;
the worker optimization predates this investigation and is not counted again.

## Hardware observations

Whole-process counters record 264.991 billion cycles, 153.330 billion instructions,
145.460 CPU seconds, 41.072 billion cache references, 464.019 million cache misses,
14.286 billion branches and 1.254 billion branch misses. The reported effective
frequency is 1.811 GHz. Cache-miss and branch-miss ratios are approximately 1.13%
and 8.78%, respectively. These are process-wide counters, not main-thread-only
or memory-stall measurements. They do not establish that memory latency is free.

Sparse CPU frequency sources disagree (scaling reports 1.992 GHz while the debug
clock reports 1.104 GHz); the measured cycle/task-clock ratio is the more useful
observation here. DDR reports 920 MHz. No clock, governor, game, sound, resolution
or cycle settings were changed. The evidence does not support an order-of-magnitude
underclock or a demonstrated bandwidth bottleneck.

## Remaining candidate dispositions

- **Direct ARM64 BL calls:** 710,218 recognized call sites in the time-valid
  diagnostic metadata are all outside BL's signed 128 MiB reach. The smallest
  displacement is 189,114,360 bytes. In the normal run's later address map, the
  largest free gap in the union of the six checked-memory helpers' BL ranges is
  876,544 bytes, versus an 8,429,568-byte contiguous cache allocation. A direct
  opcode replacement is therefore inapplicable to these observed layouts.
  A startup reservation/allocation redesign is a separate, untested project;
  these snapshots do not prove such a redesign impossible. No arbitrary existing
  mappings were moved. [Call-range evidence](benchmarks/arm64-direct-call-range.json).
- **Simple register forwarding:** the exact adjacent store/load pattern accounts
  for only 0.191 sampled seconds in the earlier diagnostic. Broader register
  allocation would require a fault-visible guest-state and invalidation design;
  this narrow observation does not establish its payoff. It is not represented
  as an experimentally rejected register allocator.
- **Additional interpreter translations:** the current normal profile observes
  0.251 self and 0.327 inclusive seconds. Missing/tail-call ancestry limits the
  inclusive estimate, but there is no demonstrated material remaining fallback
  target to justify another opcode implementation in this sweep.
- **Further flag fusion:** known zero/nonzero condition helpers account for
  0.417 seconds; all known condition helpers total 2.251 seconds. Carry evaluation
  still costs 1.910 seconds, but sampled caller ancestry cannot reliably separate
  branch use from arithmetic carry consumers. Expanding producer recognition or
  inlining a new flag representation without that distinction would be speculative.
  The controlled CMP/TEST and visibility experiments address the concrete paths
  identified here; no claim of exhaustive flag optimization is made.
- **Palette and OPL rewrites:** the current palette/conversion path is consistent
  with the previous investigation and remains 1x. OPL generation remains material,
  but its ordered worker is already implemented. No identified redundant operation
  or demonstrated data-layout defect justifies replacing synthesis semantics.
  Audio interpolation was separately tested and its exact output compared.
- **Arbitrary structure reordering, padding and prefetch:** cache/branch counts
  alone do not identify a specific conflict or dependency to fix. The concrete
  paging address dependency and block-link layout candidates were tested. No
  additional unmeasured layout changes are included.

The remaining dominant work is generated guest execution plus its memory and
flag helpers. The investigation does not establish that all possible low-level
optimizations are exhausted; it closes the reasonable candidates supported by
the measurements collected here, with experimental failures preserved explicitly.
