# KairoDos

KairoDos is a planned Android DOS app using DOSBox Pure and the first-party Kairo frontend. This repository currently contains a development shell that compiles the pinned `shared/` frontend, previews its shared keyboard with DOS/libretro key labels, and stores touch settings through the shared settings dialog. These controls do **not** send input to an emulator; the app does **not** emulate DOS or run games yet. The key codes follow [libretro's `retro_key` values](https://github.com/libretro/libretro-common/blob/master/include/libretro.h).

The first-party source is GPL-2.0-or-later; see `COPYING`. The repository remains private while the emulator integration and license audit are incomplete. The Spleen font bundled by the shared frontend has a separate BSD-2-Clause notice.

The future free GitHub and paid Google Play builds must have the same features and behavior and come from the same revision. No binary is release-ready before a DOS game boots on an Android device and the artifact's license audit is complete.
