#!/usr/bin/env pwsh
param(
    [string]$Version = '',
    [ValidateSet('neoforge', 'fabric')]
    [string]$Loader = 'neoforge',
    [string]$Feature = '',
    [string]$Task = 'runClient'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

& (Join-Path $root 'setup-workspace.ps1')

if ($Version) {
    git -C $root checkout $Version
    if ($LASTEXITCODE -ne 0) { throw "cannot checkout $Version" }
}

if ($Feature) {
    & (Join-Path $root 'gradlew.bat') ":$Feature:$Task" "-Ploader=$Loader"
} else {
    if (-not $Version) { throw 'Version is required for an adapter run' }
    & (Join-Path $root 'gradlew.bat') ":tianshu-adapter:$Version-$Loader:$Task"
}
exit $LASTEXITCODE
