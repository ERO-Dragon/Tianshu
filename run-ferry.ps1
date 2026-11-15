param(
    [string]$Version = '1.21.1',
    [ValidateSet('neoforge', 'fabric')]
    [string]$Loader = 'neoforge',
    [string]$Task = 'runClient'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$entry = Join-Path (Split-Path -Parent $root) 'Tianshu\run.ps1'
if (-not (Test-Path $entry)) {
    throw "entry script not found: $entry"
}
& $entry -Version $Version -Loader $Loader -Task $Task -Ferry
exit $LASTEXITCODE