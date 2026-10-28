<#
Builds the patched Altitude 1.1.9 client jar.

  .\build.ps1 [-GameDir <Altitude install folder>]   (default: $env:ALTITUDE_DIR, else Altitude in a Steam library)

1. Copies the stock jars from <GameDir>\app\lib into stock\ (once; the game folder is only read) and checks the
   SHA-256 of the two jars that get patched.
2. Compiles the build tools, the client sources and the tests (Eclipse compiler in tools\, Java 17 class files), and
   writes the build ID (commit, -dirty, date) into the client classes.
3. Runs the tests.
4. Writes out\Altitude-1.1.9-REMASTERED.jar: stock game jar + hooks + client classes (client.build.Patcher). Every hook
   site must match exactly; every written class is checked with ASM's verifier.
5. Loads every changed class with -Xverify:all on the launcher's class path (client.build.LoadCheck).
6. Compares the patched jar with stock and writes out\jar-diff.txt (client.build.JarDiff); any difference that is
   not one of the fixes fails the build.
Needs Java 17 or newer on PATH (or JAVA_HOME).
#>
param([string]$GameDir = (& "$PSScriptRoot\tools\game-dir.ps1"), [switch]$TestJar)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$tools = Join-Path $root 'tools'
$stock = Join-Path $root 'stock'
$out = Join-Path $root 'out'
$asm = (Get-ChildItem "$tools\asm*.jar" | ForEach-Object FullName) -join ';'
$ecj = Join-Path $tools 'ecj-3.38.0.jar'

function Invoke-Java([string[]]$arguments) {
    # Native stderr (compiler errors, test output) must not stop the script before the exit code is checked.
    $ErrorActionPreference = 'Continue'
    & $java @arguments 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "java $($arguments[-1]) failed (exit $LASTEXITCODE)" }
}
function Compile([string]$classpath, [string]$dest, [string[]]$sources) {
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    Invoke-Java (@('-jar', $ecj, '-17', '-encoding', 'UTF-8', '-proc:none', '-nowarn', '-cp', $classpath, '-d', $dest) + $sources)
}

# 1. Stock jars: copied once, read-only from the game folder.
if (-not (Test-Path "$stock\lib")) {
    if (-not $GameDir) { throw 'Altitude not found: pass -GameDir or set ALTITUDE_DIR' }
    $lib = Join-Path $GameDir 'app\lib'
    if (-not (Test-Path "$lib\Altitude-1.1.9.jar")) { throw "No stock Altitude 1.1.9 in $GameDir (pass -GameDir)" }
    New-Item -ItemType Directory -Force "$stock\lib" | Out-Null
    # The stock game jar and the libraries the launcher lists (every app.classpath line after the first, which is the
    # game jar line and may point at a patched jar).
    $names = @(Get-Content "$GameDir\app\Altitude.cfg" | Where-Object { $_ -like 'app.classpath=*' } | Select-Object -Skip 1 | ForEach-Object { Split-Path -Leaf $_.Substring($_.IndexOf('=') + 1) })
    foreach ($n in @('Altitude-1.1.9.jar') + $names) { Copy-Item (Join-Path $lib $n) "$stock\lib" }
}
$gameJar = "$stock\lib\Altitude-1.1.9.jar"
$wrappersJar = "$stock\lib\EmWrappers-1.1.9.jar"
# Library class path as the launcher has it, minus the game jar (the patched jar replaces it).
$libs = (Get-ChildItem "$stock\lib\*.jar" | Where-Object { $_.Name -ne 'Altitude-1.1.9.jar' } | Sort-Object Name | ForEach-Object FullName) -join ';'

# 2. Compile.
Compile $asm "$out\tools" @("$root\build-tools\src")
Compile "$gameJar;$libs" "$out\classes" @("$root\src")
# Build ID: short commit, -dirty when the work tree has changes, and the date, e.g. 29c6bc0-20261027. The client logs
# it at start-up and /opt prints it first (ClientOptions.BUILD_ID).
$commit = git -C $root rev-parse --short HEAD
if ($LASTEXITCODE -ne 0 -or -not $commit) { throw 'git rev-parse failed: the build ID needs the git checkout' }
$dirty = if (git -C $root status --porcelain) { '-dirty' } else { '' }
$buildId = "$commit$dirty-$(Get-Date -Format 'yyyyMMdd')"
# Into the jar next to the client classes: bundled fonts (Blambot Casual, licensed for Altitude, for HudFontSharpen)
# and build\id.txt.
function Add-Resources([string]$classes) {
    $dir = "$classes\em\altitude\game\scene\player"
    New-Item -ItemType Directory -Force "$dir\fonts", "$dir\build" | Out-Null
    Copy-Item "$root\fonts\*.ttf" "$dir\fonts"
    [IO.File]::WriteAllText("$dir\build\id.txt", $buildId)
}
Add-Resources "$out\classes"
Compile "$out\classes;$gameJar;$libs" "$out\test-classes" @("$root\test")

# 3. Tests: every *Test class in test\ (package of the client classes) has a main that throws on failure.
foreach ($test in Get-ChildItem "$root\test\*Test.java") {
    Invoke-Java @('-cp', "$out\test-classes;$out\classes;$gameJar;$libs", "em.altitude.game.scene.player.$($test.BaseName)")
}

# 4-6. Patch, load-check, diff.
$jar = "$out\Altitude-1.1.9-REMASTERED.jar"
Invoke-Java @('-cp', "$out\tools;$asm", 'client.build.Patcher', $gameJar, $wrappersJar, "$out\classes", $libs, $jar)
Invoke-Java @('-Xverify:all', '-cp', "$jar;$libs;$out\tools;$asm", 'client.build.LoadCheck', $jar, $gameJar)
Invoke-Java @('-cp', "$out\tools;$asm", 'client.build.JarDiff', $jar, $gameJar, $wrappersJar, "$out\jar-diff.txt")
Write-Host "Built $jar (build $buildId)"

# Test jar (-TestJar): the release jar plus test-src (OfflineTest: -Dclient.offline=true starts in offline mode, so
# test launches never sign in). Same checks; differences listed in out\jar-diff-test.txt. Never ship this jar.
if ($TestJar) {
    $testJarPath = "$out\Altitude-1.1.9-REMASTERED-test.jar"
    Compile "$gameJar;$libs" "$out\test-jar-classes" @("$root\src", "$root\test-src")
    Add-Resources "$out\test-jar-classes"
    Invoke-Java @('-cp', "$out\tools;$asm", 'client.build.Patcher', $gameJar, $wrappersJar, "$out\test-jar-classes", $libs, $testJarPath, '--test')
    Invoke-Java @('-Xverify:all', '-cp', "$testJarPath;$libs;$out\tools;$asm", 'client.build.LoadCheck', $testJarPath, $gameJar)
    Invoke-Java @('-cp', "$out\tools;$asm", 'client.build.JarDiff', $testJarPath, $gameJar, $wrappersJar, "$out\jar-diff-test.txt", '--test')
    Write-Host "Built $testJarPath (test only)"
}
