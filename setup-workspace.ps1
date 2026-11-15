#!/usr/bin/env pwsh
# Creates the shared ferry/features worktrees and the adapter worktree once per clone.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspace = Split-Path -Parent $root

function Ensure-Worktree([string]$Path, [string]$Branch, [switch]$AnyBranch) {
    if (Test-Path (Join-Path $Path '.git')) {
        $current = (git -C $Path branch --show-current).Trim()
        if (-not $AnyBranch -and $current -ne $Branch) {
            throw "worktree $Path is on '$current', expected '$Branch'"
        }
        Write-Host "reuse $Path ($Branch)"
        return
    }
    if (Test-Path $Path) {
        throw "$Path exists but is not a git worktree"
    }
    if (git -C $root branch --list $Branch) {
        git -C $root worktree add $Path $Branch
    } else {
        $remote = git -C $root branch -r --list "origin/$Branch"
        if (-not $remote) {
            throw "branch '$Branch' is neither local nor origin/$Branch"
        }
        git -C $root worktree add -b $Branch $Path "origin/$Branch"
    }
    Write-Host "created $Path ($Branch)"
}

Ensure-Worktree (Join-Path $workspace 'Tianshu-ferry') 'ferry'
Ensure-Worktree (Join-Path $workspace 'Tianshu-features') 'features'
Ensure-Worktree (Join-Path $workspace 'Tianshu-adapter') '1.21.1' -AnyBranch

$adapter = Join-Path $workspace 'Tianshu-adapter'
$libs = Join-Path $root 'libs'
$adapterLibs = Join-Path $adapter 'libs'
if ((Test-Path $libs) -and -not (Test-Path $adapterLibs)) {
    New-Item -ItemType Directory -Path $adapterLibs | Out-Null
    Copy-Item (Join-Path $libs '*.jar') $adapterLibs
    Write-Host "copied adapter jars to $adapterLibs"
}
Write-Host 'workspace ready'
