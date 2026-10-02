param([switch] $All)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$source = 'kairodos/src/main/assets/catalog/dos/'
$public = @('catalog/online-v1.zip', 'catalog/online-v1.json', 'catalog/core-v2.zip', 'catalog/core-v2.json', 'catalog/excluded-ids-v1.json', 'catalog/optional/kairodos-adult-v1.zip', 'catalog/optional/kairodos-adult-v1.meta.json')
$public += @('catalog/artwork-exclusions-v1.json', 'catalog/optional/kairodos-art.nsfw.meta.json')
$public += @(git ls-files --cached -- catalog/parts/)
$inputs = @('catalog/artwork-release-v1.json', 'catalog/complete-input-v1.zip', 'catalog/core-review-v1.json', 'tools/build_catalog_packages.py', 'catalog/metadata-review-v1.json', 'tools/dos_catalog_review.py',
    'tools/dos_artwork.py', 'tools/generate_dos_catalog.py', 'tools/BuildPublicDosCatalog.ps1')
$staged = @(git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect staged files.' }
if (-not $All -and -not @($staged | Where-Object {
    $_.StartsWith($source) -or $_ -in $public -or $_ -in $inputs
}).Count) {
    exit 0
}

$unstaged = @(git diff --name-only -- $source @public @inputs)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect catalog worktree.' }
if ($unstaged.Count) {
    throw "Stage or discard unstaged catalog changes before committing: $($unstaged -join ', ')"
}

& (Join-Path $PSScriptRoot 'BuildPublicDosCatalog.ps1') -Check
