<#
.SYNOPSIS
    Launches every tianshu adapter client in turn, records its log and prints a summary.

.DESCRIPTION
    For each adapter directory that has a build.gradle:
      1. downloadAssets  (online mode; needed once per Minecraft version)
      2. runClient       (starts the game, waits for the main menu, then stops it)
    The game log and the Gradle log are kept under build/smoke/<adapter>/.
    Progress is appended to build/smoke/summary.txt after every adapter, so the
    script can be stopped and started again; adapters already marked PASS are
    skipped unless -Force is given.

.PARAMETER Only
    Restrict the run to these adapter names (comma separated).

.PARAMETER Skip
    Adapter names to leave out.

.PARAMETER Force
    Run every selected adapter again, ignoring an earlier PASS.

.PARAMETER Mode
    auto    (default) try online, fall back to offline when the loader maven is unreachable
    online  always resolve dependencies online
    offline never resolve dependencies online (assets must already be cached)

.PARAMETER TimeoutSeconds
    How long to wait for "Sound engine started" before giving up (default 240).

.EXAMPLE
    .\smoke-adapters.ps1
    .\smoke-adapters.ps1 -Only 1.20.1-fabric,1.20.1-neoforge
    .\smoke-adapters.ps1 -Mode offline -TimeoutSeconds 180
#>
param(
    [string[]] $Only,
    [string[]] $Skip,
    [switch] $Force,
    [ValidateSet('auto', 'online', 'offline')]
    [string] $Mode = 'auto',
    [int] $TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repo

$smokeDir = Join-Path $repo 'build\smoke'
New-Item -ItemType Directory -Force -Path $smokeDir | Out-Null
$summary = Join-Path $smokeDir 'summary.txt'
if (-not (Test-Path $summary)) { Set-Content -Path $summary -Value '' -Encoding UTF8 }

# Existing verdicts, so a second run continues where the first stopped.
$known = @{}
foreach ($line in (Get-Content $summary -Encoding UTF8 -ErrorAction SilentlyContinue)) {
    $parts = $line -split "`t"
    if ($parts.Count -ge 2 -and $parts[0]) { $known[$parts[0]] = $parts[1] }
}

$adapters = Get-ChildItem (Join-Path $repo 'tianshu-adapter') -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'build.gradle') } |
    Where-Object { -not $Only -or $Only -contains $_.Name } |
    Where-Object { -not $Skip -or $Skip -notcontains $_.Name } |
    Sort-Object Name

$crashPattern = 'Crash report saved|Minecraft Crash Report|A fatal error has been detected|' +
    'UnsupportedClassVersionError|NoClassDefFoundError|InvalidModFileException|' +
    'Missing required field|Failed to locate library|Exception in thread "main"|Failed to launch'
$networkPattern = 'Could not resolve .*2\.11\.\+|disabled due to earlier error|' +
    'Could not GET|Connection reset|Read timed out|Failed download after'

$currentMode = if ($Mode -eq 'auto') { 'online' } else { $Mode }

function Stop-GameProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -match 'devlaunch' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Invoke-GradleTask {
    param(
        [string] $Adapter,
        [string] $Task,
        [string] $LogPath
    )
    $offline = if ($currentMode -eq 'offline') { '--offline ' } else { '' }
    $arguments = "/c .\gradlew.bat $offline--console=plain --max-workers=2 :tianshu-adapter:${Adapter}:${Task} > `"$LogPath`" 2>&1"
    $process = Start-Process -FilePath 'cmd.exe' -ArgumentList $arguments -WorkingDirectory $repo `
        -WindowStyle Hidden -PassThru
    $null = $process.WaitForExit(600000)
    return $process.ExitCode
}

function Get-FailureDetail {
    param([string] $LogPath)
    if (-not (Test-Path $LogPath)) { return 'no gradle log' }
    $lines = Get-Content $LogPath -Encoding UTF8
    $parts = @()
    $task = $lines | Where-Object { $_ -match "Execution failed for task '" } | Select-Object -First 1
    if ($task) { $parts += $task.Trim() }
    $net = $lines | Where-Object { $_ -match $networkPattern } | Select-Object -First 1
    if ($net) { $parts += ('network: ' + $net.Trim()) }
    $cause = $lines | Where-Object { $_ -match '^\s*>\s+\S' } | Select-Object -First 2
    if ($cause) { $parts += (($cause | ForEach-Object { $_.Trim() }) -join ' ') }
    if ($parts.Count -eq 0) {
        $parts = @((($lines | Where-Object { $_.Trim() } | Select-Object -Last 2) | ForEach-Object { $_.Trim() }) -join ' | ')
    }
    return (($parts -join ' | ') -replace '\s+', ' ')
}
function Test-NetworkFailure {
    param([string] $LogPath)
    if (-not (Test-Path $LogPath)) { return $false }
    $text = Get-Content $LogPath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    return [bool]($text -and $text -match $networkPattern)
}

$results = @()
$index = 0
foreach ($adapter in $adapters) {
    $index++
    $name = $adapter.Name
    if (-not $Force -and $known.ContainsKey($name) -and $known[$name] -eq 'PASS') {
        Write-Host ("[{0}/{1}] {2}  SKIP (already PASS)" -f $index, $adapters.Count, $name)
        continue
    }

    $dir = Join-Path $smokeDir $name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $gradleLog = Join-Path $dir 'gradle.log'
    $clientLog = Join-Path $dir 'client.log'
    # NeoGradle runs bring the game up in runs/client, ModDevGradle and legacy runs use run/.
    $runLogs = @(
        (Join-Path $adapter.FullName 'run\logs\latest.log'),
        (Join-Path $adapter.FullName 'runs\client\logs\latest.log')
    )
    $runLog = $runLogs | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $runLog) { $runLog = $runLogs[0] }
    foreach ($candidate in $runLogs) {
        if (Test-Path $candidate) { Remove-Item $candidate -Force -ErrorAction SilentlyContinue }
    }
    if (Test-Path $clientLog) { Remove-Item $clientLog -Force -ErrorAction SilentlyContinue }

    $started = Get-Date
    Write-Host ("[{0}/{1}] {2}  downloading assets ({3})" -f $index, $adapters.Count, $name, $currentMode)
    $null = Invoke-GradleTask -Adapter $name -Task 'downloadAssets' -LogPath $gradleLog
    if (Test-Path $gradleLog) {
        $assetText = Get-Content $gradleLog -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($assetText -and $assetText -match 'not found in project') {
            Write-Host '    (this loader has no downloadAssets task; the run task handles assets itself)'
        }
    }
    if (Test-NetworkFailure -LogPath $gradleLog) {
        if ($Mode -eq 'auto' -and $currentMode -eq 'online') {
            Write-Host '    loader maven unreachable, switching to offline mode for the rest of the run'
            $currentMode = 'offline'
            $null = Invoke-GradleTask -Adapter $name -Task 'downloadAssets' -LogPath $gradleLog
        }
    }

    Write-Host ("[{0}/{1}] {2}  starting client" -f $index, $adapters.Count, $name)
    $clientStarted = Get-Date
    $proc = Start-Process -FilePath 'cmd.exe' -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
        -ArgumentList ("/c .\gradlew.bat " + ($(if ($currentMode -eq 'offline') { '--offline ' } else { '' })) +
            "--console=plain --max-workers=2 :tianshu-adapter:${name}:runClient > `"$gradleLog.client`" 2>&1")

    $status = 'TIMEOUT'
    $detail = ''
    $deadline = $clientStarted.AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (Test-Path $runLog) {
            $text = Get-Content $runLog -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($text -match 'Sound engine started') { $status = 'PASS'; break }
            if ($text -match $crashPattern) {
                $line = ($text -split "`r?`n" | Where-Object { $_ -match $crashPattern } | Select-Object -First 1)
                $status = 'CRASH'
                $detail = $line.Trim()
                break
            }
        }
        if ($proc.HasExited -and -not (Test-Path $runLog)) {
            $tail = Get-FailureDetail -LogPath (Join-Path $dir 'gradle.log.client')
            $status = if ($tail -match $networkPattern) { 'NETWORK' } else { 'BUILD_FAIL' }
            $detail = $tail
            break
        }
    }

    if ($status -eq 'TIMEOUT' -and (Test-Path $runLog)) {
        $text = Get-Content $runLog -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($text -match 'TIANSHU_LIBS_BOOTSTRAP_OK') {
            $status = 'PARTIAL'
            $detail = 'bootstrap ok, no main menu within timeout'
        } else {
            $detail = ((Get-Content $runLog -Tail 3 -Encoding UTF8) -join ' | ')
        }
    }

    if (Test-Path $runLog) { Copy-Item $runLog $clientLog -Force -ErrorAction SilentlyContinue }
    Stop-GameProcesses
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2

    $seconds = [int]((Get-Date) - $started).TotalSeconds
    $line = "{0}`t{1}`t{2}`t{3}" -f $name, $status, $seconds, ($detail -replace "\s+", ' ')
    Add-Content -Path $summary -Value $line -Encoding UTF8
    $results += [pscustomobject]@{ Adapter = $name; Status = $status; Seconds = $seconds; Detail = $detail }
    Write-Host ("    -> {0} ({1}s) {2}" -f $status, $seconds, $detail)
}

Write-Host ''
Write-Host '======== summary ========'
$all = @()
foreach ($line in (Get-Content $summary -Encoding UTF8)) {
    $parts = $line -split "`t"
    if ($parts.Count -ge 2 -and $parts[0]) {
        $all += [pscustomobject]@{ Adapter = $parts[0]; Status = $parts[1] }
    }
}
$all | Group-Object Status | ForEach-Object { Write-Host ("{0,-10} {1}" -f $_.Name, $_.Count) }
$all | Sort-Object Adapter | Format-Table -AutoSize | Out-String -Width 120
Write-Host ("logs: {0}" -f $smokeDir)