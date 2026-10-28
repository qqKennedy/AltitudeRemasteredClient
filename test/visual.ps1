<#
Unattended visual test of the patched client: no clicks, no keystrokes, no sign-in.

  .\test\visual.ps1 [-Build] [-Reuse] [-Sprites <dir of upscaled PNGs>] [-Stock <dir of stock PNGs>] [-Map ball_arcade] [-Bots 6]

1. Prepares out\test-game: a copy of the install's app folder (the game folder is only read) running the TEST jar,
   and its own data folder (a copy of your options.xml, windowed, sound off, /para on), so your real settings are
   untouched.
2. Starts the client offline (-Dclient.offline=true: no Steam API, no account) with the test harness, which starts a
   local skirmish against bots and keeps the camera on a bot.
3. Takes screenshots from inside the game (exact frames, window may be covered): the plane gallery, the world-art
   gallery and the match, each with /opt hdsprites off and on (switched by editing client.properties, which the client
   re-reads), then quits.
4. Writes out\visual\<time>\: the shots, compare-gallery.png, compare-world.png and compare-match.png (off vs on),
   game.log (this run's part of the client log), and, with -Sprites and -Stock, the fidelity report (fidelity.csv,
   fidelity-worst.png).
-Reuse attaches to a test client still running from an earlier -Reuse run instead of restarting it, and leaves it
running: steps 1 and 2 are skipped and the replacement folder is re-read, so trying new sprites takes seconds. Code
changes need a restart: -Build always starts a fresh client, as does -Reuse when none is running. Window size and
-JavaArgs only apply when a client starts.
-Perf <seconds> measures frame pacing instead of taking the sprite shots: perflog on, /opt framepacing on and off in
four alternating phases (on, off, on, off) of half that time each, with the client's CPU use per phase. Writes
perf.csv (the client-perf.csv rows of the match), phases.csv and pacing-summary.csv, then shots of the ball-mode
score HUD: score.png as the skirmish runs (no round clock), score-clock.png with a round clock from the harness,
score-overtime.png with that clock at 0:00, score-sudden.png after the harness delivers the server's sudden-death chat
line, and compare-score.png / compare-sudden.png of the top centre.
-TimerRes: Windows 11 ignores a process's 1 ms timer request (so every 1 ms sleep lasts a 15.6 ms tick) while its
window is covered or minimised, which the unattended test window usually is. This exempts the test client
(SetProcessInformation, IGNORE_TIMER_RESOLUTION off), as for a game window that is in view.
The window is 3840x2160 by default because sprite detail only shows at the 4K HUD scale (3x); pass -Width/-Height
for a smaller one. Refuses to run while Altitude itself is running.
#>
param(
    [string]$GameDir = (& "$PSScriptRoot\..\tools\game-dir.ps1"),
    [string]$Sprites, [string]$Stock,
    [string]$Map = 'ball_arcade', [int]$Bots = 6,
    [int]$Width = 3840, [int]$Height = 2160,
    [int]$TimeoutSec = 180,
    [string[]]$JavaArgs = @(),  # extra JVM arguments, e.g. -Dclient.x=y
    [string]$Pack,             # an HD sprite pack to install next to the test client's jar (replaces -Sprites)
    [switch]$StartOn,          # launch with hdsprites already on (the start-up path), shot "start-on" first
    [ValidateSet('normal', 'resizable')][string]$Window = 'normal',  # /opt window at launch (applies when the window is created)
    [int]$Perf = 0,            # seconds per framepacing setting: pacing measurement and score shots instead of sprite shots
    [switch]$TimerRes,         # let the client keep its 1 ms timer while its window is covered (see below)
    [switch]$Reuse,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$out = "$root\out"
$t = "$out\test-game"
$control = "$t\control"           # the harness's command folder, the same for every run of one client
$clientLog = "$t\client.log"       # the running client's log; each run copies its own part into its folder
if (-not $GameDir) { throw 'Altitude not found: pass -GameDir or set ALTITUDE_DIR' }
$java = Join-Path $GameDir 'runtime\bin\java.exe'
$javaw = Join-Path $GameDir 'runtime\bin\javaw.exe'  # the client: no console window (its output goes to the log files)

if (Get-Process Altitude -ErrorAction SilentlyContinue) { throw 'Altitude is running; the visual test only runs when it is not.' }
# Test clients (java, window "Altitude", from out\test-game) are ours to stop or reuse.
$running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object { $_.CommandLine -like "*$t*" })
$attach = $Reuse -and -not $Build -and $running.Count -eq 1 -and $running[0].CommandLine -like "*-Dclient.test.dir=$control*"
if (-not $attach) { $running | ForEach-Object { Stop-Process -Id $_.ProcessId -Force } }

if ($Build) { & "$root\build.ps1" -TestJar | Out-Host }
$testJar = "$out\Altitude-1.1.9-REMASTERED-test.jar"
if (-not (Test-Path $testJar)) { throw "No test jar; run with -Build" }

$app = "$t\app"
$data = "$t\localappdata\NimblyGames\Altitude\config"
$props = "$data\client.properties"
function Set-Option([string]$name, [string]$value) {
    $lines = @(); if (Test-Path $props) { $lines = @(Get-Content $props | Where-Object { $_ -notmatch "^$name=" }) }
    Set-Content $props ($lines + "$name=$value") -Encoding ascii
}
function Get-Option([string]$name) {
    if (Test-Path $props) { foreach ($l in Get-Content $props) { if ($l -match "^$name=(.*)$") { return $Matches[1] } } }
    return $null
}

if (-not $attach) {
    # 1. Test install and data folder.
    if (-not (Test-Path "$app\Altitude.cfg")) {
        robocopy "$GameDir\app" $app /E /XF 'Altitude-1.1.9-*.jar' /NFL /NDL /NJH /NJS /NP | Out-Null
        $cfgLines = Get-Content "$app\Altitude.cfg"
        $first = $true
        $cfgLines = $cfgLines | ForEach-Object {
            if ($first -and $_ -like 'app.classpath=*') { $first = $false; 'app.classpath=$APPDIR\lib\Altitude-1.1.9-REMASTERED.jar' } else { $_ }
        }
        Set-Content "$app\Altitude.cfg" $cfgLines -Encoding ascii
    }
    Copy-Item $testJar "$app\lib\Altitude-1.1.9-REMASTERED.jar" -Force
    $packFile = "$app\lib\Altitude-1.1.9-hdsprites.zip"
    if ($Pack) { Copy-Item $Pack $packFile -Force } else { Remove-Item $packFile -ErrorAction SilentlyContinue }
    New-Item -ItemType Directory -Force $data | Out-Null
    $options = "$data\options.xml"
    if (-not (Test-Path $options)) { Copy-Item "$env:LOCALAPPDATA\NimblyGames\Altitude\config\options.xml" $options }
    [xml]$x = Get-Content $options
    $g = $x.SelectSingleNode('//graphicsOptions')
    $g.SetAttribute('fullscreen', 'false'); $g.SetAttribute('undecorated', 'false')
    $g.SetAttribute('overrideResolution', "$Width,$Height"); $g.SetAttribute('overrideLocation', '0,0')
    $g.SetAttribute('renderParallax', 'true')  # /para true: draw the background layers, as players normally see the game
    $x.SelectSingleNode('//soundOptions').SetAttribute('soundEnabled', 'false')
    $x.Save($options)
    Set-Option 'hdsprites' $(if ($StartOn) { 'on' } else { 'off' }); Set-Option 'perfhud' 'off'; Set-Option 'window' $Window
    Set-Option 'perflog' 'off'; Set-Option 'framepacing' 'on'
    if ($Pack) { Remove-Item "$data\client-sprites\*.png" -ErrorAction SilentlyContinue }  # loose PNGs would override the pack
}

# Tools for the comparison sheets and the fidelity report.
$toolsOut = "$out\sprite-tools"
$toolSources = Get-Item "$root\sprite-tools\SideBySide.java", "$root\sprite-tools\Fidelity.java"
$stale = $toolSources | Where-Object { -not (Test-Path "$toolsOut\$($_.BaseName).class") -or (Get-Item "$toolsOut\$($_.BaseName).class").LastWriteTime -lt $_.LastWriteTime }
if ($stale) {
    & $java -jar "$root\tools\ecj-3.38.0.jar" -17 -nowarn -d $toolsOut $toolSources.FullName | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Compiling sprite-tools failed' }
}

$run = "$out\visual\" + (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force $run, $control | Out-Null
if ($attach) {
    $game = Get-Process -Id $running[0].ProcessId
    $script:seen = @(Get-Content $clientLog -ErrorAction SilentlyContinue).Count
} else {
    # 2. Launch.
    Remove-Item "$control\*.cmd" -ErrorAction SilentlyContinue
    $cfg = Get-Content "$app\Altitude.cfg"
    $cp = ($cfg | Where-Object { $_ -like 'app.classpath=*' } | ForEach-Object { $_.Substring(14).Replace('$APPDIR', $app) }) -join ';'
    $opts = $cfg | Where-Object { $_ -like 'java-options=*' } | ForEach-Object { $_.Substring(13).Replace('$APPDIR', $app) }
    $env:LOCALAPPDATA = "$t\localappdata"
    $game = Start-Process -FilePath $javaw -PassThru -WorkingDirectory $app -RedirectStandardOutput $clientLog -RedirectStandardError "$t\client-stderr.log" `
        -ArgumentList (@($opts) + $JavaArgs + @('-Dclient.offline=true', "-Dclient.test.dir=$control", "-Dclient.test.skirmish=$Map", "-Dclient.test.bots=$Bots", '-cp', $cp, 'em.altitude.game.AltitudeGame'))
    $script:seen = 0
}
$firstLine = $script:seen
if ($TimerRes) {
    Add-Type -Namespace VisualTest -Name Power -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetProcessInformation(IntPtr process, int infoClass, int[] info, int size);
'@
    # PROCESS_POWER_THROTTLING_STATE { Version 1, ControlMask IGNORE_TIMER_RESOLUTION (4), StateMask 0 }; class 4 = ProcessPowerThrottling.
    if (-not [VisualTest.Power]::SetProcessInformation($game.Handle, 4, @(1, 4, 0), 12)) { throw "SetProcessInformation failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
    Write-Host 'Timer resolution kept while the window is covered'
}

Add-Type -Namespace VisualTest -Name Win -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
'@
# The stock loop only renders (and so only runs the harness) while the window is not minimised: restore it if
# something minimised it, without activating it (SW_SHOWNOACTIVATE), so it never takes the keyboard focus.
function Restore-Window {
    $game.Refresh()
    $h = $game.MainWindowHandle
    if ($h -ne [IntPtr]::Zero -and [VisualTest.Win]::IsIconic($h)) {
        [VisualTest.Win]::ShowWindow($h, 4) | Out-Null
        Write-Host 'The test window was minimised; restored it'
    }
}
function Wait-Log([string]$pattern, [int]$seconds = 60) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ($game.HasExited) { throw "The client exited while waiting for '$pattern' (see $clientLog)" }
        Restore-Window
        $lines = @(Get-Content $clientLog -ErrorAction SilentlyContinue)
        for ($i = $script:seen; $i -lt $lines.Count; $i++) { if ($lines[$i] -match $pattern) { $script:seen = $i + 1; return } }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for '$pattern' (see $clientLog)"
}
# Command files are named by time, so files from different runs of one client stay in order.
function Send-Command([string]$text) { Set-Content ("$control\{0}.cmd" -f (Get-Date -Format 'yyyyMMddHHmmssfff')) $text -Encoding ascii; Start-Sleep -Milliseconds 20 }
function Shot([string]$name) { Send-Command "shot $name"; Wait-Log "TestHarness: shot .*$name\.png" 30 }
function Set-Sprites([string]$value) { Set-Option 'hdsprites' $value; Wait-Log "textures reloaded with hdsprites $value|hdsprites $value, but a" 120 }
# -Perf: alternating framepacing phases with perflog on; the CSV rows are matched to the phases by their time.
function Measure-Pacing {
    $csv = "$data\client-perf.csv"
    Remove-Item $csv -ErrorAction SilentlyContinue
    Set-Option 'perflog' 'on'
    $phases = @()
    foreach ($mode in 'on', 'off', 'on', 'off') {
        Set-Option 'framepacing' $mode
        Start-Sleep 3  # the client re-reads the file, and the first second after the switch is left out
        $game.Refresh(); $cpu0 = $game.TotalProcessorTime.TotalSeconds; $t0 = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
        $end = (Get-Date).AddSeconds($Perf / 2)
        while ((Get-Date) -lt $end) { if ($game.HasExited) { throw 'The client exited during the measurement' }; Restore-Window; Start-Sleep 1 }
        $game.Refresh(); $cpu1 = $game.TotalProcessorTime.TotalSeconds; $t1 = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
        $phases += [pscustomobject]@{ framepacing = $mode; start_ms = $t0; end_ms = $t1
            cpu_pct_of_one_core = [Math]::Round(100 * ($cpu1 - $cpu0) / (($t1 - $t0) / 1000), 1) }
        Write-Host "framepacing $mode : $($phases[-1].cpu_pct_of_one_core)% of one core"
    }
    Set-Option 'perflog' 'off'; Set-Option 'framepacing' 'on'
    Start-Sleep 2
    $rows = @(Import-Csv $csv | Where-Object { $_.scene -eq 'PlayerScene' })
    $rows | Export-Csv "$run\perf.csv" -NoTypeInformation
    $phases | Export-Csv "$run\phases.csv" -NoTypeInformation
    $summary = foreach ($mode in 'on', 'off') {
        $mine = @($phases | Where-Object framepacing -eq $mode)
        $r = @($rows | Where-Object { $ms = [long]$_.epoch_ms; @($mine | Where-Object { $ms -gt $_.start_ms + 1000 -and $ms -le $_.end_ms }).Count -gt 0 })
        # (Windows PowerShell's Measure-Object takes property names only, so the columns are converted first.)
        $col = { param($name) @($r | ForEach-Object { [double]$_.$name }) | Measure-Object -Average -Sum -Maximum }
        $avg = { param($name) [Math]::Round((& $col $name).Average, 3) }
        $frames = (& $col 'frames').Sum
        [pscustomobject]@{ framepacing = $mode; seconds = $r.Count; frames = $frames
            fps = [Math]::Round($frames / [Math]::Max(1, (& $col 'seconds').Sum), 1)
            avg_ms = & $avg 'avg_ms'; p50_ms = & $avg 'p50_ms'; p95_ms = & $avg 'p95_ms'; p99_ms = & $avg 'p99_ms'
            max_ms_mean = & $avg 'max_ms'; max_ms_worst = (& $col 'max_ms').Maximum
            stdev_ms = & $avg 'stdev_ms'; over_refresh_per_s = & $avg 'over_refresh'
            gc_pauses = (& $col 'gc_pauses').Sum
            ticks_per_s = & $avg 'ticks_per_s'
            cpu_pct_of_one_core = [Math]::Round(($mine | Measure-Object cpu_pct_of_one_core -Average).Average, 1) }
    }
    $summary | Export-Csv "$run\pacing-summary.csv" -NoTypeInformation
    $summary | Format-Table -AutoSize | Out-Host
}
function Save-RunLog { @(Get-Content $clientLog -ErrorAction SilentlyContinue) | Select-Object -Skip $firstLine | Set-Content "$run\game.log" }

try {
    if ($attach) {
        Write-Host "Reusing the running test client (pid $($game.Id))"
        if ((Get-Option 'hdsprites') -eq 'on') { Set-Sprites 'off' }
    } else {
        Wait-Log 'TestHarness: match running' $TimeoutSec
        Start-Sleep 3
    }
    if ($StartOn -and -not $attach) {
        # Started with hdsprites on: the cache is prepared at start-up, then the textures loaded meanwhile are reloaded
        # (or nothing was loaded yet). That may already have happened before the match started.
        $ready = 'replacements ready at|textures reloaded with hdsprites on'
        $script:seen = $firstLine
        Wait-Log $ready 120
        Send-Command "out $run"
        Shot 'start-on'
        Set-Sprites 'off'
    }
    if ($Sprites) {
        # The client reads the folder only while it prepares a scale, and hdsprites is off here.
        New-Item -ItemType Directory -Force "$data\client-sprites" | Out-Null
        Remove-Item "$data\client-sprites\*.png" -ErrorAction SilentlyContinue
        Copy-Item "$Sprites\*.png" "$data\client-sprites\"
        Send-Command 'rescan'
    }
    Send-Command "out $run"
    if ($Perf) {
        Measure-Pacing
        Shot 'score'
        # Test-only: the local skirmish has no round clock and never announces sudden death, so the harness gives the
        # round a clock, runs it out, then delivers the ladder servers' sudden-death chat line.
        Send-Command 'clock 95'; Start-Sleep 1
        Shot 'score-clock'
        Send-Command 'clock 0'; Start-Sleep 1
        Shot 'score-overtime'
        Send-Command 'chat Sudden Death: First team with a point total of 8 wins.'; Start-Sleep 1
        Shot 'score-sudden'
    } else {
    # 3. Shots.
    Send-Command 'gallery on'; Start-Sleep 1
    Shot 'gallery-off'
    Send-Command 'gallery world'; Start-Sleep 1
    Shot 'world-off'
    Set-Sprites 'on'; Start-Sleep 1
    Shot 'world-on'
    Send-Command 'gallery on'; Start-Sleep 1
    Shot 'gallery-on'
    Send-Command 'gallery off'; Start-Sleep 2
    Shot 'match-on'
    Set-Sprites 'off'; Start-Sleep 1
    Shot 'match-off'
    }
    if (-not $Reuse) {
        Send-Command 'quit'
        if (-not $game.WaitForExit(30000)) { Stop-Process -Id $game.Id -Force }
    }
} catch {
    Save-RunLog
    if (-not $game.HasExited) { Stop-Process -Id $game.Id -Force }
    throw
}
Save-RunLog

# 4. Sheets and report. Sizes come from the shots, since a reused client keeps the size it started with.
Add-Type -AssemblyName System.Drawing
if ($Perf) {
    $img = [System.Drawing.Image]::FromFile("$run\score.png"); $w = $img.Width; $h = $img.Height; $img.Dispose()
    # The score sits top centre: 1280x720 HUD units (420..860, 0..60) at the window's HUD scale.
    $s = [Math]::Min($w / 1280, $h / 720)
    $crop = @(([int]($w / 2 - 220 * $s)), 0, ([int](440 * $s)), ([int](60 * $s)))
    & $java -cp $toolsOut SideBySide "$run\score.png" 'no round clock (skirmish)' "$run\score-clock.png" 'round clock' "$run\compare-score.png" @crop | Out-Host
    & $java -cp $toolsOut SideBySide "$run\score-overtime.png" 'clock at 0:00, no announcement' "$run\score-sudden.png" 'after the sudden-death chat line' "$run\compare-sudden.png" @crop | Out-Host
    Write-Host "Pacing check done: $run"
    exit 0
}
$img = [System.Drawing.Image]::FromFile("$run\match-off.png"); $w = $img.Width; $h = $img.Height; $img.Dispose()
& $java -cp $toolsOut SideBySide "$run\gallery-off.png" 'hdsprites off (stock)' "$run\gallery-on.png" 'hdsprites on' "$run\compare-gallery.png" | Out-Host
& $java -cp $toolsOut SideBySide "$run\world-off.png" 'hdsprites off (stock)' "$run\world-on.png" 'hdsprites on' "$run\compare-world.png" 0 0 $w $h | Out-Host
& $java -cp $toolsOut SideBySide "$run\match-off.png" 'hdsprites off (stock)' "$run\match-on.png" 'hdsprites on' "$run\compare-match.png" `
    ([Math]::Max(0, [int]($w / 2 - 600))) ([Math]::Max(0, [int]($h / 2 - 400))) 1200 800 | Out-Host  # the chase camera keeps the followed bot at the centre
$fidelity = 'not run (pass -Sprites and -Stock)'
if ($Sprites -and $Stock) {
    & $java -cp $toolsOut Fidelity $Stock $Sprites $run | Out-Host
    $fidelity = if ($LASTEXITCODE -eq 0) { 'all within the limits' } else { 'textures over the limits: see fidelity.csv and fidelity-worst.png' }
}
$state = if ($Reuse) { "; test client left running (pid $($game.Id))" } else { '' }
Write-Host "Visual test done: $run (fidelity: $fidelity)$state"
exit 0
