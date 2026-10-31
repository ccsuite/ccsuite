# Publish a ccacc release that `ccacc update` and the install lines pick up. Run from this folder:
#
#     .\release.ps1                  build, checksum and publish the version in ccacc/pom.xml
#     .\release.ps1 -DryRun          build and checksum only, show what would be published
#     .\release.ps1 -Changes f.md    put f.md (what changed) at the top of the release notes
#
# One jar per platform (JavaFX carries its natives per platform): ccacc-win-<sha8>.jar and the two Mac jars, each run
# by its own launcher: ccacc.exe, ccacc-macos-arm64, ccacc-macos-x86_64 (jr, built by jr-maven-plugin). The asset names
# never change between releases, only their contents, so the install lines keep working. A version X.Y.Z-label is a
# pre-release: its launchers follow the beta channel of the update file and the stable channel is left as it was.
#
# The update file (ccacc.json) goes into the site repository beside this one (..\ccsuite.github.io\update\), which is
# committed and pushed only AFTER every asset URL of the release answers, per the lesson of cfucli 0.5.1: GitHub once
# served a checksum file with HTTP 500 while the API had it.
#
# $env:GH names the GitHub CLI (default gh). Requires a clean, pushed working tree: a release matches a public commit.
param([switch]$DryRun, [string]$Changes)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$gh = if ($env:GH) { $env:GH } else { 'gh' }
$version = ([xml](Get-Content ccacc\pom.xml)).project.version
$tag = "v$version"
$pre = $version -match '-'
$repo = 'ccsuite/ccsuite'
$site = Join-Path $PSScriptRoot '..\ccsuite.github.io'
$update = Join-Path $site 'update\ccacc.json'
$target = Join-Path $PSScriptRoot 'ccacc\target'
$out = Join-Path $target 'jr\release'
$all = Join-Path $target 'jr\release-all'

function Sha([string]$ref) { @(git rev-parse $ref) | Where-Object { $_ -match '^[0-9a-f]{40}$' } | Select-Object -Last 1 }
if (-not $DryRun) {
    $dirty = @(git status --porcelain) | Where-Object { $_ -match '^[ MADRCU?!]{2} ' }
    if ($dirty) { throw "the working tree is not clean - commit first:`n$($dirty -join "`n")" }
    # ssh may print a goodbye line on stderr; that is not an error (same cause as the gh call below)
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    git fetch -q origin 2>&1 | Out-Null
    $ErrorActionPreference = $prev
    if ((Sha HEAD) -ne (Sha '@{u}')) { throw 'HEAD is not pushed - push first' }
    if (-not (Test-Path (Join-Path $site '.git'))) { throw "no site working copy at $site" }
}

function Build([string[]]$mvnArgs) {
    for ($i = 1; $i -le 4; $i++) { & mvn -q -DskipTests @mvnArgs; if ($LASTEXITCODE -eq 0) { return }; Write-Host "build attempt $i failed, retrying" }
    throw "mvn $mvnArgs failed"
}

Write-Host "building $tag"
# The Mac jars first: the Windows run puts them in the release folder and fails if one is missing.
Build @('-Pmac-aarch64', '-pl', 'ccacc', '-am', 'package')
Build @('-Pmac', '-pl', 'ccacc', '-am', 'package')
Build @('-Pwin', '-pl', 'ccacc', '-am', 'package')
$jr = @('-Djrexe', '-Djr.source=url', '-pl', 'ccacc', 'verify')
if ($pre) { $jr = @('-Djrexe.channel=beta') + $jr }
$merge = @(if (Test-Path $update) { "-Djr.updateMergeFrom=$((Resolve-Path $update).Path)" })
Build ($merge + @('-Pwin,release-windows') + $jr)
if (Test-Path $all) { Remove-Item -Recurse -Force $all }
New-Item -ItemType Directory -Path $all | Out-Null
Copy-Item (Join-Path $out '*') $all
foreach ($p in @('mac-aarch64', 'mac')) {
    Build (@("-P$p", "-Djr.updateMergeFrom=$(Join-Path $all 'ccacc.update.json')") + $jr)
    Copy-Item (Join-Path $out '*') $all -Force
}
# The unhashed Mac jars are only inputs of the Windows run; the launchers fetch the hashed ones.
Remove-Item (Join-Path $all 'ccacc-mac.jar'), (Join-Path $all 'ccacc-mac-aarch64.jar') -ErrorAction SilentlyContinue
$sums = Get-ChildItem $all -File | Where-Object { $_.Name -notin @('SHA256SUMS', 'ccacc.update.json') } | Sort-Object Name |
    ForEach-Object { "$((Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower())  $($_.Name)" }
[IO.File]::WriteAllText((Join-Path $all 'SHA256SUMS'), (($sums -join "`n") + "`n"))
foreach ($f in @('ccacc.exe', 'ccacc-win-*.jar', 'ccacc-macos-arm64', 'ccacc-mac-aarch64-*.jar', 'ccacc-macos-x86_64', 'ccacc-mac-????????.jar', 'version.txt', 'SHA256SUMS', 'ccacc.update.json')) {
    if (-not (Test-Path (Join-Path $all $f))) { throw "the release folder has no $f - see the jr output above" }
}
Get-ChildItem $all | ForEach-Object { Write-Host ('  {0,-34} {1,7:N1} MB' -f $_.Name, ($_.Length / 1MB)) }
if ($DryRun) { Write-Host "dry run - nothing published. Assets are in $all"; return }

$base = "https://github.com/$repo/releases/download/$tag"
$notes = "Install on Windows, in cmd or PowerShell:`n`n    curl.exe -fLo ccacc.exe $base/ccacc.exe`n    .\ccacc.exe install --start-at-login" +
         "`n`nInstall on a Mac, in Terminal (one line; uname -m picks Apple silicon or Intel):`n`n    curl -fLo /tmp/ccacc $base/ccacc-macos-`$(uname -m) && chmod +x /tmp/ccacc && /tmp/ccacc -Xjr:yes install --start-at-login" +
         "`n`nThe Mac binary is signed ad hoc, not by an Apple developer: downloaded with curl it opens as is. Update later with ``ccacc update``." +
         $(if ($pre) { "`n`nThis is a pre-release (beta): it updates along the beta channel. Windows is in daily use; on the Mac the tray icon, the windows and the Keychain access have not yet been tried by a person." } else { '' })
if ($Changes) { $notes = [IO.File]::ReadAllText((Resolve-Path $Changes).Path).Trim() + "`n`n" + $notes }
$notesFile = Join-Path $target 'jr\release-notes.md'
[IO.File]::WriteAllText($notesFile, $notes + "`n")
$assets = (Get-ChildItem $all -File | Where-Object Name -ne 'ccacc.update.json').FullName
$flags = if ($pre) { @('--prerelease', '--latest=false') } else { @('--latest=true') }
# gitgate's gh prints its ACCOUNT notice on stderr; with ErrorActionPreference Stop PowerShell turns that into a terminating error
# before gh has run (2026-10-31, this script and the known cfucli release.ps1 failure). So stderr is shown, not fatal, and the exit code decides.
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
& $gh release create $tag @assets --repo $repo --title "ccacc $version" --notes-file $notesFile --target (Sha HEAD) @flags 2>&1 | ForEach-Object { "$_" } | Write-Host
$code = $LASTEXITCODE
$ErrorActionPreference = $prev
if ($code) { throw "gh release create failed ($code)" }

# Every asset URL must answer 200 BEFORE the site goes live.
foreach ($a in (Get-ChildItem $all -File | Where-Object Name -ne 'ccacc.update.json')) {
    $code = (& curl.exe -s -L -o NUL -w '%{http_code}' "$base/$($a.Name)")
    if ($code -ne '200') { throw "$base/$($a.Name) answered $code - the site was NOT updated" }
}
New-Item -ItemType Directory -Force (Split-Path $update) | Out-Null
Copy-Item (Join-Path $all 'ccacc.update.json') $update -Force
Write-Host "release $tag published, every asset answered 200. Now commit and push the site repo: $site"
