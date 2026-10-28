<#
Tests out\Altitude-REMASTERED-Setup.exe on a copy of a stock Altitude folder, never on a real install:
install, install again (no changes), install without Java, a simulated Steam file check followed by a repair, and
uninstall, which must leave the copy byte-identical to the stock folder.

  .\installer\test-setup.ps1 -Stock <a stock Altitude folder copy> [-Downloads <folder with the pinned zips>]

-Stock must be a copy (Altitude.exe, app, runtime with the stock Java 17), not the Steam folder: it is only read, and
the test works in out\setup-test.
#>
param([Parameter(Mandatory)][string]$Stock, [string]$Downloads)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$exe = Join-Path $root 'out\Altitude-REMASTERED-Setup.exe'
$test = Join-Path $root 'out\setup-test'
if (-not (Test-Path (Join-Path $Stock 'app\Altitude.cfg'))) { throw "Not an Altitude folder: $Stock" }
if (Test-Path $test) { Remove-Item -LiteralPath $test -Recurse -Force }
robocopy $Stock $test /E /NFL /NDL /NJH /NJS /NP | Out-Null

function Setup([string[]]$extra) {
    $log = Join-Path $root 'out\setup-test.log'
    if (Test-Path $log) { Remove-Item -LiteralPath $log }
    $args = @("/dir:`"$test`"", "/log:`"$log`"") + $extra
    if ($Downloads) { $args += "/downloads:`"$Downloads`"" }
    $p = Start-Process $exe -ArgumentList $args -Wait -PassThru
    $text = if (Test-Path $log) { Get-Content $log -Raw } else { '' }
    if ($p.ExitCode -ne 0) { throw "Setup $extra failed:`n$text" }
    return $text
}
function Java { (Get-Content (Join-Path $test 'runtime\release') | Where-Object { $_ -like 'JAVA_VERSION=*' }) -replace 'JAVA_VERSION=|"', '' }
function Check([bool]$ok, [string]$what) { if (-not $ok) { throw "FAIL: $what" }; Write-Host "ok   $what" }
function Tree([string]$dir) { Get-ChildItem $dir -Recurse -File | ForEach-Object { $_.FullName.Substring($dir.Length) + ' ' + (Get-FileHash $_.FullName -Algorithm MD5).Hash } }
$cfg = Join-Path $test 'app\Altitude.cfg'
$dll = Join-Path $test 'app\native\OpenAL64.dll'
$newDll = '3963b06e319180700be0bcc0d027336664bb3a4975876b4a5442f10746fab5b4'

Setup @('/install') | Out-Null
Check ((Java) -eq '21.0.12.1') 'install: Java 21'
Check ((Get-Content $cfg) -contains 'app.classpath=$APPDIR\lib\Altitude-1.1.9-REMASTERED.jar') 'install: game jar line'
Check ((Get-Content $cfg) -contains 'java-options=-XX:+UseZGC') 'install: ZGC'
Check ((Get-FileHash $dll).Hash.ToLower() -eq $newDll) 'install: OpenAL Soft 1.25.2'
Check (Test-Path (Join-Path $test 'app\lib\Altitude-1.1.9-hdsprites.zip')) 'install: HD sprite pack'
$again = Setup @('/install')
Check ($again -notmatch 'Installed|Set the|Downloading') 'install again: nothing to change'
Setup @('/install', '/nojava') | Out-Null
$stockJava = (Get-Content (Join-Path $Stock 'runtime\release') | Where-Object { $_ -like 'JAVA_VERSION=*' }) -replace 'JAVA_VERSION=|"', ''
Check ((Java) -eq $stockJava) "without Java: stock Java $stockJava back"
Check (-not ((Get-Content $cfg) -contains 'java-options=-XX:+UseZGC')) 'without Java: stock options back'
Setup @('/install') | Out-Null
# Steam's file check: the stock runtime files over Java 21, the stock cfg and DLL back.
robocopy (Join-Path $Stock 'runtime') (Join-Path $test 'runtime') /E /IS /IT /NFL /NDL /NJH /NJS /NP | Out-Null
Copy-Item (Join-Path $Stock 'app\Altitude.cfg') $cfg -Force
Copy-Item (Join-Path $Stock 'app\native\OpenAL64.dll') $dll -Force
Setup @('/install') | Out-Null
Check ((Java) -eq '21.0.12.1' -and (Get-FileHash $dll).Hash.ToLower() -eq $newDll -and (Get-Content $cfg) -contains 'java-options=-XX:+UseZGC') 'repair after a Steam file check'
Setup @('/uninstall') | Out-Null
$diff = @(Compare-Object (Tree $Stock) (Tree $test))
Check ($diff.Count -eq 0) "uninstall: identical to stock ($($diff.Count) differences)"
Write-Host 'PASS: Setup install, again, without Java, repair, uninstall'
exit 0
