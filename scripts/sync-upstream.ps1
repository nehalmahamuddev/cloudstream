<#
.SYNOPSIS
    Syncs the local and remote repository with official CloudStream updates.
.DESCRIPTION
    1. Fetches latest commits from recloudstream/cloudstream
    2. Fast-forwards your local 'master' branch
    3. Pushes updated 'master' to your fork (origin/master)
    4. Optionally merges updated 'master' into your custom 'upstream' branch
#>

[CmdletBinding()]
param(
    [bool]$MergeIntoCurrentBranch = $true
)

$ErrorActionPreference = "Stop"

Write-Host "==> Fetching updates from official CloudStream (upstream-official)..." -ForegroundColor Cyan
git fetch upstream-official master

$currentBranch = (git branch --show-current).Trim()

Write-Host "==> Switching to 'master' to fast-forward..." -ForegroundColor Cyan
git checkout master
git merge --ff-only upstream-official/master

Write-Host "==> Pushing updated 'master' to your GitHub fork (origin)..." -ForegroundColor Cyan
git push origin master

if ($MergeIntoCurrentBranch -and $currentBranch -ne "master") {
    Write-Host "==> Switching back to '$currentBranch'..." -ForegroundColor Cyan
    git checkout $currentBranch

    Write-Host "==> Merging updated master into '$currentBranch'..." -ForegroundColor Cyan
    git merge master -m "chore(sync): merge latest official master into $currentBranch"
    
    Write-Host "==> Pushing updated '$currentBranch' to your GitHub fork..." -ForegroundColor Cyan
    git push origin $currentBranch
}

Write-Host "==> Sync complete successfully!" -ForegroundColor Green


