<#
Exports every stock texture of the game's image packs as a PNG named by its content key (sprite-tools/SpriteExport),
plus an index of where each one is in the game, for upscale.ps1 and pack.ps1. The game folder is only read.

  .\sprite-tools\export.ps1 [-GameDir <game>] [-Out out\hd-sprites\export-all]   (index: <Out>.txt)
#>
param(
    [string]$GameDir = (& "$PSScriptRoot\..\tools\game-dir.ps1"),
    [string]$Out = "$PSScriptRoot\..\out\hd-sprites\export-all"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
if (-not $GameDir) { throw 'Altitude not found: pass -GameDir or set ALTITUDE_DIR' }
$java = Join-Path $GameDir 'runtime\bin\java.exe'
$cp = (Get-ChildItem "$root\stock\lib\*.jar").FullName -join ';'
if (-not $cp) { throw 'No stock\lib: run build.ps1 first' }
& $java -jar "$root\tools\ecj-3.38.0.jar" -17 -nowarn -cp $cp -d "$root\out\sprite-tools" "$root\sprite-tools\SpriteExport.java" | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Compiling SpriteExport failed' }
& $java -cp "$cp;$root\out\sprite-tools" SpriteExport "$GameDir\app\resources\dist\.image" $Out > "$Out.txt"
if ($LASTEXITCODE -ne 0) { throw 'Export failed' }
Get-Content "$Out.txt" -Tail 1
