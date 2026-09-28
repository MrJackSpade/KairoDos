# Licensing

First-party KairoDos source is GPL-2.0-or-later; see `COPYING`. The pinned Kairo frontend's first-party code has the same license. Its generated Spleen font asset remains BSD-2-Clause, with source and full notice in `shared/third_party/spleen/`.

## Copied DOSBox Pure source

`third_party/dosbox-pure/` is a complete copy of the source revision identified in [source provenance](source-import.md). DOSBox Pure and its original DOSBox-derived code state GPL-2.0-or-later; the full GPL v2 text is in `third_party/dosbox-pure/LICENSE`. Preserve the upstream per-file author and license notices, `DOSBOX-AUTHORS`, and `DOSBOX-THANKS` when distributing source.

The copied tree also includes components with their own notices:

| Component | License stated in copied source | Notice location |
| --- | --- | --- |
| Nuked OPL3 | LGPL-2.1-or-later | `src/hardware/nukedopl3.cpp`, `.h` |
| Munt MT-32 emulator code | LGPL-2.1-or-later | `src/gui/mt32emu.h` |
| TinySoundFont | MIT | `src/gui/tsf.h` |
| libretro API and libretro-common headers | MIT | `libretro-common/` file headers |
| SIMDe MMX header | MIT or CC0, as stated in file | `src/cpu/core_dynrec/simde_x86_mmx.h` |
| Voodoo emulation portion by Aaron Giles | BSD-style three-clause notice | `src/hardware/voodoo.cpp` |
| ZIP and Ogg helpers | public-domain or MIT alternatives, as stated in files | `src/dos/drive_zip.cpp`, `src/dos/stb_vorbis.inl` |

The complete corresponding source for a distributed APK must include the KairoDos commit, the referenced `shared/` commit, this copied DOSBox Pure tree, native host, build scripts, and notices. The Android build uses the NDK C++ runtime; review the final packaged artifacts and library notices as part of the release license audit.

The KairoDos launcher artwork at `kairodos/src/main/res/mipmap-nodpi/ic_launcher.png` was supplied by the project owner on 27 September 2026 and is included byte for byte (SHA-256 `8180f2f52608045a0b8dcfe55937437368c04089043803e72647c81df58afe35`). The code license does not itself grant rights to the artwork. Confirm its redistribution terms before public distribution.

**Audit status:** source components and their embedded notices have been inventoried, but binary packaging and artwork terms have not yet been signed off. The development APK has been tested on a device; it is not yet designated release-ready.

## DOS catalog source

The private development catalog is derived from the owner's eXoDOS v6 LaunchBox XML and image archive. Game archives and launchers are not distributed. The copied descriptions and downscaled cover/screenshot images still require creator, redistribution, and derivative-rights review before a public free or paid binary can include them. The local import manifest records source paths and checksums; see [catalog](catalog.md).
