# PRP 78 R5: rebuild ccacc and restart the free lane, in one command, without dropping a request that is running (a dev tool: ad hoc where that is simplest, but it has to work).
#   powershell -File ccacc\pkg\restart-free-lane.ps1        (from the ccsuite root; the folder of the exe's jar is -AppDir, or CCACC_APPDIR, default as below)
# The free lane lives inside the ccacc process, so new code means a new process. The order keeps the lane serving for as long as possible:
#   1 build the jar and check the exe can be built (the lane keeps serving). When the running ccacc was started from target\ccacc.jar (a dev launch) that jar is locked, so the build waits until the old process is stopped
#   2 hold the lane (new requests get a 503 and Claude Code retries) and wait until no request is running; a request running for over -StuckMinutes does not hold the restart, and is logged
#   3 stop ccacc, put the jar in place, build the exe, repair the install, start ccacc from the installed exe (the one Windows starts at sign-in)
#   4 wait until the free lane answers again
# If the drain does not finish in -DrainMinutes the hold is released and NOTHING is restarted. If the running ccacc is an old build that has no `lanes drain`, the drain cannot be checked: the script stops, unless -Force says to restart anyway.
# Every step and its result go to the log in the ccacc store folder (~\ccsuite\ccacc\restarts), and to the screen.
param(
    [string]$AppDir = $(if ($env:CCACC_APPDIR) { $env:CCACC_APPDIR } else { 'C:\user\Apps\ccacc' }),
    [int]$DrainMinutes = 10,
    [int]$StuckMinutes = 5,
    [int]$LanePort = 8686,
    [switch]$SkipTests,
    [switch]$NoStart,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$store = Join-Path $env:USERPROFILE 'ccsuite\ccacc\restarts'
New-Item -ItemType Directory -Force $store | Out-Null
$logFile = Join-Path $store ('restart-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$ccacc = Join-Path $env:LOCALAPPDATA 'Programs\ccacc\ccacc.exe'
$control = Join-Path $env:USERPROFILE 'ccsuite\ccacc\control.json'

function Log([string]$msg) {
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + $msg
    Add-Content -Path $logFile -Value $line
    Write-Host $line
}

function Run-Ccacc([string[]]$arguments) {
    # the installed ccacc.exe as a command line to the running app; returns the output text, or $null when it failed
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $ccacc @arguments 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) { return $null }
        return $out.Trim()
    } catch { return $null } finally { $ErrorActionPreference = $old }
}

function Release-Hold {
    $r = Run-Ccacc @('lanes', 'hold', 'off')
    Log ('hold released: ' + $(if ($r) { $r } else { 'ccacc did not answer' }))
}

# the running ccacc: the pid in its control file (whatever it is called: the exe, or javaw for a dev launch) and any ccacc.exe
function Get-RunningCcacc {
    $ids = @()
    if (Test-Path $control) {
        try { $pidInFile = [int](Get-Content $control -Raw | ConvertFrom-Json).pid; if (Get-Process -Id $pidInFile -ErrorAction SilentlyContinue) { $ids += $pidInFile } } catch { }
    }
    $ids += @(Get-Process -Name ccacc -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    return @($ids | Select-Object -Unique)
}

function Stop-Ccacc {
    foreach ($id in (Get-RunningCcacc)) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue; Log "   stopped pid $id" }
    Start-Sleep -Seconds 2
}

function Build-Jar {
    Log 'build the jar'
    $mvnArgs = @('-o', '-q', 'package', '-pl', 'ccacc', '-am')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    Push-Location $root
    try { & mvn @mvnArgs; if ($LASTEXITCODE) { throw 'build failed' } } finally { Pop-Location }
    Log '   jar built'
}

function Build-Exe([string]$dir, [string]$jar) {
    Push-Location $root
    try {
        & mvn -o -q -Djrexe "-Dccacc.jar=$jar" "-Djr.installDir=$dir" 'io.github.jarrunner:jr-maven-plugin:1.3.0:exe@ccacc' -pl ccacc
        if ($LASTEXITCODE) { throw 'exe build failed' }
    } finally { Pop-Location }
}

try {
    Log "restart of the free lane started (log: $logFile)"
    New-Item -ItemType Directory -Force $AppDir | Out-Null
    $targetJar = Join-Path $root 'ccacc\target\ccacc.jar'

    # a dev launch (javaw -jar ...\target\ccacc.jar) holds that jar: it cannot be rebuilt until that process is stopped
    $running = Get-RunningCcacc
    $devLaunch = $false
    foreach ($id in $running) {
        $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue).CommandLine
        if ($cmd -and $cmd -like '*target\ccacc.jar*') { $devLaunch = $true }
    }

    # 1 build, while the lane serves (unless the running one holds the jar)
    if ($devLaunch) {
        Log '1. the running ccacc was started from target\ccacc.jar, which it holds: the build waits until it is stopped'
    } else {
        Log '1. build while the lane keeps serving'
        Build-Jar
        $pre = Join-Path $env:TEMP 'ccacc-preflight'
        Remove-Item -Recurse -Force $pre -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force $pre | Out-Null
        Copy-Item $targetJar (Join-Path $pre 'ccacc.jar') -Force
        try { Build-Exe $pre (Join-Path $pre 'ccacc.jar') } catch { throw 'the exe cannot be built (preflight): nothing was touched, the old lane is still running' }
        Remove-Item -Recurse -Force $pre -ErrorAction SilentlyContinue
        Log '   exe build checked'
    }

    # 2 hold, then drain
    if ($running.Count -eq 0) {
        Log '2. ccacc is not running: nothing to drain'
    } else {
        Log '2. hold the lane and wait for the running requests'
        $verdict = Run-Ccacc @('lanes', 'drain')
        if (-not $verdict -or $verdict -notmatch '^(idle|busy|stuck)') {
            if (-not $Force) { throw 'the running ccacc cannot say whether requests are running (an old build without "lanes drain"): nothing was restarted. Run again with -Force to restart anyway.' }
            Log '   the running ccacc has no "lanes drain" (old build): restarting without a drain because of -Force'
        } else {
            $held = Run-Ccacc @('lanes', 'hold', 'on')
            Log ('   hold: ' + $(if ($held) { $held } else { 'not set' }))
            $deadline = (Get-Date).AddMinutes($DrainMinutes)
            while ($true) {
                $verdict = Run-Ccacc @('lanes', 'drain')
                Log ('   drain: ' + $verdict)
                if ($verdict -match '^idle') { break }
                if ($verdict -match '^stuck') { Log '   LOGGED: requests running for over the limit do not hold the restart; they are dropped now'; break }
                if (-not $verdict) { Release-Hold; throw 'ccacc stopped answering during the drain: nothing was restarted' }
                if ((Get-Date) -gt $deadline) { Release-Hold; throw "requests were still running after $DrainMinutes minutes: the hold is released and nothing was restarted" }
                Start-Sleep -Seconds 2
            }
        }
    }

    # 3 stop, new jar, exe, install, start
    Log '3. stop ccacc'
    Stop-Ccacc
    if ($devLaunch) { Build-Jar }
    Log '   new jar into the app folder (the old one is kept as ccacc.jar.bak-previous); the old AOT caches are for the old jar'
    $appJar = Join-Path $AppDir 'ccacc.jar'
    if (Test-Path $appJar) { Copy-Item $appJar (Join-Path $AppDir 'ccacc.jar.bak-previous') -Force }
    Copy-Item $targetJar $appJar -Force
    Remove-Item (Join-Path $AppDir 'ccacc.*.aot'), (Join-Path $AppDir 'ccacc.*.aot.config') -ErrorAction SilentlyContinue
    Log '   exe built against that jar'
    try { Build-Exe $AppDir $appJar } catch { throw 'exe build failed: ccacc is STOPPED, run the script again' }
    Log '   repair the installed copy'
    Push-Location $env:TEMP
    try { & (Join-Path $AppDir 'ccacc.exe') install --start-at-login | Out-Null } finally { Pop-Location }

    if ($NoStart) { Log '   -NoStart: ccacc is left stopped' } else {
        Log '   start ccacc'
        Start-Process $ccacc -ArgumentList 'app' -WindowStyle Hidden
        # 4 the lane answers again
        Log '4. wait for the free lane'
        $ok = $false
        for ($i = 0; $i -lt 45 -and -not $ok; $i++) {
            Start-Sleep -Seconds 2
            try {
                $c = New-Object System.Net.Sockets.TcpClient
                $c.Connect('127.0.0.1', $LanePort)
                $ok = $c.Connected
                $c.Close()
            } catch { }
        }
        if ($ok) { Log "   the free lane answers on port $LanePort" } else { Log "   WARNING: nothing answers on port $LanePort after 90 s (are the lanes on? ccacc lanes on)" }
        $status = Run-Ccacc @('lanes')
        Log ('   ' + $(if ($status) { $status } else { 'ccacc lanes: no answer' }))
    }
    Log 'done'
} catch {
    Log ('STOPPED: ' + $_.Exception.Message)
    exit 1
}
