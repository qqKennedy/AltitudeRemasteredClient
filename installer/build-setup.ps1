<#
Builds out\Altitude-REMASTERED-Setup.exe: installer\Setup.cs compiled with the .NET Framework's own C# compiler, with
the client jar and the HD sprite pack from out\ embedded.

  .\build.ps1; .\installer\build-setup.ps1
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$out = Join-Path $root 'out'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { throw "No .NET Framework 4 C# compiler at $csc" }
$jar = Join-Path $out 'Altitude-1.1.9-REMASTERED.jar'
$pack = Join-Path $out 'Altitude-1.1.9-hdsprites.zip'
foreach ($f in $jar, $pack) { if (-not (Test-Path $f)) { throw "Missing $f (run build.ps1 and sprite-tools\pack.ps1 first)" } }
$exe = Join-Path $out 'Altitude-REMASTERED-Setup.exe'
$fw = Split-Path $csc
& $csc /nologo /target:winexe /optimize+ /out:$exe /win32manifest:"$PSScriptRoot\app.manifest" `
    /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll `
    /r:"$fw\System.IO.Compression.dll" /r:"$fw\System.IO.Compression.FileSystem.dll" `
    /resource:"$jar,client.jar" /resource:"$pack,hdsprites.zip" "$PSScriptRoot\Setup.cs"
if ($LASTEXITCODE -ne 0) { throw 'Compiling Setup.cs failed' }
Write-Host ("Wrote {0} ({1} MB)" -f $exe, [math]::Round((Get-Item $exe).Length / 1MB))
