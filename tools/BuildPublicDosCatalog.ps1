param([switch] $Check)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$source = Join-Path $PSScriptRoot '../kairodos/src/main/assets/catalog/dos'
$outputs = @(
    (Join-Path $PSScriptRoot '../catalog/online-v1.zip'),
    (Join-Path $PSScriptRoot '../shared/catalog/dos/online-v1.zip'),
    (Join-Path $PSScriptRoot '../catalog/core-v2.zip'),
    (Join-Path $PSScriptRoot '../shared/catalog/dos/core-v2.zip')
)
$metadataOutputs = @($outputs | ForEach-Object { [IO.Path]::ChangeExtension($_, '.json') })
$shardNames = @(0..255 | ForEach-Object { '{0:x2}.json' -f $_ })
$names = $shardNames + @('folders.json', 'controller-profiles-v1.json',
    'hidden-index-v1.json', 'core-v2.json')
$review = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../catalog/metadata-review-v1.json') -Raw |
    ConvertFrom-Json -AsHashtable
if ($review.schemaVersion -ne 1) { throw 'Unsupported metadata review schema' }
$excluded = (Get-Content (Join-Path $PSScriptRoot '../catalog/core-review-v1.json') -Raw | ConvertFrom-Json -AsHashtable).excluded
$reviewShards = @{}
foreach ($item in $review.records) {
    $prefix = $item.contentId.Split(':')[1].Substring(0, 2)
    if (-not $reviewShards.ContainsKey($prefix)) {
        $reviewShards[$prefix] = Get-Content -LiteralPath (Join-Path $source "$prefix.json") -Raw |
            ConvertFrom-Json -AsHashtable
    }
    $record = $reviewShards[$prefix].games[$item.contentId]
    if ($item.variant) { $record = $record.variants[$item.variant] }
    if ($null -eq $record) { throw "Missing reviewed record: $($item.contentId)" }
    foreach ($field in $item.fields.Keys) {
        $matchesReview = if ($field -eq 'heart') {
            (($record.tags -ccontains '♥') -eq $item.fields.heart)
        } else { $record[$field] -ceq $item.fields[$field] }
        if (-not $matchesReview) {
            throw "Regenerate reviewed metadata with tools/dos_catalog_review.py: $($item.contentId) / $field"
        }
    }
}
if ($Check) {
    & (Join-Path $PSScriptRoot 'GenerateDosControllerProfiles.ps1') -Check
} else {
    & (Join-Path $PSScriptRoot 'GenerateDosControllerProfiles.ps1')
}

function Get-HiddenIndexBytes {
    $hidden = [ordered]@{}
    foreach ($name in $shardNames) {
        $shard = Get-Content -LiteralPath (Join-Path $source $name) -Raw |
            ConvertFrom-Json -AsHashtable
        foreach ($id in @($shard.games.Keys | Sort-Object -CaseSensitive)) {
            $record = $shard.games[$id]
            if ($record.ContainsKey('hidden') -and $record.hidden -is [bool]) {
                $hidden[$id] = $record.hidden
            }
        }
    }
    $json = [ordered]@{ schemaVersion = 1; hidden = $hidden } |
        ConvertTo-Json -Compress -Depth 4
    return ,[Text.Encoding]::UTF8.GetBytes("$json`n")
}

function Write-PublicJson([System.Text.Json.JsonElement] $value,
                          [System.Text.Json.Utf8JsonWriter] $writer) {
    if ($value.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $writer.WriteStartObject()
        foreach ($property in $value.EnumerateObject()) {
            if ($property.Name -eq 'artwork') {
                $art = $property.Value
                if ($art.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) {
                    throw 'Invalid artwork object'
                }
                $fields = @($art.EnumerateObject() | ForEach-Object Name)
                if ($fields.Count -gt 0) {
                    if ($fields.Count -ne 3 -or @($fields | Where-Object { $_ -notin @('id', 'variant', 'kinds') }).Count -gt 0 -or
                        $art.GetProperty('id').GetString() -cnotmatch '^[0-9a-f]{64}$' -or
                        $art.GetProperty('variant').GetString() -cnotmatch '^[0-9a-f]{12}$' -or
                        $art.GetProperty('kinds').GetInt32() -notin @(1, 2, 3)) {
                        throw 'Regenerate compact artwork with tools/dos_catalog_review.py'
                    }
                }
            }
            # Empty imported notes are not valid description overrides. Keep all
            # nonempty descriptions, including the reviewed editorial rewrites.
            if ($property.Name -eq 'description' -and
                $property.Value.ValueKind -eq [System.Text.Json.JsonValueKind]::String -and
                [string]::IsNullOrWhiteSpace($property.Value.GetString())) { continue }
            $writer.WritePropertyName($property.Name)
            Write-PublicJson $property.Value $writer
        }
        $writer.WriteEndObject()
    } elseif ($value.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        $writer.WriteStartArray()
        foreach ($item in $value.EnumerateArray()) {
            Write-PublicJson $item $writer
        }
        $writer.WriteEndArray()
    } else {
        $value.WriteTo($writer)
    }
}

function Get-PublicBytes([string] $name) {
    $bytes = [IO.File]::ReadAllBytes((Join-Path $source $name))
    $document = [System.Text.Json.JsonDocument]::Parse([Text.Encoding]::UTF8.GetString($bytes))
    $stream = [IO.MemoryStream]::new()
    try {
        $writer = [System.Text.Json.Utf8JsonWriter]::new($stream)
        try { Write-PublicJson $document.RootElement $writer; $writer.Flush() }
        finally { $writer.Dispose() }
        return ,$stream.ToArray()
    } finally { $stream.Dispose(); $document.Dispose() }
}

function Test-Archive([string] $path) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($path)
    try {
        if ($archive.Entries.Count -ne $names.Count) { throw "Wrong entry count: $path" }
        foreach ($name in $names) {
            $entry = $archive.GetEntry($name)
            if ($null -eq $entry) { throw "Missing $name in $path" }
            $stream = $entry.Open()
            $copy = [IO.MemoryStream]::new()
            try { $stream.CopyTo($copy) }
            finally { $stream.Dispose() }
            $expected = Get-PublicBytes $name
            if (-not [System.Linq.Enumerable]::SequenceEqual[byte]($copy.ToArray(), $expected)) {
                throw "Public catalog differs from sanitized source: $path / $name"
            }
            $copy.Dispose()
        }
    } finally { $archive.Dispose() }
}

function Get-MetadataBytes([string] $path) {
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $size = ([IO.FileInfo]::new([IO.Path]::GetFullPath($path))).Length
    $archiveName = [IO.Path]::GetFileName($path)
    return ,[Text.Encoding]::UTF8.GetBytes(
        "{`"schemaVersion`":1,`"archive`":`"$archiveName`",`"sha256`":`"$hash`",`"size`":$size}`n")
}

if (-not $Check) {
    [IO.File]::WriteAllBytes((Join-Path $source 'hidden-index-v1.json'),
        (Get-HiddenIndexBytes))
    $path = $outputs[0]
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    $temporary = "$path.tmp"
    try {
        $archive = [System.IO.Compression.ZipFile]::Open($temporary,
            [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($name in $names) {
                $entry = $archive.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
                $stream = $entry.Open()
                try {
                    $bytes = Get-PublicBytes $name
                    $stream.Write($bytes, 0, $bytes.Length)
                } finally { $stream.Dispose() }
            }
        } finally { $archive.Dispose() }
        Move-Item -LiteralPath $temporary -Destination $path -Force
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary } }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputs[1])) | Out-Null
    foreach ($destination in $outputs[1..($outputs.Count - 1)]) { Copy-Item -LiteralPath $path -Destination $destination -Force }
    for ($index = 0; $index -lt $outputs.Count; $index++) {
        [IO.File]::WriteAllBytes($metadataOutputs[$index], (Get-MetadataBytes $outputs[$index]))
    }
}

$hiddenPath = Join-Path $source 'hidden-index-v1.json'
if (-not [System.Linq.Enumerable]::SequenceEqual[byte](
    [IO.File]::ReadAllBytes($hiddenPath), (Get-HiddenIndexBytes))) {
    throw "DOS hidden index differs from catalog shards: $hiddenPath"
}

for ($index = 0; $index -lt $outputs.Count; $index++) {
    Test-Archive $outputs[$index]
    $expected = Get-MetadataBytes $outputs[$index]
    $actual = [IO.File]::ReadAllBytes($metadataOutputs[$index])
    if (-not [System.Linq.Enumerable]::SequenceEqual[byte]($actual, $expected)) {
        throw "Catalog revision metadata does not match archive: $($outputs[$index])"
    }
}
Write-Host 'Public DOS catalog and revision metadata match both sanitized archives.'

python (Join-Path $PSScriptRoot 'build_catalog_packages.py') --check
if ($LASTEXITCODE -ne 0) { throw 'Catalog partitions are stale' }
python (Join-Path $PSScriptRoot '../shared/tools/audit_core_catalog.py') dos
if ($LASTEXITCODE -ne 0) { throw 'Clean core audit failed' }
