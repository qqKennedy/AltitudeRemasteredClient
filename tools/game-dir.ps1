<#
The Altitude install folder for the scripts' -GameDir default: $env:ALTITUDE_DIR if set, else Altitude in any Steam
library (Steam's own folder and the libraries in its libraryfolders.vdf). Prints nothing when none is found; the
calling script then asks for -GameDir.

  $GameDir = & "$PSScriptRoot\..\tools\game-dir.ps1"
#>
if ($env:ALTITUDE_DIR) { return $env:ALTITUDE_DIR }
$steam = $null
foreach ($key in 'HKCU:\Software\Valve\Steam', 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam') {
    try { $p = Get-ItemProperty $key -ErrorAction Stop; $steam = if ($p.SteamPath) { $p.SteamPath } else { $p.InstallPath }; if ($steam) { break } } catch {}
}
if (-not $steam) { return }
$libraries = @($steam)
$vdf = Join-Path $steam 'steamapps\libraryfolders.vdf'
if (Test-Path $vdf) {
    foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) { $libraries += $m.Groups[1].Value.Replace('\\', '\') }
}
foreach ($l in $libraries) {
    $dir = Join-Path $l 'steamapps\common\Altitude'
    if (Test-Path (Join-Path $dir 'app\lib\Altitude-1.1.9.jar')) { return (Resolve-Path $dir).Path }
}
