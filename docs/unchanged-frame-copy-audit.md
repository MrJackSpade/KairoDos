# Ticket #63: unchanged-frame copy audit

**Audit complete: no production optimization justified by the measured paths.**
Earlier checkpoints and their limitations are preserved below. Final #72 RGDS
deployment remains pending because the device is unreachable over Wi-Fi. These
independent fixtures use the reachable Retroid without replacing its application.

## Source path

At production commit `29a6f3a8`:

1. `src/gui/render/render.cpp:start_line_handler` compares a guest scanline with
   the retained scaler cache. The first different line starts a graphics update.
   Cache clears and indexed palette changes can also start an update. Therefore
   an update is not universally proof of a different final pixel image.
2. `src/gui/sdl_gui.cpp:GFX_EndUpdate` calls renderer `EndFrame` only if
   `sdl.draw.updating_framebuffer` is true, although the function itself is called
   for unchanged frames too.
3. `backend-dos/src/main/cpp/kairo_renderer.cpp` sets dirty at `EndFrame` and size
   changes. `PresentFrame` invokes the video callback only when dirty and clears
   the flag afterward. It retains the pixel buffer between frames.
4. `kairodos/src/main/cpp/native_host.cpp:video` converts the whole callback frame
   into a pending RGBA buffer: width × height × 4 bytes written. It may replace an
   unconsumed pending frame. The renderer swaps vector ownership without copying
   pixels, then copies rows into the native window. No surface/lock failures and
   pending-frame replacement mean callback and window-copy counts can differ.

The normal unchanged-frame path already suppresses callbacks. A new full-frame
comparison in production would add work; evidence does not currently justify it.

## Actual-core fixtures

`tools/generate_video_copy_probe.py` generates original mode-13 programs that
clear the screen, then either leave it static, change one pixel using the BIOS
tick, or change palette entry 255 while every visible pixel uses entry zero.
These are synthetic correctness observations with sound disabled and fixed 10000
cycles, not Duke performance comparisons or recommended game settings.

`tools/staging_video_probe_runner.cpp` loads the existing tested core and compares
each callback's full pixel contents with the preceding callback. It observes
after two seconds and asks the core to stop at eight seconds. It never installs
an APK or touches game files. Sources and config are generated together; assemble
with NDK clang `--target=i386-none-elf`, then link using `ld.lld -m elf_i386
-Ttext=0x100 --oformat=binary` to `PROBE.COM`. The config mounts only
`/data/local/tmp/kairo63`. Compile the runner with the NDK ARM64 API26 compiler,
`-std=c++17 -O2 -static-libstdc++ -I backend-dos/src/main/cpp -ldl`.
Its four arguments are core library, configuration file, configuration directory,
and resource root. Set `LD_LIBRARY_PATH` to the directory containing that core and
its matching `libc++_shared.so`.

Retroid Pocket Classic, core SHA256
`d59650e19f6e515b357aee8136669439532d16ca7b76e91a3778382f6170c5f4`:

| Fixture | Callbacks after warmup | Different images | Duplicate images | Callback bytes |
| --- | ---: | ---: | ---: | ---: |
| Static | 0 | 0 | 0 | 0 |
| Changing pixel | 80 | 80 | 0 | 20,480,000 |
| Unused palette entry | 0 | 0 | 0 | 0 |

All finished with core result zero and final 320×200 dimensions. The changing
pixel case demonstrates the observer was receiving video, avoiding a vacuous
all-zero result. Its measured observer work was 6.147 ms; that timing does not
establish incremental instrumentation overhead without an uninstrumented control.
Machine-readable results: [fixture data](benchmarks/ticket63-video-fixtures.json).

## Remaining acceptance evidence

- Count guest update decisions, core callbacks, exact image changes, replaced
  pending frames, actual window copies and bytes within the running Android app.
- Include resets/mode changes, used-palette changes and other pixel formats.
- Measure instrumentation overhead against the same build with observation off.
- Capture the unchanged Duke configuration after an actual 60-second warmup for
  120 seconds on RGDS, with device/build identity and rendered-scene verification.
- Determine whether any remaining duplicate copies cost enough to justify a
  separate implementation ticket. Do not optimize or close #63 from these short
  standalone fixtures alone.

## Android observer preparation

The debug host now has an independently enabled `frame_copy_profile` observer.
The existing instrumentation entry accepts `-e presentationCopies true` to enable
it; omitted/false keeps content comparison off while retaining the existing
timing metrics. Reports include `metrics.frameCopies` with callback count,
changed-content count, duplicates, first/resized images, callback byte count,
window-copy count and bytes actually copied into the native window. The first
image and size changes are classified separately, not asserted to be changed
guest images. Padding outside the visible row is excluded from comparisons.

This counts callbacks and window copies independently; frames can be replaced
before presentation. `callbackBytes` denotes visible bytes converted into the
pending buffer, not observer overhead, window stride allocation, or GPU traffic.
No observation changes which frames are presented. The observer's extra compare
and retained-image copy require an on/off overhead comparison before interpreting
performance measurements. Ordinary timing captures keep it off by default.

The presentation fixture now uses a 60-second monotonic warmup deadline after
launch keys and records actual warmup duration. Use `presentationSeconds=120` for
the requested capture interval. This harness change has not yet been exercised
against the RGDS game; its older launch/audio behavior must also be verified
against the real-app benchmark before treating fixture timing as equivalent.

Validation so far: the actual native host compiled with and without
`KAIRO_VIDEO_PROFILE` using NDK ARM64 API26 and `-O2`; `llvm-nm -C` found no
`frame_copy_profile` or `video_profile` symbols in the release object. The ARM64
`tools/test_frame_copy_profile.cpp` test ran on Retroid and passed disabled
collection, changed pixels, duplicate pixels with different padding, resized
images, invalid input, stopped collection and reset. Its expected accumulated
counts were four callbacks (one changed, one duplicate, two first/resized),
56 callback bytes, and two window copies totaling 24 bytes. This verifies counter
semantics, not actual app measurements or instrumentation overhead.

`:kairodos:compileDebugAndroidTestKotlin` also passed using the local SDK/Gradle
cache (52 seconds). No updated APK was installed during this preparation.

## Isolated measurement build and Retroid eligibility

The isolated benchmark checkout subsequently built both APKs successfully in
33 seconds with `guestProfile=false`. ZIP-entry comparison against the tested
SETcc APK found exactly one changed entry, `lib/arm64-v8a/libkairodos_host.so`;
the emulator, frontend DEX/resources and entry list were identical. Artifact
identities are in [the build manifest](benchmarks/ticket63-measurement-build.json).
Artifacts are preserved locally as `.tmp/ticket63/measurement.apk` and
`measurement-test.apk`; the normal SETcc APK remains untouched.

A read-only pull established that Retroid's installed APK was byte-for-byte the
original benchmark baseline, SHA256
`5a114b2b0db15ef2327b09f44daef61a2b4c8544792b7777c4b610e270f23db9`.
Thus the measurement APK could update it without reverting newer frontend work.
The measurement app and test APK were installed with `adb install -r` using the
existing application ID. This eligibility check supersedes the earlier concern
about an unknown/newer Retroid frontend; it does not justify downgrading any
different installation.

The first fixture attempt failed its surface-availability check while the
Retroid was dozing, before data collection. After verifying it was awake, the
retry rendered the Duke demo. No results from the failed attempt are accepted.
The fixture copies the original archive into its own cache and leaves installed
game files untouched. That copy uses its original **1024x768** setting, whereas
the earlier RGDS benchmark uses 320x200. Retroid results must not be represented
as RGDS performance improvements. The captured config selects `core=dynamic`
and ends with `cycles = auto`; game sound is enabled and the fixture consumes
audio. These runs test the Android host/counters, not the real-app audio output.

## Actual Android on/off observation

Both successful runs recorded a 60.000-second warmup and 120.000-second capture.
Fresh power checks were Awake; mid-run and final images show the 3D demo. This is
sampled scene verification, not a continuous recording. Full raw metric reports
are retained in [Retroid results](benchmarks/ticket63-retroid-copies.json).

| Metric | Observer on | Observer off |
| --- | ---: | ---: |
| Produced callbacks | 5,931 | 6,138 |
| Different from previous callback | 5,930 | Not observed |
| First/resized callbacks | 1 | Not observed |
| Duplicate callback images | 0 | Not observed |
| Pending frames replaced | 56 | 123 |
| Window copies/presentations | 5,875 | 6,016 |
| Visible callback bytes converted | 18,657,312,768 | Not counted |
| Bytes copied into native window | 18,481,152,000 | Not counted |
| Presentations/s | 48.9582 | 50.1331 |

The disabled observer correctly reports zero counters; these zeros do not mean
the control had no copies. Its existing timing-stage metrics establish that it
was rendering. Counts at independently sampled stage boundaries may include
an in-flight frame: the off run has one more window copy/presentation than
produced minus replaced. Do not interpret that as evidence of an extra duplicate
image; exact comparison was disabled. Observer-on count/byte invariants all pass.

This pair observed 2.34% fewer presentations and 3.37% fewer callbacks with the
observer on. One sequential pair cannot isolate stable overhead from scene,
scheduling or thermal variation. Full-image comparison/copy is not free, and the
data must not be described as demonstrating negligible observation overhead.

**Current decision:** no production duplicate-check optimization is justified.
This tested path delivered zero duplicate images while already skipping static
and unused-palette frames in the independent core fixtures. The cost of normal
copying is separate from the question of copying *unchanged* frames. Guest VGA
update counts, other mode transitions and the requested RGDS capture remain
outstanding; #63 is not closed by this Retroid validation.

After testing, `adb install -r` restored `.tmp/ticket63/retroid-installed.apk`.
Device-side SHA256 of the newly installed `base.apk` again matched
`5a114b2b0db15ef2327b09f44daef61a2b4c8544792b7777c4b610e270f23db9`.
The temporary game fixture directory was verified absent. Original game files
were never edited. RGDS still did not advertise an ADB service, and both its
known fixed and paired endpoints timed out; its #72 deployment remains pending.

## Final acceptance review

The earlier pending list was broader than the actual investigation ticket.
Ticket #63 requires counts, tracing the real dirty-flag producers/consumers,
an overhead measurement and an explicit decision. It requires broader regression
coverage **before accepting a later implementation**; no such implementation is
proposed here. It does not require an RGDS deployment to close a no-change audit.

| Ticket requirement | Evidence and conclusion |
| --- | --- |
| Trace actual unchanged-frame gating | Source trace above follows scaler-cache comparison through `GFX_EndUpdate`, renderer dirty flag, callback conversion and presenter copy. Static and unused-palette fixtures exercise suppression in the actual core. |
| Count changed guest images and host copies | At the core-to-host boundary, 5,930 images differ from the previous image, one establishes initial history, and none are duplicates. The host performs 5,875 window copies; 56 pending replacements explain the difference in this capture. These are delivered guest images, not all VGA scanouts or guest engine simulation ticks. Frames omitted before this boundary incur no host callback copy and are not claimed to have been counted. |
| Count bytes | Exact visible callback and window bytes are recorded and verified against dimensions/counts. Allocation capacity, padding and GPU traffic are not mislabeled as copied visible bytes. |
| Measure observation overhead | Same-build on/off capture shows -2.34% presentations with observation on in one pair. Its uncertainty is explicit; this is not proof of a stable overhead percentage or a speed improvement. |
| Controlled demo capture | Both successful captures use 60-second warmup and 120-second measurement, the same Retroid/configuration, recorded binary identities and sampled 3D-scene/wakefulness verification. No numerical comparison to the separate RGDS 320x200 baseline is made. |
| Decision | No redundant host copies were observed in these paths. Do not add another full-image comparison to production. No implementation ticket or runtime optimization is warranted from this evidence. |

This closes the investigation with a **no-change decision**, not a universal
claim about every DOS mode or a promise of improved FPS. The already separate
presenter back-pressure question belongs to #64. #72's RGDS deployment remains
pending independently. Reopen this audit if a measured workload shows duplicate
images reaching the host or a new mode-specific reproduction identifies waste.
