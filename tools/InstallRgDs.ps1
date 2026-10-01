param([Parameter(Mandatory)] [string] $Apk)

$ErrorActionPreference = 'Stop'
$adb = 'D:\android-sdk\platform-tools\adb.exe'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
if (-not (Test-Path -LiteralPath $adb)) { throw "ADB not found: $adb" }

function Get-RgDsSerial {
    $devices = & $adb devices -l
    if ($LASTEXITCODE -ne 0) { throw 'Could not list ADB devices.' }
    $candidates = @($devices | ForEach-Object {
        if ($_ -match '^(\S+)\s+device\b.*\bmodel:RG_DS\b') { $Matches[1] }
    })
    # USB avoids large-transfer Wi-Fi failures. Fixed TCP survives TLS restarts.
    foreach ($candidate in $candidates) {
        if ($candidate -eq 'dd437d64c337800f') { return $candidate }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -match ':5555$') { return $candidate }
    }
    return $candidates | Select-Object -First 1
}

$serial = Get-RgDsSerial
if ($serial -ne 'dd437d64c337800f' -and $serial -notmatch ':5555$') {
    # User-authorized persistent endpoint; do not enable TCP debugging here.
    $fixedEndpoint = '192.168.1.118:5555'
    if ($serial -match '^(\d+\.\d+\.\d+\.\d+):\d+$') {
        $fixedEndpoint = "$($Matches[1]):5555"
    }
    & $adb connect $fixedEndpoint | Out-Host
    $serial = Get-RgDsSerial
}
if (-not $serial) {
    # The port changes whenever Android restarts wireless debugging.
    $services = & $adb mdns services
    if ($LASTEXITCODE -ne 0) { throw 'Could not discover wireless ADB services.' }
    foreach ($line in $services) {
        if ($line -notmatch '^adb-dd437d64c337800f\b.*\s(\d+\.\d+\.\d+\.\d+:\d+)\s*$') {
            continue
        }
        $endpoint = $Matches[1]
        & $adb connect $endpoint | Out-Host
        $serial = Get-RgDsSerial
        if ($serial) { break }
    }
}
if (-not $serial) { throw 'RGDS is not reachable by USB or wireless debugging.' }

$model = (& $adb -s $serial shell getprop ro.product.model).Trim()
if ($LASTEXITCODE -ne 0 -or $model -ne 'RG DS') {
    throw "ADB device $serial is not the RGDS (model: $model)."
}

# This device's Realtek Wi-Fi link dropped under large APK transfers with
# power saving enabled; Android then disabled wireless debugging entirely.
& $adb -s $serial shell iw dev wlan0 set power_save off
if ($LASTEXITCODE -ne 0) { throw 'Could not disable RGDS Wi-Fi power saving.' }
$powerSave = (& $adb -s $serial shell iw dev wlan0 get power_save).Trim()
if ($LASTEXITCODE -ne 0 -or $powerSave -ne 'Power save: off') {
    throw "RGDS Wi-Fi power saving is still enabled: $powerSave"
}

Write-Host "Installing $apkPath on RGDS ($serial) with Wi-Fi power saving off."
# These APKs have v4 signatures, so adb otherwise attempts incremental install
# before falling back to streaming on the RGDS. Use the normal complete update
# directly; it also avoids leaving an incremental transfer across Wi-Fi recovery.
& $adb -s $serial install --no-incremental -r $apkPath
if ($LASTEXITCODE -ne 0) { throw 'RGDS APK installation failed.' }
