param([switch] $All)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$source = 'kairodos/src/main/assets/catalog/dos/'
$public = @('catalog/online-v1.zip', 'catalog/online-v1.json')
$staged = @(git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect staged files.' }
if (-not $All -and -not @($staged | Where-Object {
    $_.StartsWith($source) -or $_ -in $public
}).Count) {
    exit 0
}

$unstaged = @(git diff --name-only -- $source @public)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect catalog worktree.' }
if ($unstaged.Count) {
    throw "Stage or discard unstaged catalog changes before committing: $($unstaged -join ', ')"
}

& (Join-Path $PSScriptRoot 'BuildPublicDosCatalog.ps1') -Check
