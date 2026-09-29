# Licensing and third-party credits

KairoDos first-party source and the pinned [Kairo frontend](https://github.com/MrJackSpade/Kairo) are GPL-2.0-or-later; see [COPYING](../COPYING). The generated Spleen UI font remains BSD-2-Clause, with its source and notice in `shared/third_party/spleen/`.

The copied [DOSBox Pure](https://github.com/schellingb/dosbox-pure) core and its DOSBox-derived code state GPL-2.0-or-later. Its complete source and license text are in `third_party/dosbox-pure/`. Preserve its per-file notices, `DOSBOX-AUTHORS`, and `DOSBOX-THANKS` when distributing source. The copied tree also contains components under their own notices, including Nuked OPL3 and Munt (LGPL-2.1-or-later), TinySoundFont and libretro headers (MIT), SIMDe (MIT or CC0 as marked), and Voodoo emulation code with a BSD-style notice.

A distributed APK requires the corresponding KairoDos source, pinned shared frontend commit, copied DOSBox Pure source, host and build scripts, and applicable third-party notices. See [source provenance](source-import.md) for the exact core revision and project patches.

The app's About screen packages an offline `THIRD_PARTY_NOTICES.txt` with the GPL text, notices from the copied DOSBox Pure components, Spleen's license, and the Android NDK 28.2.13676358 LLVM toolchain notice. The full source tree remains the authoritative source for per-file copyright and license statements.

KairoDos does not distribute games, operating systems, BIOS files, or Windows launchers. The hash catalog may include descriptions, startup profiles, and downscaled images derived from eXoDOS and LaunchBox material. Access to those sources does not itself grant redistribution rights. Review creator, redistribution, and derivative rights for included metadata and artwork before public distribution. The project owner's launcher artwork is separate from the code license.
