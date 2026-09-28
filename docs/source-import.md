# Source provenance

`shared/` is the pinned first-party Kairo Android frontend submodule. DOSBox Pure is **copied source** in `third_party/dosbox-pure/`; it is not a submodule, subtree, remote, or automated upstream sync.

## DOSBox Pure copy

- Project: [schellingb/dosbox-pure](https://github.com/schellingb/dosbox-pure)
- Upstream release: `1.0-preview6` (14 July 2026)
- Source commit: `a4a0bab7f8931433588f2fcad9045c85b277373d`
- Archive: `https://github.com/schellingb/dosbox-pure/archive/a4a0bab7f8931433588f2fcad9045c85b277373d.tar.gz`
- Archive SHA-256: `f0d04f087bb1c63a4cf1d46e314a9e0336afad427ddebbefde3daffe64b9005b`
- Import method: extracted and copied the complete archive tree into `third_party/dosbox-pure/` (284 files). No source patches were applied.
- Build entry: `third_party/dosbox-pure/jni/Android.mk`, `arm64-v8a`, using upstream's arm64 dynamic recompiler selection.

The host in `kairodos/src/main/cpp/` uses the libretro API header copied with the core. `backend-dos/` builds upstream source into `libretro.so`; `kairodos/` builds the first-party `libkairodos_host.so`. The app loads the copied core at runtime. Neither repository carries a DOSBox Pure Git remote.

No BIOS, ROM, operating system, or game files are included in the application source or APK. The local DOS `.COM` used for a device smoke test is outside the KairoDos repository.
