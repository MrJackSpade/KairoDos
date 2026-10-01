# Ticket #71: guarded ARM64 reads — preparation

Status: **not adopted; ticket remains open**. No installed app was changed.

The [experimental patch](benchmarks/ticket71-inline-read-prototype.patch)
applies to `f260dfaa` and only changes the three dynrec read generators in
`decoder_basic.h`. It was built in the isolated Staging worktree with
`-PguestProfile=false :kairodos:assembleDebug`. Build succeeded.
[Machine-readable results](benchmarks/ticket71-preparation.json) identify the APK.

## Design reviewed

- Zero-extend the guest address, read the current full-TLB entry on every access,
  and test the entire 64-bit host pointer for null.
- For words/dwords, detect crossing with `(offset + width - 1) >> 12`.
  This also sends accesses crossing the 32-bit address boundary to the helper.
- Load exactly one, two or four bytes into the destination. No widened byte read.
- On a rejected guard, execute the original checked helper, exception check and
  result load. Writes, permissions, code invalidation and cycle policy are untouched.
- The generated fast path uses the backend's temporary registers and instructions
  that do not modify NZCV. Register/flags correctness still needs integration tests.
- No direct pointer is retained across accesses. The removed scratch store is
  internal to the read operation; active other scratch users supply their own data.

The old experiment's binary and measurements were recovered, but its source was
not located. This is a fresh prototype, not a verified restoration of that code.
The earlier 1024x768 result did not establish a benefit; that remains a reason to
require a new paired comparison rather than infer gain from helper samples.

## Actual generated-code checks

`tools/generate_arm64_read_probe.py` extracts the actual prototype emitter and
the backend instruction definitions into a standalone Android C++ probe:

```powershell
python tools/generate_arm64_read_probe.py <patched-source-root> <output.cpp>
aarch64-linux-android26-clang++.cmd -std=c++17 -O2 -static-libstdc++ <output.cpp> -o <probe>
adb -s <device> push <probe> /data/local/tmp/ticket71-read-test
adb -s <device> shell 'chmod 700 /data/local/tmp/ticket71-read-test && /data/local/tmp/ticket71-read-test'
```

On the available Retroid Pocket Classic, **110,592 checks passed**: all 4,096
offsets for byte/word/dword loads on guest pages 0, 1 and 0xfffff, each with direct,
null and changed live mappings. Code pages transition from writable to executable;
the same generated code observes mapping changes between calls.

This probe returns a sentinel for fallback. It does **not** exercise the real
checked helper, device handlers, faults, all destination-register combinations,
or full-core ABI/flags behavior. It is preliminary evidence, not the ticket's
correctness gate.

## Remaining work

1. Full-core generated-code checks, including handler/fault returns and register
   preservation; representative games beyond Duke.
2. Original/prototype paired RGDS captures: 60-second warmup, 120-second rendered
   demo, 320x200, matching configuration and hardware measurements, no counters.
3. Repeat enough to distinguish any gain from run variation and check audio/video
   timing. Report display submissions, not unique game FPS.
4. Adopt only a repeatable gain with passing correctness checks; otherwise revert.

The RGDS was absent from USB and mDNS on this continuation. Connections to its
last paired port 35597 and existing fixed port 5555 timed out. This establishes
unavailability, not its cause. The Retroid probe did not modify installed apps.
