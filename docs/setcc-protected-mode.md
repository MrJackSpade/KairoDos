# Ticket #72: protected-mode and paging validation

The isolated SETcc prototype passed another **81,920 actual-core comparisons** in
32-bit protected mode. All materialized/lazy CMP32 and none/66/67/66+67 prefix
combinations matched the normal interpreter byte-for-byte and passed the existing
independent result/register/flag/memory oracle. Lazy inputs repeat eight distinct
operand values, as in the earlier real-mode suites. The fixture enters/leaves
protected mode between batches and logs results through DOS only after returning.

Evidence: [protected32 snapshots](benchmarks/ticket72-setcc-pm32.json).

## Genuine user-mode page faults

A synthetic guest installs a GDT, IDT, TSS and identity page tables, enters CPL3,
executes SETcc, and returns to CPL0 through an interrupt gate. With explicit
`cputype=486`, all **96 cases** passed:

- 32 absent-page writes: page-fault error code 6.
- 32 read-only-page writes: page-fault error code 7.
- 32 writable-page writes: no fault.

These cover every condition with register-indirect and scaled-index/displacement
addresses. The oracle verifies CR2, the saved faulting instruction pointer,
arithmetic flags, and the byte being untouched before repair (read through a
valid alias). The handler fixes the page mapping and retries the instruction;
the final byte must equal the independently expected condition value.
Interpreter and prototype snapshots are byte-identical. Prototype telemetry:
671 translated / 13,062 executed blocks. Interpreter telemetry: zero/zero.
This is correctness evidence, not a performance benchmark.

Evidence: [paging results](benchmarks/ticket72-setcc-faults.json).

## Failures encountered and scope limits

The first handler skipped the faulting instruction without repairing its page.
Staging's existing interpreter PageFaultCore waits for a present mapping and a
return to the original CS:EIP. The fixture recursed and crashed. This attempt is
not a passed test and does not establish that abandoning faults works. The final
fixture uses repair/retry; no emulator behavior was changed to accommodate it.

The first supervisor read-only case did not fault, including with `cputype=486`.
This also reproduced with the original baseline core, with identical output.
Source paging checks gate these permissions on user-level access. It is tracked
separately in **#73**; it is not counted as a passed supervisor WP test and is not
attributed to SETcc. Intel's control-register/page-access rules are documented in
[Volume 3A](https://cdrdv2-public.intel.com/825758/253668-sdm-vol-3a.pdf); #73 must
verify the intended historical CPU-model behavior before any change.

The user-mode fixture initially assembled its `push offset label` as a 16-bit
immediate, creating an invalid IRET frame. Disassembly identified that fixture
bug; the tested generator emits an explicit 32-bit immediate. Earlier aborts are
not counted as core correctness failures or successful runs.

Segment-limit fault behavior is still not certified by these tests. No claim of
universal guest compatibility is made. #72 remains open for remaining validation
and an adoption decision; the installed app remains the original baseline.

## Reproduction

1. Generate an unprefixed template with `tools/generate_setcc_pm_probe.py <dir>`.
   Optional `--lazy` and `--prefix` select the protected32 snapshot suites.
2. For paging, run `tools/generate_setcc_fault_probe.py <template/setcc.s> <dir>`.
   The final saved generator reproduces the tested source byte-for-byte.
3. Assemble/link as described in `docs/setcc-experiment.md` and run in the isolated
   standalone actual-core harness. Set `cputype=486` in that fixture config only.
   Run normal and dynamic separately, preserving RESULT.BIN as normal.bin and
   dynamic.bin. Require complete files and zero core status, not merely process exit.
4. Snapshot oracle: `tools/verify_setcc_guest_probe.py <dir> --code-mode protected32`
   with matching `--lazy`/`--prefix` options. Fault oracle:
   `tools/verify_setcc_fault_probe.py <dir> --user`.
5. `tools/generate_setcc_supervisor_probe.py <template/setcc.s> <dir>` reproduces
   the separate WP limitation. Its strict oracle intentionally rejects the missing
   supervisor read-only fault; do not weaken that expected result.

All guest code is synthetic first-party test code. It runs in `/data/local/tmp`
and does not modify game files. No Kairo98 code or APK was touched.
