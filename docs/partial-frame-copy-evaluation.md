# Partial-frame / changed-row copying (#79)

Status: completed; no partial-frame renderer change justified or adopted.

## Existing behavior and prior art

Staging keeps a scaler cache and tracks alternating changed/unchanged scanline
runs (`scaler_changed_lines` / `scaler_changed_line_index`). `GFX_EndUpdate`
only calls the backend's EndFrame when updating the framebuffer. KairoRenderer
retains that RGB image and suppresses presentation when its dirty flag is false.
It passes a complete frame to the host; it does not pass scanline damage.

The host converts every visible row into a pending RGBA slot. The presenter swaps
that slot with its own vector, then copies the complete image into ANativeWindow.
Pending frames may be replaced before presentation. Each slot therefore needs a
known complete image; blindly skipping rows after a swap would retain old pixels.

Kairo98's `gpu_frame_merge.h` and `gl_presenter.h` use a different representation:
text/graphics plane deltas, row masks, palette-slot updates, and merging of damage
when older frames are dropped. Its partial uploads preserve a GPU-side mirror.
That mechanism is useful prior art, but cannot be copied into a complete-RGBA
mailbox by merely changing memcpy bounds.

The NDK native_window.h contract also says ANativeWindow_lock can expand the
requested dirty rectangle. A software partial-copy implementation must redraw
the returned region, with full invalidation on resize/surface recreation. Sparse
row updates can turn into a much larger bounding rectangle. Palette changes and
pending-frame replacement must preserve accumulated damage. No shortcut around
those correctness requirements is proposed.

## Measurement

Baseline is the adopted #78 normal APK. An isolated observer changes only
libkairodos_host.so; frontend, emulator core and settings are identical. It uses
CLOCK_THREAD_CPUTIME_ID and CLOCK_MONOTONIC around color conversion, window copy,
window lock and post. Cumulative totals are logged every two seconds. Producer
and presenter counters are independent atomic snapshots; boundaries may differ
by one concurrent operation. Clock calls and instrumentation are included in the
small measured stage cost; this is attribution, not an optimization A/B result.

A separate opt-in observer compares every visible row with the previous callback
and retains a full prior image. It excludes padding, forces new dimensions dirty,
and reports changed rows and a five-bin fraction histogram. Its own CPU/wall cost
is recorded. Timing-only runs disable all pixel comparison/copy overhead; this
avoids treating observer-warmed caches as the baseline. ARM64 assertions verified
visible-row changes, padding exclusion, resize invalidation, reset and the
comparison-disabled path before the game captures.

Every performance capture uses 60 seconds warmup and 120 seconds observation.
Start/end screenshots must show the intended rendered scene. Summaries use
log entries inside the actual capture interval and report that narrower window.

## Initial 320x200 results

Timing-only Duke3D rendered demo, 117.732 seconds between in-window log samples:

| Stage | Calls | Mean CPU time | CPU share of one core |
| --- | ---: | ---: | ---: |
| RGBA conversion | 3741 | 133.29 us | 0.424% |
| Native-window copy | 3728 | 123.88 us | 0.392% |
| Window lock | 3728 | 266.22 us | 0.843% |
| Window post | 3728 | 562.12 us | 1.780% |

Only the first two rows are the direct byte-processing target of partial copying.
Lock/post costs cannot simply be credited as eliminated by skipping copied rows.
The conversion is on the emulator worker; the window operations are on a separate
presenter. Summing CPU shares is not an FPS or critical-path speedup prediction.

Independent row observation: 3648 callbacks, 729600 rows, 362271 changed rows
(**49.65%**), no duplicate full frames and no in-window resizes. A simplistic
uniform-per-row estimate would save about 0.41% of one core, before any tracking,
union, buffer-repair or dirty-rectangle work. This is a model, not a measured win
or a rigorous bound on secondary cache effects. The observer itself consumes
0.562% of one core. A compare-and-copy implementation is therefore not justified;
existing scaler damage could avoid comparison, but still needs correct propagation.

## Higher-resolution check

The existing `DukeResolutionFixture` copied the installed session into private
fixture cache and selected 1024x768 only in that copy. Its audio consumer now uses
the shared #78 policy, matching the app. The fixture was started at its verified
sound prompt, warmed for 60 seconds, and captured for 120 seconds. Start/end images
show rendered 3D gameplay. The measured native buffer is 1024x768, not an enlarged
320x200 output. Row comparison was disabled.

Over 119.128 in-window seconds / 681 callbacks:

- Conversion: **1.473 ms CPU/callback**, **0.842% of one core**.
- Window copy: **1.461 ms CPU/copy**, **0.835% of one core**.
- Window lock: 0.293% of one core; post: 0.500% of one core.

Even erasing both direct copy stages would target only 1.68% of one core in this
actual slow high-resolution workload. That is not a promise that partial copying
can remove all of it, or that off-thread CPU savings translate directly to FPS.
The modelled changed-row fraction from 320x200 is not assumed to hold at 1024x768.

The fixture exited successfully, checked the original configuration byte-for-byte,
and deleted its copied session. The installed DUKE3D.CFG still hashes to
`1999e2a9be9ed2094649bd511d71c1217cd39fe33dc45dec6b5abafcb3a5fba5`.
No installed game resolution, executable, archive or save was edited by this test.

## Reproduction

In an isolated checkout of the #78 app:

1. Copy `tools/row_copy_measure.h` beside `native_host.cpp`, and apply
   `docs/benchmarks/ticket79-row-observer.patch` with `git apply --ignore-space-change`.
   This adds observation only. It must not be included in the normal app.
2. Build/install the existing package with the normal signing identity. Verify
   that only `lib/arm64-v8a/libkairodos_host.so` differs from the #78 APK.
3. `adb shell setprop debug.kairo.rows 0` selects timing without comparisons;
   `1` adds row observation. The choice is read once at native session start.
4. Direct-launch the exact granted game URI. Use the 60/120 capture tool after
   verifying/responding to its prompt. Save `logcat -d -v epoch -s KairoRowMeasure:I`
   as `row-log.txt`; run `tools/summarize_row_copy_measure.py <capture>`.
5. The high-resolution case uses `resolutionSource`, `resolutionWidth=1024` and
   `resolutionNoDouble=true` instrumentation arguments, with its `go`/`stop` files.
   This is a copy-cost attribution fixture, not a normal-app FPS comparison.
6. Restore the normal APK and remove the temporary debug property afterward.

`tools/test_row_copy_measure.cpp` exercises the observer on Android ARM64; compile
with the NDK, `-std=c++17 -O2 -static-libstdc++ -llog`, including the tools directory.
The observer histogram bins are 0, (0,25%], (25%,50%], (50%,75%], (75%,100%].
Counter snapshots can differ by an in-flight presenter call; row totals belong to
the producer. Logging, clock calls and pixel observation are explicitly excluded
from any claim of production performance improvement.


## Sparse 2D workload and decision

Cannon Fodder's Sound Blaster opening animation was also observed for 60-second
warmup / 120-second capture. This is an animated 2D workload, not a claim of active
Cannon gameplay or a game-completion test. Start/end images show different animated
scenes. Over 117.083 seconds, 4246 callbacks contained 849200 rows; **174855 rows
changed (20.59%)**, with no duplicate callbacks or in-window resizes.

In that comparison-enabled run, conversion and window copy consumed 0.423% and
0.459% of one core, while the observer itself consumed 0.697%. The full-frame
observer warms cache and adds work, so these are not an uncontaminated 2D baseline
or an optimization A/B. They confirm that a sparse update workload exists and
that detecting it through extra full-image comparison is not free.

**Decision: retain the current production renderer.** The measured direct budgets
are small: 0.816% of one core for both copy stages at 320x200 with comparison off,
and 1.677% at 1024x768. Only part of that can be removed, and the window copy is on
another thread. Naive row comparison consumes the prospective saving itself.
Existing scaler metadata avoids that comparison but still requires per-slot
history, damage accumulation across drops, palette/mode invalidation and actual
window dirty-region handling. These measurements do not justify implementing
that change as a performance fix. This is not proof that no future renderer
architecture or different workload can benefit; no unmeasured FPS gain is claimed.

All four frame histories were complete and their AudioFlinger underrun deltas
were zero. Production game/configuration files were preserved. No renderer
candidate was adopted, so there is no new partial-update correctness path to
ship or claim validated. The normal #78 APK is restored after measurement, with
the diagnostic property cleared; renderer source remains unchanged.

Evidence: `docs/benchmarks/ticket79-copy-attribution.json`, including all stage
CPU/wall totals, row histograms, observation intervals, archive identities and
raw-file hashes. Raw captures remain in `.tmp/ticket79`. The only maintained
fixture adjustment is using #78's shared audio policy in the isolated resolution
fixture so it stays consistent with the application. Kairo98 is unchanged.
