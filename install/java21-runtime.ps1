<#
Switches an Altitude install to a Java 21 runtime (Temurin JRE 21) with ZGC, or rolls that back.

  .\install\java21-runtime.ps1 [-GameDir <folder>] [-Jre <Temurin JRE 21 folder>]
  .\install\java21-runtime.ps1 -Rollback [-GameDir <folder>] [-From <runtime backup folder name>]

Install:
  1. Renames <game>\runtime to runtime-before-java21-<time> and copies the JRE 21 folder to <game>\runtime.
     (Skipped when <game>\runtime is already Java 21.)
  2. Backs up app\Altitude.cfg to Altitude.cfg.before-java21-<time>, then sets these [JavaOptions] lines, removing
     any other garbage collector or -Xms/-Xmx line:
       java-options=-XX:+UseZGC
       java-options=-XX:+ZGenerational
       java-options=-Xmx2g
       java-options=-Xms2g
     (Skipped when they are already exactly so.)
Rollback (one step): renames <game>\runtime to runtime-java21-<time> (kept, not deleted), renames the newest
runtime-before-java21-* folder (or -From) back to runtime, and restores the matching Altitude.cfg backup when there
is one. Nothing is deleted either way. Refuses to run while Altitude is running.
#>
param(
    [string]$GameDir = (& "$PSScriptRoot\..\tools\game-dir.ps1"),
    [string]$Jre = 'C:\Program Files\Eclipse Adoptium\jre-21.0.12.101-hotspot',
    [switch]$Rollback,
    [string]$From
)

$ErrorActionPreference = 'Stop'
if (-not $GameDir) { throw 'Altitude not found: pass -GameDir or set ALTITUDE_DIR' }
$runtime = Join-Path $GameDir 'runtime'
$cfg = Join-Path $GameDir 'app\Altitude.cfg'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$options = @('java-options=-XX:+UseZGC', 'java-options=-XX:+ZGenerational', 'java-options=-Xmx2g', 'java-options=-Xms2g')

function Get-JavaVersion([string]$dir) {
    $release = Join-Path $dir 'release'
    if (-not (Test-Path $release)) { return $null }
    $line = Get-Content $release | Where-Object { $_ -like 'JAVA_VERSION=*' } | Select-Object -First 1
    if ($line) { return $line.Substring('JAVA_VERSION='.Length).Trim('"') }
    return $null
}

if (-not (Test-Path $cfg)) { throw "Not an Altitude install (no app\Altitude.cfg): $GameDir" }
if (Get-Process -Name 'Altitude' -ErrorAction SilentlyContinue) { throw 'Altitude is running; close it first.' }

if ($Rollback) {
    if ($From) {
        $backup = Get-Item (Join-Path $GameDir $From)
    } else {
        $backup = Get-ChildItem $GameDir -Directory -Filter 'runtime-before-java21-*' | Sort-Object Name | Select-Object -Last 1
    }
    if (-not $backup) { throw "No runtime-before-java21-* folder in $GameDir (pass -From <folder name>)" }
    if (Test-Path $runtime) { Rename-Item $runtime "runtime-java21-$stamp"; Write-Host "Moved runtime to runtime-java21-$stamp" }
    Rename-Item $backup.FullName 'runtime'
    Write-Host "Restored $($backup.Name) as runtime (Java $(Get-JavaVersion $runtime))"
    $cfgBackup = Join-Path $GameDir ('app\Altitude.cfg.before-java21-' + ($backup.Name -replace '^runtime-before-java21-', ''))
    if (Test-Path $cfgBackup) {
        Copy-Item $cfg (Join-Path $GameDir "app\Altitude.cfg.java21-$stamp")
        Copy-Item $cfgBackup $cfg -Force
        Write-Host "Restored app\Altitude.cfg from $(Split-Path -Leaf $cfgBackup) (the Java 21 one is kept as Altitude.cfg.java21-$stamp)"
    } else {
        Write-Host 'No matching Altitude.cfg backup; app\Altitude.cfg left as it is.'
    }
    return
}

# Install: runtime.
$jreVersion = Get-JavaVersion $Jre
if (-not $jreVersion -or -not $jreVersion.StartsWith('21') -or -not (Test-Path (Join-Path $Jre 'bin\java.exe'))) { throw "Not a Java 21 runtime: $Jre" }
$current = Get-JavaVersion $runtime
if ($current -and $current.StartsWith('21')) {
    Write-Host "runtime is already Java $current"
} else {
    if (Test-Path $runtime) { Rename-Item $runtime "runtime-before-java21-$stamp"; Write-Host "Moved runtime (Java $current) to runtime-before-java21-$stamp" }
    Copy-Item $Jre $runtime -Recurse
    Write-Host "Copied Java $jreVersion from $Jre to runtime"
}

# Install: launcher options.
$text = [IO.File]::ReadAllText($cfg)
$newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
$lines = $text -split "\r?\n"
if ($lines[-1] -eq '') { $lines = $lines[0..($lines.Count - 2)] }
$kept = New-Object System.Collections.Generic.List[string]
$section = ''
$inserted = $false
foreach ($line in $lines) {
    if ($line -match '^\[(.+)\]$') {
        if ($section -eq 'JavaOptions' -and -not $inserted) { $options | ForEach-Object { $kept.Add($_) }; $inserted = $true }
        $section = $Matches[1]
    }
    if ($section -eq 'JavaOptions' -and $line -match '^java-options=(-XX:[+-]Use\w*GC|-XX:[+-]ZGenerational|-Xm[sx]\S*)$') { continue }
    $kept.Add($line)
}
if (-not $inserted) {
    if ($section -ne 'JavaOptions') { $kept.Add('[JavaOptions]') }
    $options | ForEach-Object { $kept.Add($_) }
}
$newText = ($kept -join $newline) + $newline
if ($newText -eq $text) {
    Write-Host 'app\Altitude.cfg already has the Java 21 options'
} else {
    Copy-Item $cfg (Join-Path $GameDir "app\Altitude.cfg.before-java21-$stamp")
    [IO.File]::WriteAllText($cfg, $newText)
    Write-Host "Set the Java 21 options in app\Altitude.cfg (backup: Altitude.cfg.before-java21-$stamp)"
}
Write-Host 'Rollback: .\install\java21-runtime.ps1 -Rollback'
