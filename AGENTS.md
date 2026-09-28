# Project instructions

- KairoDos is an independent Android DOS app. Credit DOSBox Pure accurately without using its name as this app's brand.
- Keep only this project's `origin` remote and the pinned first-party Kairo frontend submodule at `shared/`. Do not add upstream remotes, fork relationships, or automated merges.
- Copy an audited DOSBox Pure source release into `third_party/` before building the emulator. Do not make DOSBox Pure a submodule or add its Git remote. Record provenance and transitive licenses in `docs/source-import.md` and `docs/licensing.md`.
- Do not bundle proprietary BIOS, ROM, operating system, or game files.
- First-party code is GPL-2.0-or-later. Preserve each third-party notice and supply complete corresponding source for distributed binaries.
- Free GitHub and paid Google Play builds must have the same features and behavior and come from the same revision.
- Do not claim a binary is release-ready until it boots a game on an Android device and its license audit is complete.
- When the user asks to push a release tag, treat that as confirmation that testing is sufficient. Keep the normal tag-triggered release workflow enabled; do not bypass it or substitute manual publication to add a test gate.

# Local Windows build

- This workstation has the Android SDK at `D:\android-sdk` (platform 36, NDK `28.2.13676358`, CMake `3.22.1`) and a populated Gradle cache at `C:\Users\Service Account\.gradle`. Environment variables may be unset; check these locations before claiming the toolchain is unavailable.
- The checkout path contains a space, which makes `ndk-build` reject `Android.mk`. Temporarily map the checkout to an unused drive letter (verified with `K:` here), build from that drive, and remove the mapping afterward. The exact working command is in `docs/build-windows.md`.

# Local Kairo98 signing and device updates

- The companion Kairo98 checkout is at `C:\Users\Service Account\Kairo98`. Its existing update key is `.downloads\ci-signing\kairo98-beta.p12`; its password source is the adjacent `password.txt`. These are local, ignored files. Read the password into `KAIRO98_BETA_PASSWORD` for the build without printing it, and set `KAIRO98_BETA_KEYSTORE` to the absolute `.p12` path. Do not commit either file or the password.
- Kairo98's `kairo98/build.gradle.kts` uses those environment variables for the `beta` signing config, including debug builds. Build `:kairo98:assembleWithImagesDebug` with the variables set, then install `kairo98/build/outputs/apk/withImages/debug/kairo98-withImages-debug.apk` with `adb install -r`. An ordinary debug APK uses a different key and cannot update the existing installations on the Retroid or RGDS.
- Keep Kairo98's pinned `shared/` submodule on the same first-party Kairo commit as KairoDos when changing shared controller code. Build both apps and update both devices after such changes.
