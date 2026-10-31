# Rebuild ccacc and put the new version in place, the way a jr app is kept:
#   the jar lives in a stable folder (-AppDir), the exe is built against that exact jar (the exe checks its SHA-256),
#   `ccacc install` repairs the copy in the user's programs folder, and ccacc is started again.
# Run from the ccsuite root:   powershell -File ccacc\pkg\redeploy.ps1 -AppDir D:\apps\ccacc
param(
    [Parameter(Mandatory = $true)][string]$AppDir,
    [switch]$NoStart,
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
New-Item -ItemType Directory -Force $AppDir | Out-Null

Write-Host '1. build the jar'
$mvnArgs = @('-o', '-q', 'package', '-pl', 'ccacc', '-am')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
Push-Location $root
try { & mvn @mvnArgs; if ($LASTEXITCODE) { throw 'build failed' } } finally { Pop-Location }

Write-Host '2. stop ccacc (it holds the jar open)'
Get-Process -Name ccacc -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.Id -Force; Write-Host "   stopped pid $($_.Id)" }
Start-Sleep -Seconds 2

Write-Host '3. new jar into the app folder; the old AOT caches are for the old jar'
Copy-Item (Join-Path $root 'ccacc\target\ccacc.jar') (Join-Path $AppDir 'ccacc.jar') -Force
Remove-Item (Join-Path $AppDir 'ccacc.*.aot'), (Join-Path $AppDir 'ccacc.*.aot.config') -ErrorAction SilentlyContinue

Write-Host '4. exe built against that jar'
Push-Location $root
try {
    & mvn -o -q -Djrexe "-Dccacc.jar=$AppDir\ccacc.jar" "-Djr.installDir=$AppDir" 'io.github.jarrunner:jr-maven-plugin:1.3.0:exe@ccacc' -pl ccacc
    if ($LASTEXITCODE) { throw 'exe build failed' }
} finally { Pop-Location }

Write-Host '5. repair the installed copy (programs folder, PATH, Start Menu, start at sign-in)'
Push-Location $env:TEMP
try { & (Join-Path $AppDir 'ccacc.exe') install --start-at-login } finally { Pop-Location }

if (-not $NoStart) {
    Write-Host '6. start ccacc'
    Start-Process (Join-Path $env:LOCALAPPDATA 'Programs\ccacc\ccacc.exe') -ArgumentList 'app' -WindowStyle Hidden
}
