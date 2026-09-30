param(
    [string] $Serial = '192.168.1.118:5555',
    [Parameter(Mandatory)] [string] $Output,
    [int] $Seconds = 200
)
$ErrorActionPreference = 'Stop'
$adb = 'D:\android-sdk\platform-tools\adb.exe'
if (Test-Path -LiteralPath $Output) { throw "Observation output already exists: $Output" }
$deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
while ([DateTime]::UtcNow -lt $deadline) {
    # One shell request limits sampling gaps that can overflow SF's 127 records.
    $raw = @(& $adb -s $Serial shell 'cat /proc/uptime; for z in /sys/class/thermal/thermal_zone*; do cat $z/type $z/temp; done; for z in /sys/class/thermal/cooling_device*; do cat $z/type $z/cur_state; done; cat /sys/class/power_supply/battery/status /sys/class/power_supply/battery/current_now /sys/class/power_supply/battery/voltage_now; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq /sys/class/devfreq/fde60000.gpu/cur_freq; p=$(pidof com.loxifi.kairodos); if [ -n "$p" ]; then cat /proc/$p/stat; fi; echo KAIRO_LAYER; l=$(dumpsys SurfaceFlinger --list | grep "^SurfaceView\[com.loxifi.kairodos/.*VideoPresentationActivity\](BLAST)" | tail -n 1); echo "$l"; echo KAIRO_FRAMES; if [ -n "$l" ]; then dumpsys SurfaceFlinger --latency "$l"; fi')
    if ($LASTEXITCODE) { throw 'Device observation failed' }
    $layerMarker = [Array]::IndexOf($raw, 'KAIRO_LAYER')
    $frameMarker = [Array]::IndexOf($raw, 'KAIRO_FRAMES')
    if ($layerMarker -lt 1 -or $frameMarker -ne $layerMarker + 2) {
        throw 'Unexpected observation format'
    }
    $layer = $raw[$layerMarker + 1]
    $frames = @(if ($frameMarker + 1 -lt $raw.Count) { $raw[($frameMarker + 1)..($raw.Count - 1)] })
    [pscustomobject]@{ hostUtc = [DateTime]::UtcNow.ToString('o'); telemetry = @($raw[0..($layerMarker - 1)]);
        layer = $layer; frameTimings = $frames } | ConvertTo-Json -Compress -Depth 4 |
        Add-Content -LiteralPath $Output -Encoding utf8
    Start-Sleep -Milliseconds 200
}
