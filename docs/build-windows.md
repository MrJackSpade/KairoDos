# Local Windows debug build

This workstation has Android SDK 36, NDK `28.2.13676358`, and CMake `3.22.1` under `D:\android-sdk`. Gradle 8.13 and the project dependencies are cached under `C:\Users\Service Account\.gradle`.

The checkout path contains a space (`Service Account`). Android `ndk-build` rejects that path for `APP_BUILD_SCRIPT`, so run Gradle through a temporary drive alias. This PowerShell command built `:kairodos:assembleDebug` successfully on September 28, 2026:

```powershell
$env:ANDROID_HOME = 'D:\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\android-sdk'
$env:GRADLE_USER_HOME = 'C:\Users\Service Account\.gradle'
if (Test-Path 'K:\') { throw 'K: is already in use; choose another drive letter' }
subst K: 'C:\Users\Service Account\KairoDos'
if ($LASTEXITCODE -ne 0) { throw 'Could not create K: drive alias' }
$buildExit = 1
try {
    Push-Location 'K:\'
    try {
        .\gradlew.bat --no-daemon --offline :kairodos:assembleDebug
        $buildExit = $LASTEXITCODE
    }
    finally { Pop-Location }
}
finally { subst K: /D }
if ($buildExit -ne 0) { throw "Gradle failed with exit code $buildExit" }
```

Use another unused drive letter if `K:` is occupied. The output is `kairodos\build\outputs\apk\debug\kairodos-debug.apk`. Initialize the pinned `shared/` submodule before running the wrapper. The product wrapper delegates to its Gradle wrapper, and CI uses its Android setup action. Toolchain versions and conventions are documented in `shared/docs/android-build.md`.

For RGDS deployment, run `pwsh -File tools/InstallRgDs.ps1 -Apk kairodos/build/outputs/apk/debug/kairodos-debug.apk`. The helper discovers its current wireless debugging port, verifies the device model, and turns off Wi-Fi power saving before transfer. Device logs showed the Wi-Fi link dropping under large APK transfers when power saving was on; Android then disabled wireless debugging. The setting may reset after Wi-Fi reconnects or device restarts, so the helper applies it on every install.
