param([switch] $Check)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$source = Join-Path $PSScriptRoot '../kairodos/src/main/assets/catalog/dos'
$outputs = @(
    (Join-Path $PSScriptRoot '../catalog/online-v1.zip'),
    (Join-Path $PSScriptRoot '../shared/catalog/dos/online-v1.zip')
)
$names = @(0..255 | ForEach-Object { '{0:x2}.json' -f $_ }) +
    @('folders.json', 'controller-profiles-v1.json')

function Write-PublicJson([System.Text.Json.JsonElement] $value,
                          [System.Text.Json.Utf8JsonWriter] $writer) {
    if ($value.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $writer.WriteStartObject()
        foreach ($property in $value.EnumerateObject()) {
            if ($property.Name -eq 'description') { continue }
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

if (-not $Check) {
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
    Copy-Item -LiteralPath $path -Destination $outputs[1] -Force
}

foreach ($path in $outputs) { Test-Archive $path }
Write-Host 'Public DOS catalog contains only sanitized metadata and matches both archives.'
