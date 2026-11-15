#!/usr/bin/env pwsh
param(
    [string]$Version = '1.21.1',
    [ValidateSet('neoforge', 'fabric')]
    [string]$Loader = 'neoforge',
    [string]$Feature = '',
    [string]$Task = 'runClient',
    [switch]$Ferry
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root 'setup-workspace.ps1')
$adapter = Join-Path (Split-Path -Parent $root) 'Tianshu-adapter'
if (-not (Test-Path $adapter)) { throw "adapter worktree missing: $adapter" }
cmd /c "git -C `"$adapter`" checkout adapter/$Version >nul 2>&1"
if ($LASTEXITCODE -ne 0) { throw "cannot checkout $Version in adapter worktree" }
$gradlew = Join-Path $adapter 'gradlew.bat'
Push-Location $adapter
if ($Ferry) {
    & $gradlew ":tianshu-adapter:${Version}-${Loader}:${Task}" "-PfeatureMods="
} elseif ($Feature) {
    & $gradlew ":${Feature}:${Task}" "-Ploader=$Loader"
} else {
    & $gradlew ":tianshu-adapter:${Version}-${Loader}:${Task}"
}
Pop-Location
exit $LASTEXITCODE




