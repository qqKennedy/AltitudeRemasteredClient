<#
Upscales exported stock textures (PNGs named by content key, from SpriteExport) for /opt hdsprites:

  .\sprite-tools\upscale.ps1 -Stock <dir> -Out <dir> -Waifu2x <waifu2x-ncnn-vulkan folder> [-Tiled key,key] [-Gpu 0]

1. waifu2x-ncnn-vulkan, cunet model, 4x, no denoise. Textures the game repeats (-Tiled) are upscaled as a 3x3 tiling
   and the centre cut out again (sprite-tools/TileWrap), so tiles meet without seams.
2. Back-projection (sprite-tools/BackProject): the result is corrected until shrinking it back gives the stock pixels.
3. Fidelity (sprite-tools/Fidelity) on the raw and on the final result; report in <Out>\..\<name>-fidelity.

On the RTX 5090 waifu2x needs tile size 128: other sizes silently write blank or cropped images, which the raw
fidelity check catches (RGB error far over the limit). Exit 1 when any final texture is over the limits.
#>
param(
    [Parameter(Mandatory)][string]$Stock,
    [Parameter(Mandatory)][string]$Out,
    [Parameter(Mandatory)][string]$Waifu2x,
    [string[]]$Tiled = @(),
    [int]$Gpu = 0, [int]$Tile = 128,
    [string]$GameDir = (& "$PSScriptRoot\..\tools\game-dir.ps1")
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
if (-not $GameDir) { throw 'Altitude not found: pass -GameDir or set ALTITUDE_DIR' }
$java = Join-Path $GameDir 'runtime\bin\java.exe'
$tools = "$root\out\sprite-tools"
& $java -jar "$root\tools\ecj-3.38.0.jar" -17 -nowarn -d $tools "$root\sprite-tools\TileWrap.java" "$root\sprite-tools\BackProject.java" "$root\sprite-tools\Fidelity.java" | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Compiling sprite-tools failed' }

$work = "$Out.work"
foreach ($d in "$work\in", "$work\raw", $Out) { New-Item -ItemType Directory -Force $d | Out-Null }
foreach ($f in Get-ChildItem "$Stock\*.png") {
    $key = $f.BaseName
    if ($Tiled -contains $key) { & $java -cp $tools TileWrap tile $f.FullName "$work\in\$key.png" }
    else { Copy-Item $f.FullName "$work\in\$key.png" -Force }
}
$ErrorActionPreference = 'Continue'  # waifu2x prints its GPU list on stderr
& "$Waifu2x\waifu2x-ncnn-vulkan.exe" -i "$work\in" -o "$work\raw" -m "$Waifu2x\models-cunet" -s 4 -n -1 -g $Gpu -t $Tile -f png 2>&1 | Out-Null
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw "waifu2x failed ($LASTEXITCODE)" }
foreach ($key in $Tiled) { if (Test-Path "$work\raw\$key.png") { & $java -cp $tools TileWrap crop "$work\raw\$key.png" "$work\raw\$key.png" } }

$name = Split-Path $Out -Leaf
& $java -cp $tools Fidelity $Stock "$work\raw" "$Out\..\$name-fidelity-raw" 20 20 | Out-Host
& $java -cp $tools BackProject $Stock "$work\raw" $Out | Out-Host
& $java -cp $tools Fidelity $Stock $Out "$Out\..\$name-fidelity" | Out-Host
exit $LASTEXITCODE
