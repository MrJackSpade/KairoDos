# Builds pinned Android libraries from the corresponding source shipped with KairoDos.
param([string] $AndroidSdk, [switch] $VerifyOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceRoot = Join-Path $projectRoot 'third_party/staging-deps'
$manifest = Get-Content -LiteralPath (Join-Path $sourceRoot 'sources.json') -Raw | ConvertFrom-Json
function Get-Sha256([string] $Path) {
    $stream = [IO.File]::OpenRead($Path)
    try { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant() }
    finally { $stream.Dispose() }
}
foreach ($source in $manifest.sources) {
    $path = Join-Path $sourceRoot "sources/$($source.file)"
    if ((Get-Sha256 $path) -ne $source.sha256) { throw "Source digest mismatch: $($source.file)" }
}
if ($VerifyOnly) { Write-Host 'Pinned Staging dependency sources verified.'; exit }

$versions = @{}
Get-Content -LiteralPath (Join-Path $projectRoot 'shared/gradle/android-versions.properties') |
    ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') { $versions[$Matches[1]] = $Matches[2] } }
if (-not $AndroidSdk) {
    $AndroidSdk = $env:ANDROID_HOME
    if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
    if (-not $AndroidSdk -and $IsWindows) { $AndroidSdk = 'D:\android-sdk' }
}
$ndk = Join-Path $AndroidSdk "ndk/$($versions.ndk)"
if (-not (Test-Path -LiteralPath $ndk)) { throw "Android NDK not found: $ndk" }
$buildRoot = Join-Path $projectRoot '.downloads/staging-deps'
$toolRoot = Join-Path $buildRoot 'vcpkg'
$downloads = Join-Path $buildRoot 'downloads'
$prefix = Join-Path $buildRoot 'installed'
New-Item -ItemType Directory -Force -Path $toolRoot, $downloads | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $toolRoot 'scripts/vcpkg-tools.json'))) {
    & tar -xzf (Join-Path $sourceRoot 'sources/vcpkg-6283825.tar.gz') -C $toolRoot --strip-components 1
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract the pinned dependency recipes' }
}
foreach ($source in $manifest.sources) {
    if ($source.file -eq 'vcpkg-6283825.tar.gz') { continue }
    Copy-Item -LiteralPath (Join-Path $sourceRoot "sources/$($source.file)") -Destination $downloads -Force
}
$toolName = if ($IsWindows) { 'vcpkg.exe' } else { 'vcpkg-glibc' }
$tool = @($manifest.tools | Where-Object name -EQ $toolName)[0]
if (-not $tool) { throw "No pinned tool for this host: $toolName" }
$executable = Join-Path $toolRoot $(if ($IsWindows) { 'vcpkg.exe' } else { 'vcpkg' })
if (-not (Test-Path -LiteralPath $executable) -or (Get-Sha256 $executable) -ne $tool.digest.Substring(7)) {
    Invoke-WebRequest -Uri $tool.browser_download_url -OutFile $executable
    if ((Get-Sha256 $executable) -ne $tool.digest.Substring(7)) { throw 'Build tool digest mismatch' }
}
if (-not $IsWindows) { & chmod +x $executable }
$env:ANDROID_NDK_HOME = $ndk
$env:VCPKG_ROOT = $toolRoot
$env:VCPKG_DOWNLOADS = $downloads
# Classic mode otherwise reports an already-installed port as satisfied even
# when its overlay changed. Remove only this generated package before rebuilding;
# vcpkg's binary cache still keys the replacement on the complete recipe ABI.
$speexPort = Join-Path $sourceRoot 'ports/speexdsp'
$speexStamp = Join-Path $buildRoot 'speex-overlay.sha256'
$speexSignature = (Get-ChildItem -LiteralPath $speexPort -File | Sort-Object Name |
    ForEach-Object { "$($_.Name):$(Get-Sha256 $_.FullName)" }) -join "`n"
if (-not (Test-Path -LiteralPath $speexStamp) -or
    (Get-Content -LiteralPath $speexStamp -Raw).TrimEnd() -ne $speexSignature) {
    & $executable remove 'speexdsp:arm64-kairo-android' --classic --disable-metrics --x-install-root $prefix
    if ($LASTEXITCODE -ne 0) { throw 'Could not refresh the generated SpeexDSP overlay package' }
}
& $executable install asio iir1 libmt32emu libpng opusfile fluidsynth sdl2 sdl2-image speexdsp zlib-ng `
    --classic --disable-metrics --triplet arm64-kairo-android `
    --overlay-triplets (Join-Path $sourceRoot 'triplets') `
    --overlay-ports (Join-Path $sourceRoot 'ports') --x-install-root $prefix
if ($LASTEXITCODE -ne 0) { throw "Staging dependency build failed ($LASTEXITCODE)" }
Set-Content -LiteralPath $speexStamp -Value $speexSignature
Write-Host "Android dependency prefix: $prefix/arm64-kairo-android"
