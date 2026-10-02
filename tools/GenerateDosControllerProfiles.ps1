param([switch] $Check, [switch] $Rebuild)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$catalog = Join-Path $project 'kairodos/src/main/assets/catalog/dos'
$ledger = Join-Path $project 'catalog/controller-research/recommendations.json'
$asset = Join-Path $catalog 'controller-profiles-v1.json'
$recommendations = Get-Content -LiteralPath $ledger -Raw | ConvertFrom-Json
if ($recommendations.schemaVersion -ne 1) { throw 'Unsupported controller recommendation schema.' }

$virtualControls = @('up','down','left','right','a','b','x','y','l1','r1','l2','r2',
    'start','select','menu','lsup','lsdown','lsleft','lsright','rsup','rsdown','rsleft','rsright')
$joystickControls = @('b','y','select','start','up','down','left','right','a','x','l1','r1','l2','r2',
    'joy1up','joy1down','joy1left','joy1right','joy2up','joy2down','joy2left','joy2right')
$mouseControls = @('moveUp','moveDown','moveLeft','moveRight','leftButton','rightButton')
$actions = @('menu','pause','restart','exit')
$cycleInputs = @('virtual:l1','virtual:r1','virtual:l2','virtual:r2')
$guestKeyCodes = [Collections.Generic.HashSet[int]]::new()
foreach ($code in @((48..57) + (97..122) + (256..293) +
        @(8,9,13,27,32,39,44,45,46,47,59,61,91,92,93,96,127,301,303,304,305,306,307,308))) {
    [void]$guestKeyCodes.Add($code)
}

function Test-Preset($preset, [string] $profileId, [switch] $AllowSticks) {
    if (-not $preset -or -not ($preset.bindings -is [array]) -or $preset.bindings.Count -gt 128) {
        throw "Invalid preset bindings: $profileId"
    }
    $seen = [Collections.Generic.HashSet[string]]::new()
    foreach ($binding in $preset.bindings) {
        $source = [string]$binding.input
        if (-not $source -or -not $seen.Add($source)) { throw "Missing or duplicate input in ${profileId}: $source" }
        if ($source -match '^axis:|^virtual:l$|^virtual:r$' -or
            (-not $AllowSticks -and $source -match '^virtual:(ls|rs)')) {
            throw "Analog or unsupported controller input in ${profileId}: $source"
        }
        if ($source.StartsWith('virtual:') -and $source.Substring(8) -notin $virtualControls) {
            throw "Unknown virtual controller input in ${profileId}: $source"
        }
        if ($source.StartsWith('hat:') -and $source -notmatch '^hat:\d{1,3}:[+-]$') {
            throw "Invalid hat input in ${profileId}: $source"
        }
        if ($source.StartsWith('button:') -and $source -notmatch '^button:\d{1,4}$') {
            throw "Invalid button input in ${profileId}: $source"
        }
        if (-not ($source.StartsWith('virtual:') -or $source.StartsWith('hat:') -or $source.StartsWith('button:'))) {
            throw "Unsupported controller input in ${profileId}: $source"
        }
        $targetNames = @('keys','action','joystick','mouse','cycleKeys')
        $targets = @($targetNames | Where-Object { $binding.PSObject.Properties.Name -contains $_ })
        if ($targets.Count -ne 1) { throw "Binding must have exactly one target in ${profileId}: $source" }
        $target = $targets[0]
        $value = $binding.$target
        if ($target -in @('keys','cycleKeys')) {
            $keys = @($value)
            $minimum = if ($target -eq 'keys') { 1 } else { 2 }
            $maximum = if ($target -eq 'keys') { 4 } else { 16 }
            if ($keys.Count -lt $minimum -or $keys.Count -gt $maximum -or
                @($keys | Where-Object { $_ -notin $guestKeyCodes }).Count -gt 0 -or
                @($keys | Select-Object -Unique).Count -ne $keys.Count) {
                throw "Invalid guest key mapping in ${profileId}: $source"
            }
            if ($target -eq 'cycleKeys' -and $source -notin $cycleInputs) {
                throw "Invalid cycle-key input in ${profileId}: $source"
            }
        } elseif ($target -eq 'action' -and $value -notin $actions) {
            throw "Unsupported app action in ${profileId}: $value"
        } elseif ($target -eq 'joystick' -and $value -notin $joystickControls) {
            throw "Unsupported joystick target in ${profileId}: $value"
        } elseif ($target -eq 'mouse' -and $value -notin $mouseControls) {
            throw "Unsupported mouse target in ${profileId}: $value"
        }
        if ($binding.PSObject.Properties.Name -contains 'mouseSpeed') {
            if ($target -ne 'mouse' -or $value -notlike 'move*' -or
                $binding.mouseSpeed -lt 0.1 -or $binding.mouseSpeed -gt 20) {
                throw "Invalid mouse speed in ${profileId}: $source"
            }
        }
    }
}

$currentIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($prefix in 0..255) {
    $name = '{0:x2}.json' -f $prefix
    $shard = Get-Content -LiteralPath (Join-Path $catalog $name) -Raw | ConvertFrom-Json
    foreach ($contentId in $shard.games.PSObject.Properties.Name) { [void]$currentIds.Add($contentId) }
}

$assignments = [ordered]@{}
$presets = [ordered]@{}
if ($Rebuild) {
    $inventoryPath = Join-Path $project 'catalog/controller-research/current-games.jsonl'
    $doomIds = @(
        foreach ($line in [IO.File]::ReadLines($inventoryPath)) {
            if (-not $line) { continue }
            $row = $line | ConvertFrom-Json
            if ($row.researchStatus -eq 'existing' -and $row.mappingProfile -eq 'doom-v1') {
                [string]$row.contentId
            }
        }
    )
    if ($doomIds.Count -ne 10) { throw "Expected 10 existing Doom records in current research inventory, found $($doomIds.Count)." }
    $existingProfiles = [ordered]@{ 'doom-v1' = $doomIds }
} else {
    $existing = Get-Content -LiteralPath $asset -Raw | ConvertFrom-Json
    $existingProfiles = $existing.profiles
    $doomIds = @($existingProfiles.'doom-v1')
    if ($existing.PSObject.Properties.Name -contains 'assignments') {
        foreach ($item in $existing.assignments.PSObject.Properties) {
            if ($currentIds.Contains($item.Name)) { $assignments[$item.Name] = $item.Value }
        }
    }
    if ($existing.PSObject.Properties.Name -contains 'presets') {
        foreach ($item in $existing.presets.PSObject.Properties) { $presets[$item.Name] = $item.Value }
    }
}
$bindingOwners = @{}
foreach ($item in $recommendations.profiles.PSObject.Properties) {
    Test-Preset $item.Value $item.Name
    $bindingKey = @($item.Value.bindings | Sort-Object input | ForEach-Object {
        $_ | ConvertTo-Json -Depth 10 -Compress
    }) -join '|'
    if ($bindingOwners.ContainsKey($bindingKey)) {
        throw "Duplicate controller bindings in profiles '$($bindingOwners[$bindingKey])' and '$($item.Name)'; reuse one profile ID."
    }
    $bindingOwners[$bindingKey] = $item.Name
}
foreach ($item in $recommendations.assignments.PSObject.Properties) {
    $profileId = [string]$item.Value
    if ($recommendations.profiles.PSObject.Properties.Name -notcontains $profileId) {
        throw "Assignment references missing profile: $profileId"
    }
    if ($item.Name -in $doomIds) { throw "Recommendation duplicates Doom assignment: $($item.Name)" }
    if (-not $currentIds.Contains($item.Name)) {
        throw "Recommendation does not resolve to a current catalog ID: $($item.Name)"
    }
    $assignments[$item.Name] = $profileId
}
foreach ($item in $recommendations.profiles.PSObject.Properties) { $presets[$item.Name] = $item.Value }
foreach ($id in $doomIds) {
    if ($assignments.Contains($id)) { throw "Doom ID also has a recommendation: $id" }
}
# Reviewed defaults are source data, including both controller configurations.
# They replace old researched assignments; runtime code must not special-case games.
$defaults = Get-Content (Join-Path $project 'catalog/controller-defaults.json') -Raw | ConvertFrom-Json
if ($defaults.schemaVersion -ne 1) { throw 'Unsupported controller defaults schema.' }
foreach ($item in $defaults.assignments.PSObject.Properties) {
    if (-not $currentIds.Contains($item.Name) -or
        $defaults.presets.PSObject.Properties.Name -notcontains $item.Value) {
        throw "Invalid controller default assignment: $($item.Name)"
    }
    $assignments[$item.Name] = $item.Value
}
foreach ($item in $defaults.presets.PSObject.Properties) { $presets[$item.Name] = $item.Value }
$usedProfiles = @( (@($assignments.Values) + @($existingProfiles.PSObject.Properties.Name)) | Sort-Object -Unique)
$finalPresets = [ordered]@{}
foreach ($profileId in $usedProfiles) {
    if (-not $presets.Contains($profileId)) { throw "Assigned profile has no preset: $profileId" }
    $preset = $presets[$profileId]
    if ($preset.PSObject.Properties.Name -contains 'defaults') {
        $variants = $preset.defaults
        if ($variants.PSObject.Properties.Name -notcontains 'withoutSticks' -or
            @($variants.PSObject.Properties.Name | Where-Object { $_ -notin @('withoutSticks','withSticks') }).Count -gt 0) {
            throw "Invalid controller defaults: $profileId"
        }
        foreach ($variant in $variants.PSObject.Properties) {
            Test-Preset ([pscustomobject]@{ bindings = $variant.Value }) $profileId -AllowSticks:($variant.Name -eq 'withSticks')
        }
    } else {
        $preset | Add-Member -NotePropertyName defaults -NotePropertyValue ([ordered]@{ withoutSticks = @($preset.bindings) })
    }
    $finalPresets[$profileId] = $preset
}
$result = [ordered]@{
    schemaVersion = 1
    profiles = $existingProfiles
    assignments = $assignments
    presets = $finalPresets
}
$expected = ($result | ConvertTo-Json -Depth 40) + "`n"
if ($Check) {
    $actual = [IO.File]::ReadAllText((Resolve-Path $asset).Path)
    if ($actual -cne $expected) { throw 'Controller profile asset is stale; run without -Check to regenerate it.' }
} else {
    $temporary = "$asset.tmp"
    [IO.File]::WriteAllText($temporary, $expected, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $asset -Force
    Write-Output "Generated $($assignments.Count) controller assignments and $($finalPresets.Count) presets."
}
