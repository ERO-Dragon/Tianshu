#!/usr/bin/env pwsh
# Creates the shared ferry/features worktrees once per clone. Idempotent.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Ensure-Worktree([string]$Path, [string]$Branch) {
    if (Test-Path (Join-Path $Path '.git')) {
        $current = (git -C $Path branch --show-current).Trim()
        if ($current -ne $Branch) {
            throw "worktree $Path is on '$current', expected '$Branch'"
        }
        Write-Host "reuse $Path ($Branch)"
        return
    }
    if (Test-Path $Path) {
        throw "$Path exists but is not a git worktree"
    }
    $local = git -C $root branch --list $Branch
    if ($local) {
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

Ensure-Worktree (Join-Path (Split-Path -Parent $root) 'Tianshu-ferry') 'ferry'
Ensure-Worktree (Join-Path (Split-Path -Parent $root) 'Tianshu-features') 'features'
Write-Host 'workspace ready'