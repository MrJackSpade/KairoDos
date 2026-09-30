# Local Windows debug build

SDK 36, NDK 28.2.13676358 and CMake 3.22.1 are at `D:\android-sdk`. JDK 17, PowerShell 7, Python 3 and Visual Studio C++ host tools are required. Initialize the pinned shared submodule first.

The bootstrap verifies shipped source archives and builds static API 26 libraries. Its pinned vcpkg tool and host tools need network access on first use. Outputs are ignored under `.downloads/staging-deps/`.

Use an unused drive alias to avoid spaces:

```powershell
$env:ANDROID_HOME = 'D:\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\android-sdk'
$env:GRADLE_USER_HOME = 'C:\Users\Service Account\.gradle'
if (Test-Path 'K:\') { throw 'Choose an unused drive letter' }
subst K: 'C:\Users\Service Account\KairoDos'
if ($LASTEXITCODE -ne 0) { throw 'Drive mapping failed' }
try {
    Push-Location 'K:\'
    try {
        pwsh -NoProfile -File tools/PrepareStagingDependencies.ps1
        if ($LASTEXITCODE -ne 0) { throw 'Dependency build failed' }
        python tools/GenerateStagingNotices.py
        if ($LASTEXITCODE -ne 0) { throw 'Notice generation failed' }
        .\gradlew.bat --no-daemon --offline :kairodos:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'Gradle failed' }
    } finally { Pop-Location }
} finally { subst K: /D }
```

APK: `kairodos/build/outputs/apk/debug/kairodos-debug.apk`. Keep package `com.loxifi.kairodos`, existing signing key, and `adb install -r`. Never create another package or uninstall to bypass a signature mismatch.

RGDS: `pwsh tools/InstallRgDs.ps1 -Apk <path>` prefers USB, then discovers the authorized wireless endpoint and disables Wi-Fi power saving. A stable ADB port does not prevent Wi-Fi link failures.

CI uses the same bootstrap/build on Ubuntu. See shared/docs/android-build.md for common frontend conventions.
