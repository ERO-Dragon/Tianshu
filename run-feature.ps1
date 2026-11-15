param(
    [string]$Feature = '',
    [ValidateSet('neoforge', 'fabric')]
    [string]$Loader = 'neoforge',
    [string]$Task = 'runClient'
)
$ErrorActionPreference = 'Stop'
$featuresRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Feature) {
    # Invoked from a module directory: use that module name.
    $Feature = Split-Path -Leaf (Get-Location)
}
$adapterRoot = Join-Path (Split-Path -Parent $featuresRoot) 'Tianshu'
$entry = Join-Path $adapterRoot 'run.ps1'
if (-not (Test-Path $entry)) {
    throw "adapter entry not found: $entry"
}
& $entry -Feature $Feature -Loader $Loader -Task $Task
exit $LASTEXITCODE