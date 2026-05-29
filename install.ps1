<#
  jcgraph installer (Windows).

  DEFAULT — one-command remote install. Downloads the latest prebuilt,
  self-contained release (bundled JRE — no system Java needed) and puts
  `jcgraph` on your PATH:

    irm https://raw.githubusercontent.com/7-e1even/jcgraph/main/install.ps1 | iex

  CHECKOUT — run from a source tree (or pass -Build) to build target\jcgraph.jar
  from source instead (needs a JDK with javac + Maven). This is the old behavior.

    .\install.ps1                  # in a checkout -> build; piped via irm -> download
    .\install.ps1 -Download        # force download of the latest release
    .\install.ps1 -Build           # force build from source (needs JDK + Maven)
    .\install.ps1 -Version v0.1.0  # download a specific release tag
    .\install.ps1 -Dir C:\tools\jcgraph   # install location (download mode)
    .\install.ps1 -JavaHome <jdk>  # JDK for -Build when none is auto-detected
    .\install.ps1 -SkipPull        # -Build: don't git pull first
    .\install.ps1 -SkipPath        # don't touch the user PATH

  Release-asset contract (download mode), for a release tagged <tag>:
    jcgraph-<ver>-windows-full.zip   (ver = <tag> without a leading "v")
    containing a top dir with a jcgraph.cmd launcher (+ bundled jre\, jcgraph.jar).
  This is exactly what package.ps1 emits — attach it to the GitHub release.
#>
[CmdletBinding()]
param(
  [switch]$Download,
  [switch]$Build,
  [string]$Version,
  [string]$Dir,
  [string]$JavaHome,
  [switch]$SkipPull,
  [switch]$SkipPath
)
$ErrorActionPreference = "Stop"

$Repo = if ($env:JCGRAPH_REPO) { $env:JCGRAPH_REPO } else { "7-e1even/jcgraph" }
if (-not $Dir) {
  $Dir = if ($env:JCGRAPH_HOME) { $env:JCGRAPH_HOME } else { Join-Path $env:LOCALAPPDATA "Programs\jcgraph" }
}

# Script dir — only meaningful from a checkout. Empty when run via `irm | iex`.
$self = if ($PSScriptRoot) { $PSScriptRoot } elseif ($PSCommandPath) { Split-Path -Parent $PSCommandPath } else { "" }

$mode =
  if ($Download) { "download" }
  elseif ($Build) { "build" }
  elseif ($self -and (Test-Path (Join-Path $self "pom.xml"))) { "build" }
  else { "download" }

function Add-UserPath([string]$dir) {
  if ($SkipPath) { Write-Host "[jcgraph] -SkipPath: add to PATH yourself: `$env:Path += ';$dir'"; return }
  $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
  $entries = @()
  if ($userPath) { $entries = $userPath -split ';' | Where-Object { $_ -ne '' } }
  $present = $entries | Where-Object { $_.TrimEnd('\') -ieq $dir.TrimEnd('\') }
  if ($present) {
    Write-Host "[jcgraph] user PATH already contains $dir"
  } else {
    [Environment]::SetEnvironmentVariable("Path", (@($entries + $dir) -join ';'), "User")
    Write-Host "[jcgraph] added $dir to user PATH (open a NEW terminal to pick it up)"
  }
  if ($env:Path -notlike "*$dir*") { $env:Path = "$env:Path;$dir" }
}

function Invoke-BuildFromSource {
  if (-not ($self -and (Test-Path (Join-Path $self "pom.xml")))) {
    throw "-Build needs a source checkout (no pom.xml next to this script). Clone the repo and run .\install.ps1 from inside it, or drop -Build to download."
  }
  $root = $self
  Write-Host "[jcgraph] build/install from $root"
  if (-not $SkipPull -and (Test-Path (Join-Path $root ".git")) -and (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "[jcgraph] git pull --ff-only"; & git -C $root pull --ff-only
  }
  function Test-Jdk([string]$d) { $d -and (Test-Path (Join-Path $d "bin\javac.exe")) }
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
  if (-not $jdk) { throw "No JDK (with javac) found. Pass -JavaHome <jdk>." }
  Write-Host "[jcgraph] JDK: $jdk"
  if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw "maven (mvn) not found on PATH." }
  Write-Host "[jcgraph] mvn clean package -DskipTests ..."
  $env:JAVA_HOME = $jdk
  & mvn -q clean package -DskipTests
  if ($LASTEXITCODE -ne 0) { throw "maven build failed" }
  $jar = Join-Path $root "target\jcgraph.jar"
  if (-not (Test-Path $jar)) { throw "build produced no jar: $jar" }
  Write-Host ("[jcgraph] built {0} ({1:N1} MB)" -f $jar, ((Get-Item $jar).Length / 1MB))
  Add-UserPath $root
  if (-not (Test-Path (Join-Path $root "jre\bin\java.exe"))) {
    Write-Host "[jcgraph] note: no bundled jre\ here; the launcher falls back to system java"
  }
  Write-Host ""
  Write-Host "[jcgraph] done. Try:  jcgraph index path\to\app.jar"
  Write-Host "[jcgraph] update later by re-running:  .\install.ps1 -Build"
}

function Invoke-DownloadRelease {
  $tag = $Version
  if (-not $tag) {
    Write-Host "[jcgraph] resolving latest release of $Repo ..."
    try {
      $rel = Invoke-RestMethod -UseBasicParsing -Headers @{ "User-Agent" = "jcgraph-install" } `
               -Uri "https://api.github.com/repos/$Repo/releases/latest"
    } catch {
      throw "could not query releases for $Repo (no releases yet, or the repo is private?). $_"
    }
    $tag = $rel.tag_name
    if (-not $tag) { throw "no releases found for $Repo (publish one first, or pass -Version)." }
  }
  $ver = $tag -replace '^v', ''
  $asset = "jcgraph-$ver-windows-full.zip"
  $url = "https://github.com/$Repo/releases/download/$tag/$asset"

  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("jcg-" + [System.IO.Path]::GetRandomFileName())
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  try {
    $zip = Join-Path $tmp "jcgraph.zip"
    Write-Host "[jcgraph] downloading $asset ($tag) ..."
    try {
      Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
    } catch {
      throw "download failed: $url`n  Check release '$tag' has an asset named '$asset' (build it with .\package.ps1 and attach it)."
    }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $src = Get-ChildItem $tmp -Directory |
           Where-Object { Test-Path (Join-Path $_.FullName "jcgraph.cmd") } | Select-Object -First 1
    if (-not $src) { throw "extracted archive has no jcgraph.cmd launcher." }
    Write-Host "[jcgraph] installing to $Dir"
    if (Test-Path $Dir) { Remove-Item $Dir -Recurse -Force -Confirm:$false }
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    Copy-Item -Path (Join-Path $src.FullName "*") -Destination $Dir -Recurse -Force
    if (-not (Test-Path (Join-Path $Dir "jcgraph.cmd"))) { throw "install dir has no jcgraph.cmd after copy." }
    Add-UserPath $Dir
    Write-Host ""
    Write-Host "[jcgraph] done - $tag installed. Try:  jcgraph index path\to\app.jar"
    Write-Host "[jcgraph] update later by re-running this installer."
  } finally {
    Remove-Item $tmp -Recurse -Force -Confirm:$false -ErrorAction SilentlyContinue
  }
}

if ($mode -eq "build") { Invoke-BuildFromSource } else { Invoke-DownloadRelease }
