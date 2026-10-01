# Ticket #63: unchanged-frame copies (in progress)

This is partial evidence, not a completion or optimization claim. Final #72 RGDS
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
