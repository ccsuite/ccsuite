# fclaude-lane-check: finds Claude Code sessions that carry the fclaude flags (--settings ...fclaude-settings.json) and may NOT be pointed at the free lane,
# so they would talk to Anthropic directly with a paid login (PRP 76). Read-only: it lists processes and reads the settings file, nothing else.
#
# Since the endpoint lives in the "env" block of fclaude-settings.json, any session that starts with that file and starts AFTER the endpoint was put in it
# is on the free lane. A session that started earlier cannot be judged from outside: it may have been started through fclaude.bat (fine) or from a copied
# command line (a leak, like a leaked session of 2026-11-08). Those are reported as CHECK, with the way to settle it.
# (A connection to Anthropic is not a signal: a lane session also holds one for its own side traffic.)
#
# Usage:   powershell -NoProfile -File fclaude-lane-check.ps1        one line per fclaude-flagged session; exit code 1 if any needs a CHECK
$ErrorActionPreference = 'SilentlyContinue'

$settings = Join-Path $PSScriptRoot 'fclaude-settings.json'
$endpointSince = $null
if (Test-Path $settings) {
    $json = Get-Content $settings -Raw | ConvertFrom-Json
    if ($json.env.ANTHROPIC_BASE_URL -like 'http://127.0.0.1:*') { $endpointSince = (Get-Item $settings).LastWriteTime }
}

$checks = 0
$found = 0
Get-CimInstance Win32_Process -Filter "Name='claude.exe'" | Where-Object { $_.CommandLine -match 'fclaude-settings\.json' } | ForEach-Object {
    $found++
    $session = if ($_.CommandLine -match '(--resume|--session-id)\s+(\S+)') { $Matches[2].Substring(0, [Math]::Min(8, $Matches[2].Length)) } else { '-' }
    $started = $_.CreationDate
    if ($endpointSince -and $started -ge $endpointSince) {
        "OK     pid $($_.ProcessId) session $session  started $($started.ToString('MM-dd HH:mm')) after the endpoint was put in the settings file: on the free lane"
    } else {
        $checks++
        "CHECK  pid $($_.ProcessId) session $session  started $($started.ToString('MM-dd HH:mm')) before the endpoint was in the settings file: it is on the free lane only if it was started through fclaude.bat. Settle it: ccacc calls --since 10m shows the lane serving it, or restart it with proj prp resume <n> --exe fclaude"
    }
}
if ($found -eq 0) { 'none   no session with the fclaude flags is running' }
if ($checks -gt 0) { exit 1 } else { exit 0 }
