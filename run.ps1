#!/usr/bin/env pwsh
param(
    [string]$Version = '1.21.1',
    [ValidateSet('neoforge', 'fabric')]
    [string]$Loader = 'neoforge',
    [string]$Feature = '',
    [string]$Task = 'runClient'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root 'setup-workspace.ps1')
$adapter = Join-Path (Split-Path -Parent $root) 'Tianshu-adapter'
if (-not (Test-Path $adapter)) { throw "adapter worktree missing: $adapter" }
git -C $adapter checkout $Version
if ($LASTEXITCODE -ne 0) { throw "cannot checkout $Version in adapter worktree" }
$gradlew = Join-Path $adapter 'gradlew.bat'
if ($Feature) {
    & $gradlew ":$Feature:$Task" "-Ploader=$Loader"
} else {
    & $gradlew ":tianshu-adapter:$Version-$Loader:$Task"
}
exit $LASTEXITCODE
