#requires -Version 5
<#
  Package jcgraph into distributable zips under dist/:
    jcgraph-<ver>-windows-full.zip  jar + bundled Windows JRE + launchers (self-contained, Windows)
    jcgraph-<ver>-system.zip        jar + launchers only (needs system Java; any OS)

  Usage:
    .\package.ps1            # uses existing target\jcgraph.jar (builds if missing)
    .\package.ps1 -Build     # force `mvn clean package` first
#>
[CmdletBinding()]
param(
  [switch]$Build,
  [string]$JreDir = "$PSScriptRoot\jre"
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$jar  = Join-Path $root "target\jcgraph.jar"

[xml]$pom = Get-Content (Join-Path $root "pom.xml")
$ver = $pom.project.version
if (-not $ver) { $ver = "0.0.0" }
Write-Host "[package] version $ver"

# 1) ensure the shaded jar exists
if ($Build -or -not (Test-Path $jar)) {
  Write-Host "[package] mvn clean package..."
  & mvn -q clean package -DskipTests
  if ($LASTEXITCODE -ne 0) { throw "maven build failed" }
}
if (-not (Test-Path $jar)) { throw "jar not found: $jar" }

# Launchers for the dist (point at the SIBLING jcgraph.jar, not target\).
# Same launcher serves both variants: uses bundled jre\ if present, else system java.
$cmdLauncher = @'
@echo off
setlocal
set "JCG_DIR=%~dp0"
if exist "%JCG_DIR%jre\bin\java.exe" (
  "%JCG_DIR%jre\bin\java.exe" -jar "%JCG_DIR%jcgraph.jar" %*
) else (
  java -jar "%JCG_DIR%jcgraph.jar" %*
)
endlocal
'@
$shLauncher = @'
#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -x "$DIR/jre/bin/java" ]; then
  exec "$DIR/jre/bin/java" -jar "$DIR/jcgraph.jar" "$@"
else
  exec java -jar "$DIR/jcgraph.jar" "$@"
fi
'@

$dist = Join-Path $root "dist"
Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $dist | Out-Null

function New-Variant([string]$name, [bool]$withJre) {
  $vd = Join-Path $dist $name
  New-Item -ItemType Directory -Force -Path $vd | Out-Null
  Copy-Item $jar (Join-Path $vd "jcgraph.jar")
  Set-Content -Path (Join-Path $vd "jcgraph.cmd") -Value $cmdLauncher -Encoding ASCII
  [System.IO.File]::WriteAllText((Join-Path $vd "jcgraph"), ($shLauncher -replace "`r`n","`n"))
  if (Test-Path (Join-Path $root "README.md")) { Copy-Item (Join-Path $root "README.md") $vd }
  if ($withJre) {
    if (-not (Test-Path (Join-Path $JreDir "bin\java.exe"))) { throw "no bundled JRE at $JreDir" }
    robocopy $JreDir (Join-Path $vd "jre") /E /MT:8 /NFL /NDL /NJH /NP | Out-Null
    # robocopy uses 0-7 for success (1 = files copied); only >=8 is a real error.
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed copying JRE (code $LASTEXITCODE)" }
    $global:LASTEXITCODE = 0
  }
  return $vd
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Compress-Variant([string]$dir) {
  $zip = "$dir.zip"
  if (Test-Path $zip) { Remove-Item $zip -Force }
  [System.IO.Compression.ZipFile]::CreateFromDirectory($dir, $zip)
}

$full   = New-Variant "jcgraph-$ver-windows-full" $true
$system = New-Variant "jcgraph-$ver-system" $false
Compress-Variant $full
Compress-Variant $system

Write-Host "[package] done:"
Get-ChildItem $dist -Filter *.zip | ForEach-Object { "  {0,-36} {1,8:N1} MB" -f $_.Name, ($_.Length/1MB) }
