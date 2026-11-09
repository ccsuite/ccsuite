# Starts fclaude-monitor.ps1 hidden and detached (survives closing Claude and every terminal). Refuses a second copy.
$lock = Join-Path $env:USERPROFILE 'ccsuite\ccacc\monitor\monitor.lock'
if (Test-Path $lock) {
  $old = [int](Get-Content $lock | Select-Object -First 1)
  if ($old -and (Get-Process -Id $old -ErrorAction SilentlyContinue)) { Write-Output "already running, pid $old"; exit 1 }
}
$script = Join-Path $PSScriptRoot 'fclaude-monitor.ps1'
Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script
Start-Sleep -Seconds 5
if (Test-Path $lock) { Write-Output ("started, pid " + (Get-Content $lock | Select-Object -First 1) + "; look: fclaude-monitor-status.cmd") } else { Write-Output 'started, but no lock yet: check fclaude-monitor-status.cmd' }
