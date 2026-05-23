#requires -Version 5
<#
  jcgraph one-shot install / update (Windows).

  Builds target\jcgraph.jar and registers THIS folder on the user PATH, so the
  `jcgraph` command works from any directory (via jcgraph.cmd). Re-run anytime to
  update: it rebuilds the jar (and `git pull`s first if this is a git checkout).
  Fully idempotent.

  Usage:
    .\install.ps1                                 # build + add to PATH
    .\install.ps1 -JavaHome E:\Envs\java\java8    # if no JDK is auto-detected
    .\install.ps1 -SkipPull                       # don't git pull
    .\install.ps1 -SkipPath                       # build only, leave PATH alone
#>
[CmdletBinding()]
param(
  [string]$JavaHome,
  [switch]$SkipPull,
  [switch]$SkipPath
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Write-Host "[jcgraph] install/update from $root"

# 1) refresh sources if this is a git checkout (no-op otherwise)
if (-not $SkipPull) {
  if ((Test-Path (Join-Path $root ".git")) -and (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "[jcgraph] git pull --ff-only"
    & git -C $root pull --ff-only
  } else {
    Write-Host "[jcgraph] not a git checkout (or git missing); skipping pull"
  }
}

# 2) locate a JDK (needs javac.exe; a JRE is not enough to build)
function Test-Jdk([string]$dir) { $dir -and (Test-Path (Join-Path $dir "bin\javac.exe")) }
$jdk = $null
foreach ($c in @($JavaHome, $env:JAVA_HOME)) { if (Test-Jdk $c) { $jdk = $c; break } }
if (-not $jdk) {
  $roots = @("E:\Envs\java", "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
             "$env:ProgramFiles\Microsoft\jdk", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium")
  foreach ($r in $roots) {
    if (Test-Path $r) {
      $hit = Get-ChildItem $r -Directory -ErrorAction SilentlyContinue |
             Where-Object { Test-Jdk $_.FullName } | Select-Object -First 1
      if ($hit) { $jdk = $hit.FullName; break }
    }
  }
}
if (-not $jdk) {
  throw "No JDK (with javac) found. Pass -JavaHome <jdk>, e.g. -JavaHome E:\Envs\java\java8"
}
Write-Host "[jcgraph] JDK: $jdk"

# 3) maven must be on PATH
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
  throw "maven (mvn) not found on PATH. Install Maven or add it to PATH, then re-run."
}

# 4) build the shaded jar
Write-Host "[jcgraph] mvn clean package -DskipTests ..."
$env:JAVA_HOME = $jdk
& mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "maven build failed" }
$jar = Join-Path $root "target\jcgraph.jar"
if (-not (Test-Path $jar)) { throw "build produced no jar: $jar" }
Write-Host ("[jcgraph] built {0} ({1:N1} MB)" -f $jar, ((Get-Item $jar).Length / 1MB))

# 5) register this folder on the user PATH so `jcgraph` resolves anywhere
if (-not $SkipPath) {
  $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
  $entries = @()
  if ($userPath) { $entries = $userPath -split ';' | Where-Object { $_ -ne '' } }
  $present = $entries | Where-Object { $_.TrimEnd('\') -ieq $root.TrimEnd('\') }
  if ($present) {
    Write-Host "[jcgraph] user PATH already contains $root"
  } else {
    [Environment]::SetEnvironmentVariable("Path", (@($entries + $root) -join ';'), "User")
    Write-Host "[jcgraph] added $root to user PATH (open a NEW terminal to pick it up)"
  }
  if ($env:Path -notlike "*$root*") { $env:Path = "$env:Path;$root" }  # this session
} else {
  Write-Host "[jcgraph] -SkipPath: leaving PATH unchanged"
}

# 6) sanity note about the runtime the launcher will use
if (-not (Test-Path (Join-Path $root "jre\bin\java.exe"))) {
  Write-Host "[jcgraph] note: no bundled jre\ here; the launcher will fall back to system java"
}

Write-Host ""
Write-Host "[jcgraph] done. Try:  jcgraph index path\to\app.jar"
Write-Host "[jcgraph] update later by re-running:  .\install.ps1"
