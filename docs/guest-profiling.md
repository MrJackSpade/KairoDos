# Guest CPU hotspot measurement

Issue #55 uses time-valid translation metadata to resolve generated ARM64 program counters to DOS guest blocks. This measures sampled CPU time, **not guest instruction counts or game FPS**.

## Recording

1. Build a Debug APK with `-PguestProfile=true`, following `build-windows.md`. The option defaults to false; CMake rejects enabling it for Release.
2. Save the normal installed APK and matching unstripped core before installing the measurement APK with `adb install -r`.
3. Create `kairo-guest-profile.enable` inside the benchmark game's existing `staging/config` directory. Restart the session. The marker is checked only at session start.
4. Use the agreed 60-second warmup and 120-second rendered-demo capture. Verify the startup prompt and rendered scene. Check `mWakefulness=Awake` throughout; a sleep event invalidates the run. Do not interpret black-screen samples as a rendered-game benchmark.
5. Record with `simpleperf record --clockid monotonic --trace-offcpu -e cpu-clock -f 99 --call-graph fp -p <pid> --duration 120`. Preserve the matching core Build ID, configuration, thread list, screenshots, and hardware/cadence measurements.
6. Pull `kairo-guest-profile.jsonl` from that config directory after the capture. The recorder flushes periodically. Keep raw metadata and perf files in ignored local storage; they contain guest code information.

The hooks run on the emulation thread during translation and invalidation, not on every executed instruction. No additional guest-memory reads, guest-code patches, or altered timing/cycle decisions are introduced. This does not make recording free: translation logging and allocation have an observer cost, which must be measured.

## Analysis

```powershell
python tools/analyze_guest_profile.py `
  --metadata <capture>/translations.jsonl --perf <capture>/perf.data `
  --simpleperf-dir D:/android-sdk/ndk/28.2.13676358/simpleperf `
  --symbols <matching-unstripped-symbols> --tid <emulation-thread-id> `
  --output <capture>/guest-report.json
python tools/test_guest_profile.py
```

Use the emulation thread, not the similarly named presenter. The analyzer maintains translation lifetimes and rejects overlapping live mappings. Direct PC samples include linked blocks without requiring a return to the outer dispatcher. Helper attribution uses the nearest mapped JIT caller and never counts one sample twice.

Limitations:

- Missing call chains leave helper work unattributed to a guest block; host symbols can still identify that work.
- Block ranges include generated guards and exits. The opcode/address list locates guest instructions, but does not provide full operand disassembly or distinguish operand-only self-modification.
- Kernel samples can inherit the interrupted user call chain; caller attribution alone is not proof of causality.
- A polling claim requires separate inspection of the polled state and exit conditions (#56). A hot address is not proof of an idle loop.

## Observer cost and cleanup

Repeat the same benchmark with the **same APK** after removing the enable marker and restarting the session. Keep simpleperf and hardware sampling identical. Compare CPU use, display submissions, and frame intervals, recording run-to-run uncertainty; display submissions are not necessarily unique game frames. Default-build comparisons additionally include compiler-layout differences.

Remove the marker and restore the saved normal APK after measurement. Never ship a measurement build as a performance fix. Publish aggregate measurements and relevant addresses rather than raw guest opcode dumps.
