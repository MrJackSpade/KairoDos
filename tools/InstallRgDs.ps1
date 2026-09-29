param([Parameter(Mandatory)] [string] $Apk)

$ErrorActionPreference = 'Stop'
$adb = 'D:\android-sdk\platform-tools\adb.exe'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
if (-not (Test-Path -LiteralPath $adb)) { throw "ADB not found: $adb" }

function Get-RgDsSerial {
    $devices = & $adb devices -l
    if ($LASTEXITCODE -ne 0) { throw 'Could not list ADB devices.' }
    foreach ($line in $devices) {
        if ($line -match '^(\S+)\s+device\b.*\bmodel:RG_DS\b') { return $Matches[1] }
    }
    return $null
}

$serial = Get-RgDsSerial
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
& $adb -s $serial install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw 'RGDS APK installation failed.' }
