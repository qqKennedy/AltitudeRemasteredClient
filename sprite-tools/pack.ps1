<#
Builds the HD sprite pack, out\Altitude-1.1.9-hdsprites.zip, from the upscaled masters (sprite-tools\upscale.ps1):

  .\sprite-tools\pack.ps1 [-Masters DIR] [-Stock DIR] [-Index FILE] [-Scale 3]

1. Shrinks every master to -Scale times its stock size (sprite-tools/Pack scale; the client derives smaller scales).
2. Fidelity check against the stock textures (sprite-tools/Fidelity); stops if any texture is over the limits.
3. Writes the pack with its manifest (sprite-tools/Pack zip): texture keys, SHA-256 of every PNG, where each texture
   is in the game (from SpriteExport's index), and how the art was made. Reproducible: same PNGs, same pack.. Pins the pack in the client: writes the manifest's SHA-256 into SpritePack.java (rebuild the client after).
Needs a build (out\classes): the pack uses the client's own resampler and checksum code.
#>
param(
    [string]$Masters = "$PSScriptRoot\..\art\hd-sprites\masters",
    [string]$Stock = "$PSScriptRoot\..\art\hd-sprites\stock",
    [string]$Index = "$PSScriptRoot\..\art\hd-sprites\index.txt",
    [int]$Scale = 3,
    [string]$GameDir = (& "$PSScriptRoot\..\tools\game-dir.ps1"),
    [string]$Pipeline = 'waifu2x-ncnn-vulkan 20250915 models-cunet -s 4 -n -1; BackProject 20 iterations; area average to scale'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
if (-not $GameDir) { throw 'Altitude not found: pass -GameDir or set ALTITUDE_DIR' }
$java = Join-Path $GameDir 'runtime\bin\java.exe'
$tools = "$root\out\sprite-tools"
$cp = (@("$root\out\classes") + (Get-ChildItem "$root\stock\lib\*.jar").FullName) -join ';'
& $java -jar "$root\tools\ecj-3.38.0.jar" -17 -nowarn -cp $cp -d $tools "$root\sprite-tools\Pack.java" "$root\sprite-tools\Fidelity.java" | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Compiling sprite-tools failed' }

$stage = "$root\out\hd-sprites\pack-${Scale}x"
if (Test-Path $stage) { Get-ChildItem $stage -File | Remove-Item }
& $java -cp "$tools;$cp" em.altitude.game.scene.player.Pack scale $Masters $stage $Scale 2>&1 | Where-Object { "$_" -notmatch 'SLF4J' } | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Scaling failed' }
& $java -cp $tools Fidelity $Stock $stage "$root\out\hd-sprites\pack-${Scale}x-fidelity" | Out-Host
if ($LASTEXITCODE -ne 0) { throw "Fidelity check failed: see $root\out\hd-sprites\pack-${Scale}x-fidelity" }
& $java -cp "$tools;$cp" em.altitude.game.scene.player.Pack zip $stage $Index "$root\out\Altitude-1.1.9-hdsprites.zip" $Scale $Pipeline 2>&1 | Where-Object { "$_" -notmatch 'SLF4J' } | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Packing failed' }
# Pin the pack in the client: the release jar accepts only a pack whose manifest has this SHA-256 (SpritePack).
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead("$root\out\Altitude-1.1.9-hdsprites.zip")
try { $s = $zip.GetEntry('manifest.txt').Open(); $sha = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($s)).Replace('-', '').ToLower(); $s.Close() } finally { $zip.Dispose() }
$src = "$root\src\em\altitude\game\scene\player\SpritePack.java"
$code = [IO.File]::ReadAllText($src)
$pinned = [regex]::Replace($code, 'MANIFEST_SHA256 = "[0-9a-f]{64}"', "MANIFEST_SHA256 = `"$sha`"")
if ($pinned -ne $code) { [IO.File]::WriteAllText($src, $pinned, (New-Object Text.UTF8Encoding $false)); "Manifest SHA-256 $sha written to SpritePack.java: rebuild the client (build.ps1) to ship it with this pack." }
else { "Manifest SHA-256 $sha (already pinned in SpritePack.java)" }