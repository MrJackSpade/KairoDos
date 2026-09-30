# Project instructions

- KairoDos is an independent Android DOS app. Credit DOSBox Staging accurately without using its name as this app's brand.
- Keep only this project's `origin` remote and the pinned first-party Kairo frontend submodule at `shared/`. Do not add upstream remotes, fork relationships, or automated merges.
- The emulator is DOSBox Staging 0.83.0, copied into `third_party/dosbox-staging/`. Do not restore DOSBox Pure, add an upstream emulator Git remote, or make the emulator a submodule. Record provenance and transitive licenses in `docs/source-import.md` and `docs/licensing.md`.
- Run `pwsh -File tools/PrepareStagingDependencies.ps1` before the first Android build. The pinned dependency sources and build recipes are under `third_party/staging-deps/`; generated libraries stay in ignored `.downloads/staging-deps/`. Never commit prebuilt emulator libraries in place of corresponding source.
- Keep ARM64 dynarec enabled (`C_DYNREC` and `C_TARGET_CPU_ARM`). The launch adapter selects `core=dynamic` for default/auto profiles in both real and protected modes; preserve explicit interpreter choices and prefetch CPU compatibility. Release JIT mappings and code-page handlers at session teardown. Verify actual generated-code execution with `StagingCoreFixture` and `nativeCpuTelemetry`; compiler flags alone are insufficient evidence.
- Staging does not implement emulator save states. Preserve existing Pure state files and game overlays; import overlays transactionally into Staging's writable drive. Do not expose unsupported Save/Load actions or discard legacy files.
- Do not bundle proprietary BIOS, ROM, operating system, or game files.
- First-party code is GPL-2.0-or-later. Preserve each third-party notice and supply complete corresponding source for distributed binaries.
- Free GitHub and paid Google Play builds must have the same features and behavior and come from the same revision.
- Do not claim a binary is release-ready until it boots a game on an Android device and its license audit is complete.
- When the user asks to push a release tag, treat that as confirmation that testing is sufficient. Keep the normal tag-triggered release workflow enabled; do not bypass it or substitute manual publication to add a test gate.

# Local Windows build

- Android toolchain versions, Gradle conventions, wrapper distribution and CI setup live in pinned `shared/` (see `shared/docs/android-build.md`). Initialize that submodule before building. Product wrappers delegate to it; do not restore duplicate plugin versions or CI setup in either app.

- This workstation has the Android SDK at `D:\android-sdk` (platform 36, NDK `28.2.13676358`, CMake `3.22.1`) and a populated Gradle cache at `C:\Users\Service Account\.gradle`. Environment variables may be unset; check these locations before claiming the toolchain is unavailable.
- The checkout path contains a space. Temporarily map it to an unused drive letter (verified with `K:` here) for native builds, then remove the mapping. The current dependency bootstrap and CMake/Gradle commands are in `docs/build-windows.md`; the previous Pure `ndk-build` commands are obsolete.

# Local Kairo98 signing and device updates

- Installed app IDs are `com.loxifi.kairo98` and `com.loxifi.kairodos`; their launch activities retain the `com.mrjackspade` namespace. Always update the existing app with `adb install -r` and verify its installed package before testing. Do not install a second application ID. The obsolete `com.mrjackspade.kairo98` package was removed from the Retroid on September 29, 2026; both devices now have only `com.loxifi.kairo98`.
- The companion Kairo98 checkout is at `C:\Users\Service Account\Kairo98`. Its existing update key is `.downloads\ci-signing\kairo98-beta.p12`; its password source is the adjacent `password.txt`. These are local, ignored files. Read the password into `KAIRO98_BETA_PASSWORD` for the build without printing it, and set `KAIRO98_BETA_KEYSTORE` to the absolute `.p12` path. Do not commit either file or the password.
- Kairo98's `kairo98/build.gradle.kts` uses those environment variables for the `beta` signing config, including debug builds. Build `:kairo98:assembleWithImagesDebug` with the variables set, then install `kairo98/build/outputs/apk/withImages/debug/kairo98-withImages-debug.apk` with `adb install -r`. An ordinary debug APK uses a different key and cannot update the existing installations on the Retroid or RGDS.
- Keep Kairo98's pinned `shared/` submodule on the same first-party Kairo commit as KairoDos when changing shared controller code. Build both apps and update both devices after such changes.

# RGDS deployment

- The RGDS wireless ADB port changes. Discover it with `adb mdns services`; the device advertises `adb-dd437d64c337800f` and has model `RG_DS`. Do not ask the user for a port before checking mDNS.
- On September 29, 2026, two large APK transfers failed because the RGDS Wi-Fi link disconnected. Device logs show `wlan0: CTRL-EVENT-DISCONNECTED`, followed by Android disabling wireless debugging. `iw dev wlan0 get power_save` was `on`; `adb shell iw dev wlan0 set power_save off` made the 238 MB KairoDos install succeed. The device shell runs as root. Use `tools/InstallRgDs.ps1 -Apk <path>` for future RGDS installs; it discovers the device and verifies power saving is off before transfer.
- Later that day, another large wireless install disconnected even with power saving disabled; the advertised ADB port then refused connections while the device still answered on Wi-Fi. USB ADB (`dd437d64c337800f`) installed both APKs successfully. Prefer USB for large RGDS updates when it is connected. Do not assume disabling Wi-Fi power saving fully fixes the transfer problem.
- The user explicitly authorized fixing the RGDS port on September 29, 2026, superseding the earlier approval rejection. Its fixed endpoint is `192.168.1.118:5555`. The root shell set `persist.adb.tcp.port=5555`, then `adb tcpip 5555`. The fixed connection survived toggling paired wireless debugging, disconnect/reconnect, and a full device reboot. After reboot `service.adb.tcp.port` is empty but adbd honors the persistent property and listens on 5555. The existing `com.loxifi.wifidebug` recovery service restores Android's paired wireless debugging but its TLS port still rotates. Prefer USB for large transfers, then the fixed endpoint. Verify model `RG DS` before installs. If the address changes, discover the RGDS via mDNS and obtain its Wi-Fi IP from the shell before reconnecting to port 5555. Do not assume the TLS port is stable.

# Retroid deployment

- The Retroid Pocket Classic is currently reachable at `192.168.1.247:5555`. On September 29, 2026, `adb -s adb-49b22840-zuwOf4._adb-tls-connect._tcp tcpip 5555` enabled the fixed TCP endpoint; `adb connect 192.168.1.247:5555` connected, and it survived screen off/on, disconnect/reconnect, and APK installation. Prefer this endpoint to the rotating Android wireless-debugging port. Verify the model with `adb -s 192.168.1.247:5555 shell getprop ro.product.model` before installing.
- If its DHCP address changes, inspect `adb devices -l` and `adb mdns services` for `adb-49b22840-zuwOf4` and obtain the current Wi-Fi address from the device shell. If port 5555 stops listening after a device reboot, reconnect through its paired TLS wireless-debugging service and run `adb -s <TLS-serial> tcpip 5555` again. Exhaust these checks before asking the user to operate the device. The Retroid shell is unprivileged, so the fixed TCP setting is not known to persist across a reboot.
- Wi-Fi was switched off via System UI at 02:33 on September 29 and re-enabled at 09:52. That historical event does not explain subsequent ADB unavailability; do not cite it as the cause of a later disconnect without fresh evidence.
# Shared menu and settings behavior

- All regular menus, controller mapping pickers, selectors, sliders, and confirmation buttons must work with D-pad key events and controller hat-axis events, plus confirm/back, in both KairoDos and Kairo98. Keep navigation behavior in `shared/`. Verify off-screen rows and returning from a subpage, not just the first visible items.
- The user explicitly excludes actual text entry and the touch-only on-screen control layout editor from this requirement. Do not add controller-driven dragging or text-entry keyboards for those excluded flows.
- Keep emulated video-card and 3dfx settings inside Graphics, for both global and per-game settings. Do not add graphics options directly to the top-level settings list.

# Generated catalog guard

- Do not hand-edit bundled files under kairodos/src/main/assets/catalog/dos/ or the public catalog/online-v1.zip and catalog/online-v1.json outputs. Use the catalog generator for bundled data and tools/BuildPublicDosCatalog.ps1 for the sanitized public archive.
- Keep this checkout's versioned pre-commit hook active with git config core.hooksPath .githooks. It checks the public archive against the bundled catalog before a direct commit to main.
- Bundled catalog generation uses private source data unavailable in a clean checkout. Review bundled changes carefully; the local hook can verify the public export but cannot reproduce the private import.
