# Issue #59: memory access measurement

The production helpers already look up a cached host pointer. A non-null pointer permits direct access; otherwise they call a checked page handler. Word/dword accesses crossing a page use separate checked routines. This measurement separates those paths instead of labeling every checked access a page-table walk.

## Diagnostic build

The exact source is preserved in [ticket59-memory-probe.patch](benchmarks/ticket59-memory-probe.patch), against main at `e0efafd4`. Apply it only in an isolated Android measurement checkout, then build Debug with `-PguestProfile=true`. Do not apply it to production source or use the resulting APK to evaluate an optimization. It reuses the existing Debug-only profiling compile gate; no game code or settings are changed.

Create `kairo-memory-profile.enable` in the benchmark game's existing `staging/config` directory, leaving `kairo-guest-profile.enable` absent. Restart the game. The recorder writes cumulative `kairo-memory-profile.jsonl` records once per second using `CLOCK_MONOTONIC`. It runs only on the emulation thread and preserves the original reads, writes, checked-handler results and page-boundary routing. It does not perform extra guest memory accesses.

Each row has:

- `counts[operation][path]`: operations are byte/word/dword read, then byte/word/dword write. Paths are direct pointer backed by the main guest allocation, direct pointer outside that allocation, checked page handler, and page crossing.
- `handlerFlags[operation][flags]`: flags of the existing handler before calling it. These are not a complete dynamic device-type classification.
- `faults[operation]`: checked handler/crossing calls returning failure. No failure in the observed run is not proof that guards are unnecessary.

Main-allocation backing does not mean writable RAM: that allocation can also back guest ROM. A direct-pointer hit is the existing core's permission to use that access path. A null pointer is **not automatically a page fault**: initialization, read-only and translated-code handlers also use it. Flag `0x30` identifies initialization-class handling, not a unique cause. Flags `0x09` and `0x41` identify readable translated 32-bit/16-bit code pages.

## Capture and comparison

Use the standard sound-prompt response, 60-second warmup and 120-second rendered demo. Preserve awake-state checks, screenshots, hardware frequencies, thermals, per-thread CPU and display submissions. Select complete counter records within the simpleperf sample timestamp window; take differences, not startup totals. Validate monotonically increasing timestamps/counters, handler histogram sums matching handler-path counts, and fault counts bounded by handler plus crossing calls.

Repeat with the same APK and enable marker removed. The marker is read only at session start. This distinguishes counter-update overhead from part of the measurement build overhead; it does not remove disabled branches or compiler-layout changes. Compare with the accepted normal build as context. Per-access counters substantially perturb this workload, so use their path proportions diagnostically and use normal-build sampling for CPU costs. Do not infer normal-build operation throughput from their rates.

Coverage is limited to the six dynrec checked-memory helpers. Interpreter accesses, separate string/bulk paths, DMA and other core paths are not counted. In particular, no observed direct device mapping in these helpers does not mean the whole game performs no VRAM or device access.

After recording, remove the marker, reinstall the saved normal APK with `adb install -r`, verify its checksum and rendered launch, and restore the diagnostic checkout's changed source from main. The patch remains as the corresponding source for reproducing the measurement. No counter hook is retained in production code.

## Candidate resulting from the evidence

The normal capture places 10.52 sampled CPU seconds in the six helper bodies (8.73 in reads); source attribution places 2.74 seconds in their cached-pointer lookups. The generated read path also calls the helper, stores to `core_dynrec.readdata`, checks the fault result and reloads that storage. Source-line samples near a return are not exact instruction-latency measurements, and helper CPU cost is not a guaranteed recoverable gain.

Measured direct-read prevalence supports a **separate guarded inline-read experiment**, tracked in [#71](https://github.com/MrJackSpade/KairoDos/issues/71). Preserve null-TLB, width, page-crossing, wrap, fault and handler behavior, and validate actual generated code on device. Keep writes unchanged: translated-code handlers are active and necessary. The earlier 1024x768 inline-read experiment failed to improve end-to-end performance; the new ticket must establish repeatable benefit at the current baseline with counters absent before adoption.
