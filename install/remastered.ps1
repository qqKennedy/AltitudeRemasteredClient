<#
Installs the patched Altitude 1.1.9 client (REMASTERED jar and HD sprite pack), or removes it.

  .\install\remastered.ps1 [-GameDir <folder>]
  .\install\remastered.ps1 -Uninstall [-GameDir <folder>]

The two files come from this script's own folder (a playtest bundle) or, in the repo, from out\.
Install:
  1. Copies Altitude-1.1.9-REMASTERED.jar and Altitude-1.1.9-hdsprites.zip into <game>\app\lib.
     (Skipped for a file that is already there and identical.)
  2. Backs up app\Altitude.cfg to Altitude.cfg.before-remastered-<time>, then sets the first app.classpath= line
     (the game jar) to app.classpath=$APPDIR\lib\Altitude-1.1.9-REMASTERED.jar. Every other line is kept.
     (Skipped when it is already so.)
Uninstall: restores the newest Altitude.cfg.before-remastered-* backup (when there is none, or it still names the
REMASTERED jar, the first app.classpath= line is set back to the stock Altitude-1.1.9.jar), then removes the two
files from app\lib. Nothing else is deleted. Refuses to run while Altitude is running.
Steam's "Verify integrity of game files" also undoes the install.
#>
param(
    [string]$GameDir,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
if (-not $GameDir) {
    # tools\game-dir.ps1 in the repo; next to this script in a playtest bundle.
    $finder = @("$PSScriptRoot\..\tools\game-dir.ps1", "$PSScriptRoot\game-dir.ps1") | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($finder) { $GameDir = & $finder }
}
if (-not $GameDir) { throw 'Altitude not found: pass -GameDir or set ALTITUDE_DIR' }
$lib = Join-Path $GameDir 'app\lib'
$cfg = Join-Path $GameDir 'app\Altitude.cfg'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$files = @('Altitude-1.1.9-REMASTERED.jar', 'Altitude-1.1.9-hdsprites.zip')
$patchedLine = 'app.classpath=$APPDIR\lib\Altitude-1.1.9-REMASTERED.jar'
$stockLine = 'app.classpath=$APPDIR\lib\Altitude-1.1.9.jar'

# Reads the cfg, replaces its first app.classpath= line with $line and writes it back when that changes it.
# Returns whether it was written.
function Set-GameJarLine([string]$line) {
    $text = [IO.File]::ReadAllText($cfg)
    $newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines = $text -split "\r?\n"
    $i = [Array]::FindIndex($lines, [Predicate[string]] { param($l) $l -like 'app.classpath=*' })
    if ($i -lt 0) { throw "No app.classpath= line in $cfg" }
    if ($lines[$i] -eq $line) { return $false }
    $lines[$i] = $line
    [IO.File]::WriteAllText($cfg, ($lines -join $newline))
    return $true
}
function Get-GameJarLine {
    Get-Content $cfg | Where-Object { $_ -like 'app.classpath=*' } | Select-Object -First 1
}

if (-not (Test-Path $cfg) -or -not (Test-Path (Join-Path $lib 'Altitude-1.1.9.jar'))) { throw "Not an Altitude 1.1.9 install (no app\Altitude.cfg or app\lib\Altitude-1.1.9.jar): $GameDir" }
if (Get-Process -Name 'Altitude' -ErrorAction SilentlyContinue) { throw 'Altitude is running; close it first.' }

if ($Uninstall) {
    $backup = Get-ChildItem (Join-Path $GameDir 'app') -File -Filter 'Altitude.cfg.before-remastered-*' | Sort-Object Name | Select-Object -Last 1
    if ($backup) {
        if ([IO.File]::ReadAllText($backup.FullName) -eq [IO.File]::ReadAllText($cfg)) {
            Write-Host "app\Altitude.cfg is already the same as $($backup.Name)"
        } else {
            Copy-Item $backup.FullName $cfg -Force
            Write-Host "Restored app\Altitude.cfg from $($backup.Name)"
        }
    }
    if ((Get-GameJarLine) -eq $patchedLine) {
        Set-GameJarLine $stockLine | Out-Null
        Write-Host 'Set the game jar in app\Altitude.cfg back to the stock Altitude-1.1.9.jar'
    } elseif (-not $backup) {
        Write-Host 'No Altitude.cfg.before-remastered-* backup; app\Altitude.cfg does not use the REMASTERED jar and is left as it is.'
    }
    foreach ($f in $files) {
        $path = Join-Path $lib $f
        if (Test-Path $path) { Remove-Item $path; Write-Host "Removed app\lib\$f" } else { Write-Host "app\lib\$f is not there" }
    }
    return
}

# Install: files.
$source = @($PSScriptRoot, (Join-Path $PSScriptRoot '..\out')) | Where-Object { Test-Path (Join-Path $_ $files[0]) } | Select-Object -First 1
if (-not $source) { throw "No $($files[0]) next to this script or in out\ (run build.ps1 first)" }
foreach ($f in $files) {
    $from = Join-Path $source $f
    if (-not (Test-Path $from)) { throw "Missing $from" }
    $to = Join-Path $lib $f
    if ((Test-Path $to) -and (Get-FileHash $to).Hash -eq (Get-FileHash $from).Hash) {
        Write-Host "app\lib\$f is already this version"
    } else {
        Copy-Item $from $to -Force
        Write-Host "Copied $f to app\lib"
    }
}

# Install: launcher class path.
if ((Get-GameJarLine) -eq $patchedLine) {
    Write-Host 'app\Altitude.cfg already uses the REMASTERED jar'
} else {
    Copy-Item $cfg (Join-Path $GameDir "app\Altitude.cfg.before-remastered-$stamp")
    Set-GameJarLine $patchedLine | Out-Null
    Write-Host "Set the game jar in app\Altitude.cfg to Altitude-1.1.9-REMASTERED.jar (backup: Altitude.cfg.before-remastered-$stamp)"
}
Write-Host 'Uninstall: run this script again with -Uninstall'
