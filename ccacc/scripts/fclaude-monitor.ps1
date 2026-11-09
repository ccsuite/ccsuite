# fclaude-monitor.ps1: the level-1 check of the fclaude free lane, run OUTSIDE Claude Code (PRP 76, closed 2026-11-09: "u can run it outside the claude process").
# Read-only: it never restarts the lane, never starts an agent, never edits anything but its own files in ~\ccsuite\ccacc\monitor\.
# Every IntervalSec (default 600) it checks: the lane answers on 8686; ccacc calls --since 15m --problems (free-lane failures, slow, still-open calls; paid-lane noise is ignored);
# fclaude-lane-check.ps1 (CHECK = a session claims to be fclaude but is not on the lane); a session past its close line with no "closed" line for 10 minutes while it is still working.
# Output: monitor.jsonl (one JSON line per check), status.md (rewritten each check: read this first to catch up), ATTENTION.txt (exists only while the last check was not ok).
# Start: fclaude-monitor-start.cmd   Stop: fclaude-monitor-stop.cmd   Look: fclaude-monitor-status.cmd   One copy only (lock file with the pid). Times are local (EAT) with the offset.
param([int]$IntervalSec = 600, [switch]$Once)
$ErrorActionPreference = 'Continue'
$home_ = $env:USERPROFILE
$dir = Join-Path $home_ 'ccsuite\ccacc\monitor'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$lock = Join-Path $dir 'monitor.lock'; $stopf = Join-Path $dir 'monitor.stop'
$logf = Join-Path $dir 'monitor.jsonl'; $statusf = Join-Path $dir 'status.md'; $attf = Join-Path $dir 'ATTENTION.txt'
$decisions = Join-Path $home_ 'ccsuite\ccacc\close-decisions.log'
$sessdir = Join-Path $home_ 'ccsuite\ccacc\session-status'
$laneCheck = Join-Path $PSScriptRoot 'fclaude-lane-check.ps1'
if (-not (Test-Path $laneCheck)) { $laneCheck = 'C:\user\Apps\cmdtools\fclaude-lane-check.ps1' }

if (-not $Once) {
  if (Test-Path $lock) {
    $old = [int](Get-Content $lock -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($old -and (Get-Process -Id $old -ErrorAction SilentlyContinue)) { Write-Output "already running, pid $old"; exit 1 }
  }
  Set-Content -Path $lock -Value $PID
  Remove-Item $stopf -ErrorAction SilentlyContinue
}

function Now { (Get-Date).ToString("yyyy-MM-dd'T'HH:mm:sszzz") }

function Check {
  $problems = @(); $notes = @()
  # 1. the lane answers (a 404 with a fast reply is alive)
  $r = & curl.exe -s -o NUL -m 5 -w '%{http_code} %{time_total}' http://127.0.0.1:8686/ 2>$null
  $code = ($r -split ' ')[0]
  $dead = ($code -eq '000' -or -not $code)
  if ($dead) { $problems += "lane DEAD: no answer on 8686 ($r)" } else { $notes += "port $r" }
  $inflight = $null
  if (-not $dead) {
    $lanes = (& ccacc lanes 2>$null) -join "`n"
    if ($lanes -match 'free lane: (\d+) accounts .*?, (\d+) in flight') { $notes += "$($Matches[1]) accounts, $($Matches[2]) in flight"; $inflight = [int]$Matches[2] }
    elseif ($lanes -match 'lanes: off') { $problems += 'lanes are OFF' }
    # 2. calls of the last 15 minutes
    $pj = (& ccacc calls --since 15m --problems --json 2>$null) -join "`n"
    try {
      $p = $pj | ConvertFrom-Json
      $failed = @($p.failed | Where-Object { $_ -and $_.mode -ne 'paid' -and $_.account -ne 'KLG' -and $_.port -notlike 'pclaude*' })
      $slowStuck = @($p.open | Where-Object { $_ -and $_.ts -and ((Get-Date).ToUniversalTime() - [datetimeoffset]::Parse([string]$_.ts).UtcDateTime).TotalSeconds -gt 300 })
      if ($failed.Count -gt 0) { $problems += "$($failed.Count) failed free-lane call(s) in 15 min, first: " + (($failed[0] | ConvertTo-Json -Compress) -replace '\s+', ' ').Substring(0, [Math]::Min(160, (($failed[0] | ConvertTo-Json -Compress)).Length)) }
      if ($slowStuck.Count -gt 0) { $problems += "$($slowStuck.Count) call(s) open over 5 minutes" }
      $notes += "calls: failed $($failed.Count), slow $(@($p.slow).Count), open $(@($p.open).Count)"
    } catch { $notes += 'calls: could not read' }
  }
  # 3. sessions that claim fclaude but are not on the lane
  $lc = (& powershell -NoProfile -File $laneCheck 2>$null) -join "`n"
  foreach ($line in ($lc -split "`n")) { if ($line -match '^CHECK') { $problems += $line.Trim() } }
  # 4. a session past its close line with no closed line, still working
  if (Test-Path $decisions) {
    $lines = Get-Content $decisions -Tail 400
    $last = @{}
    foreach ($l in $lines) {
      if ($l -match '^(\S+)\s+(close|closed|block)\b.*session=(\S+)') { $last[$Matches[3]] = @{ t = $Matches[1]; kind = $Matches[2] } }
      elseif ($l -match '^(\S+)\s+(closed|block)\s+session=(\S+)') { $last[$Matches[3]] = @{ t = $Matches[1]; kind = $Matches[2] } }
    }
    foreach ($s in $last.Keys) {
      $e = $last[$s]
      if ($e.kind -eq 'close') {
        $tc = [datetimeoffset]::Parse($e.t)
        $age = (New-TimeSpan -Start $tc.UtcDateTime -End (Get-Date).ToUniversalTime()).TotalMinutes
        $sf = Join-Path $sessdir "$s.txt"
        $active = $false
        if (Test-Path $sf) { $active = ((Get-Date) - (Get-Item $sf).LastWriteTime).TotalMinutes -lt 10 }
        if ($age -gt 10 -and $age -lt 180 -and $active) { $problems += "session $($s.Substring(0,8)) passed its close line $([int]$age) min ago, still working, no closed line" }
      }
    }
  }
  $verdict = if ($dead) { 'dead' } elseif ($problems.Count -gt 0) { 'attention' } else { 'ok' }
  [pscustomobject]@{ ts = Now; verdict = $verdict; problems = $problems; notes = $notes }
}

while ($true) {
  $res = Check
  ($res | ConvertTo-Json -Compress -Depth 4) | Add-Content -Path $logf -Encoding utf8
  $md = @("# fclaude monitor status (rewritten after every check; read this first)", "", "Last check: $($res.ts)  verdict: **$($res.verdict)**", "", "Checks: " + ($res.notes -join '; '))
  if ($res.problems.Count -gt 0) { $md += ""; $md += "Problems:"; $md += ($res.problems | ForEach-Object { "- $_" }) }
  $md += ""; $md += "Last 5 non-ok checks:"
  $bad = @(Get-Content $logf -Tail 600 | ForEach-Object { try { $_ | ConvertFrom-Json } catch {} } | Where-Object { $_.verdict -ne 'ok' } | Select-Object -Last 5)
  if ($bad.Count -eq 0) { $md += "- none" } else { $md += ($bad | ForEach-Object { "- $($_.ts) $($_.verdict): " + ($_.problems -join ' | ') }) }
  $md += ""; $md += "Runs every $IntervalSec s, read-only; it does not restart the lane or start agents. Start: fclaude-monitor-start.cmd, stop: fclaude-monitor-stop.cmd. Log: monitor.jsonl."
  Set-Content -Path $statusf -Value $md -Encoding utf8
  if ($res.verdict -ne 'ok') { Set-Content -Path $attf -Value (@("$($res.ts) $($res.verdict)") + $res.problems) -Encoding utf8 } else { Remove-Item $attf -ErrorAction SilentlyContinue }
  if ($Once) { break }
  $until = (Get-Date).AddSeconds($IntervalSec)
  while ((Get-Date) -lt $until -and -not (Test-Path $stopf)) { Start-Sleep -Seconds 15 }
  if (Test-Path $stopf) { break }
}
if (-not $Once) { Remove-Item $lock -ErrorAction SilentlyContinue; Add-Content -Path $logf -Value ((@{ ts = Now; verdict = 'stopped' } | ConvertTo-Json -Compress)) }
