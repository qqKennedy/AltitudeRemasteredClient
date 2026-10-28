<#
Writes the playtest bundle out\Altitude-REMASTERED-<build ID>.zip from the last build.

  .\build.ps1; .\bundle.ps1

The zip holds, at its top level: Altitude-1.1.9-REMASTERED.jar and Altitude-1.1.9-hdsprites.zip from out\, the installer
install\remastered.ps1 (with tools\game-dir.ps1, which it uses to find Altitude through Steam) and TESTERS.md. The
build ID is read from the jar (build.ps1 writes it), so the name always matches the jar inside. Warns when the jar
was built from uncommitted changes (-dirty). Writes only into out\.
#>
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'out'
$jar = Join-Path $out 'Altitude-1.1.9-REMASTERED.jar'
$files = [ordered]@{
    'Altitude-1.1.9-REMASTERED.jar' = $jar
    'Altitude-1.1.9-hdsprites.zip'  = Join-Path $out 'Altitude-1.1.9-hdsprites.zip'
    'remastered.ps1'                = Join-Path $root 'install\remastered.ps1'
    'game-dir.ps1'                  = Join-Path $root 'tools\game-dir.ps1'
    'TESTERS.md'                    = Join-Path $root 'TESTERS.md'
}
foreach ($f in $files.Values) { if (-not (Test-Path $f)) { throw "Missing $f (run build.ps1 first)" } }

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entry = $zip.GetEntry('em/altitude/game/scene/player/build/id.txt')
    if (-not $entry) { throw "No build ID in $jar (rebuild with build.ps1)" }
    $reader = New-Object IO.StreamReader($entry.Open())
    $buildId = $reader.ReadToEnd().Trim()
    $reader.Dispose()
} finally {
    $zip.Dispose()
}
if ($buildId -like '*-dirty-*') { Write-Warning "Build $buildId has uncommitted changes; commit and rebuild for a release." }

$bundle = Join-Path $out "Altitude-REMASTERED-$buildId.zip"
if (Test-Path $bundle) { Remove-Item $bundle }
$zip = [IO.Compression.ZipFile]::Open($bundle, 'Create')
try {
    foreach ($name in $files.Keys) {
        # The jar and the pack are already compressed.
        $level = if ($name -like '*.ps1' -or $name -like '*.md') { 'Optimal' } else { 'NoCompression' }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $files[$name], $name, $level) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Write-Host "Wrote $bundle ($([math]::Round((Get-Item $bundle).Length / 1MB)) MB)"
