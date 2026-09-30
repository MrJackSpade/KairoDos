# Android 3dfx rendering host

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

RGDS USB checks have verified the host fixtures, real GLES Doom rendering, and
Kairo98 Steam Heart's rendering after EGL consolidation. These checks do **not**
prove the emulated 3dfx rendering path. Issue #41 remains open until a legally
supplied 3dfx game is checked for rendering, lifecycle and performance against
software. No game data is part of the app or source distribution.
