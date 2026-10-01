# Ticket #72: adoption decision

**Adopt the measured generic SETcc translation.** Production decoder/helper source
matches the tested prototype exactly. No other instruction family, guest cycle
policy, invalidation rule, frontend setting or game file is changed.

## Evidence supporting adoption

- 204,800 snapshot comparisons across the extended real-mode and protected32
  suites matched the interpreter and independent result/flag/register/memory
  oracle. These are comparison counts, with repeated inputs as documented.
- 2,048 self-modifying-code checks preserved external-target and current-block
  invalidation behavior.
- 96 genuine CPL3 paging cases verified 64 absent/read-only faults and 32 writes,
  including exact exception state, untouched memory before repair and retry result.
- 3,072 FS segment comparisons on Retroid matched the original interpreter.
  They confirm no new difference in the tested cases, **not** architectural segment
  protection: both permit the tested above-limit and read-only-descriptor writes.
- The sound-enabled RGDS A/B/A/B/A experiment measured baseline rates of 19.0578,
  17.4661 and 18.7675 display submissions/s, versus prototype 19.4972 and 19.6599.
  Both prototype runs exceeded all baselines and reduced interpreter self-time.

The observation supports this small generic improvement, but not a fixed FPS
promise: baseline variation is substantial, only five runs were captured, and the
nominal warmup included consistent ADB overhead. The observed +6.23% difference of
means must not be described as a statistically established gain. Future captures
use the corrected wall-clock warmup helper.

## What changes

Enable 0F 90-9F in the dynrec. Retain flag producers, normalize any nonzero
condition-helper result to byte 1, preserve the computed address across helper
calls and retain the existing checked memory-write path. Original third-party
notices remain intact. Core-specific implementation remains in Staging.

## Existing limits and scope

This does not make Staging a fully precise x86 protection emulator. Supervisor
CR0.WP behavior is independently reproduced on the baseline and tracked in #73.
Segment-limit/read-only-descriptor behavior also predates the change, was surfaced
to the user, and remains unchanged. The passing CPL3 fault tests must not be used
as evidence that these other protections work. Arbitrary all-game safety cannot
be proved by these tests; no new regression was observed in the covered cases.
No Kairo98 source or installation is touched.

See [performance](setcc-performance.md), [protected mode](setcc-protected-mode.md),
and [segment evidence](benchmarks/ticket72-setcc-segments.json). The segment fixture
is generated with `tools/generate_setcc_segment_probe.py <pm template> <output>`
and run with the same standalone runner and explicit 486 config. Its 48 distinct
condition/destination cases repeat 64 times. Prototype telemetry recorded 75
translations and 12,701 generated-code invocations on Retroid.

## Binary and deployment state

The already-built and RGDS-tested APK is `.tmp/ticket72/prototype.apk`, SHA256
`22eba5ada93003208297e1970706d8ee9d2ff25230e16ee6afb8c3ab4577eae2`, with core Build ID
`a97cd112e9297ac093e9480132b2345068b870f9`. Its source implementation now matches the
production tree. It differs from the benchmark baseline APK only in the core.
This is a development measurement build, not a new release-ready claim.

After benchmarks, the RGDS was restored to the baseline. During final segment
validation, both its paired Wi-Fi endpoint and fixed endpoint became unreachable.
The segment fixture therefore ran standalone on Retroid without replacing its app.
**Final RGDS installation of the adopted APK is pending; #72 stays open until
that deployment is accounted for.** Check mDNS/fixed endpoint and use
`tools/InstallRgDs.ps1` when reachable. Do not ask for USB or overwrite the Retroid
app with an older frontend just to bypass RGDS connectivity.
