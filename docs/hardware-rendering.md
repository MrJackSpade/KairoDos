# DOS graphics on Android

The current DOSBox Staging adapter presents CPU framebuffers through Kairo's latest-frame queue and Android native windows. The 3dfx setting selects automatic or single CPU rendering threads. Shared EGL helpers remain available to Kairo98 and graphics fixtures. See [Staging migration](staging-migration.md) for the current engine integration.

## Previous DOSBox Pure implementation and measurements

The remaining sections record the implementation and RGDS measurements before the September 30, 2026 Staging migration. Their hardware-mode and save-state instructions describe that earlier core.

KairoDos can supply DOSBox Pure's existing OpenGL ES hardware-render interface.
Choose **3dfx rendering → Hardware when available** in global or game settings,
then relaunch the game. **Software** is the default and preserves the current
software presentation path for ordinary games. A game override can inherit the
global choice and is removed by resetting that game's settings.

## Ownership

- `shared/frontend/src/main/cpp/kairo/egl_context.h` owns EGL contexts, pbuffers,
  window references and surface replacement. Kairo98 uses this same helper;
  its palette/index-plane composition and scaling remain product-specific.
- `shared/frontend/src/main/cpp/kairo/gpu_frame_queue.h` supplies an offscreen
  framebuffer and a three-texture GPU mailbox. GPU copies and shared-context
  fences protect completed frames while the presenter samples them. The
  presenter owns `eglSwapBuffers` on a separate thread. A slow display drops
  old pending frames without changing DOS timing. No CPU readback is used.
- `kairodos/src/main/cpp/native_host.cpp` owns libretro negotiation, proc lookup,
  context reset/destroy callbacks, geometry updates and emulator options.
  It requests GLES 3.1. Unsupported context creation or presenter initialization
  rejects negotiation, allowing the core's software Voodoo fallback.

Surface removal, replacement or resizing preserves the producer context and
guest graphics resources. Save/load calls transfer the producer context under
the core execution mutex; every call releases it before another thread executes
the core. The presenter joins before graphics resources and the shared texture
pool are destroyed.

An actual driver context loss ends the session with a relaunch/software message.
The host does not promise transparent recovery of lost Voodoo graphics state.

## Device checks

The debug instrumentation supports these fixtures:

```text
adb shell am instrument -w -e hardwareRender true \
  com.loxifi.kairodos.test/com.mrjackspade.kairodos.CatalogUpdateInstrumentation

adb shell am instrument -w -e hardwareGame "/path/to/user/installed/Doom.zip" \
  com.loxifi.kairodos.test/com.mrjackspade.kairodos.CatalogUpdateInstrumentation
```

The first checks real shared contexts, fenced copies, both frame origins,
framebuffer growth, rejected oversized buffers, negotiation rollback, two
windows and surface removal/recreation. The second copies a user-supplied Doom
archive unchanged into test cache, boots hardware and software sessions, checks
save/load, pause/resume, surface recreation, reset and exit, then removes the
isolated cache. It never writes the user's original archive or saves.

RGDS checks have verified the host fixtures, real GLES/software Doom rendering,
and Kairo98 Steam Heart's rendering after EGL consolidation. The original,
user-supplied **Tomb Raider Gold (1998)** archive then verified the emulated
3dfx path using its existing launcher option 3 (`TOMB3D/tomb.exe`). The archive
and executables were not modified. Hardware negotiation selected GLES 3.1;
the same 3dfx executable also rendered through the software Voodoo mode.

Both modes displayed the title ring and textured gameplay with the expected
orientation, colors and geometry. The hardware session passed Home/resume,
in-app pause/resume, moving the game to the bottom screen and back while the
keyboard moved to the other screen, exit and relaunch. Returning to hardware
after the software session selected GLES 3.1 again and rendered correctly.

Short SurfaceFlinger samples of the animated title scene measured **52.9
presentations/second in hardware mode** (127 completed presentations over
2.381 seconds) and **33.1 in software mode** (126 over 3.780 seconds). These
measure Android presentation cadence, not unique game frames or simulation
speed; the rotating menu items were not synchronized. A separate hardware
gameplay sample reached 59.6 presentations/second. This is a device smoke
comparison, not a general performance guarantee. Software and hardware
Voodoo filtering/dithering can differ within the copied core.

The test ran on the RGDS; Retroid checks remain deferred at the user's request.
No game data is part of the app or source distribution. Actual driver context
loss still requires relaunching, optionally with Software selected; only
negotiation/initialization failures automatically use the software fallback.
