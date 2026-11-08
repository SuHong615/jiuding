# Copyright 2026 suhong
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# @author suhong
#Requires -Version 5.1
<#
.SYNOPSIS
    Full build: `mvn install` for the whole reactor (or a module subset).

.EXAMPLE
    pwsh ./scripts/build.ps1                 # full reactor install (skip nothing)
    pwsh ./scripts/build.ps1 -SkipTests      # compile + install without tests
    pwsh ./scripts/build.ps1 -Module tenant-service,gateway
    pwsh ./scripts/build.ps1 -Clean -Offline

.NOTES
    * The reactor requires every module directory listed in the root pom.xml to contain
      a pom.xml. While teammates are still writing modules, the script falls back to
      building the available modules one by one (in dependency order) unless -Reactor
      is passed explicitly.
    * Idempotent. Exit code 1 when any selected build fails.
#>
[CmdletBinding()]
param(
    [string[]]$Module,
    [switch]$Reactor,
    [switch]$SkipTests,
    [switch]$Clean,
    [switch]$Offline,
    [switch]$KeepGoing
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

if (-not $global:TenancyMvn) { throw 'Maven not found. Run scripts/env.ps1 for details.' }

$root = $global:TenancyRoot
$allModules = @(
    'agent-kernel',
    'tenancy-common',
    'tenant-service',
    'approval-service',
    'agent-service',
    'mcp-service',
    'gateway',
    'tenancy-cli'
)

function Get-CommonArgs {
    $arguments = @('-B')
    if ($Offline) { $arguments += '--offline' }
    if ($Clean) { $arguments += 'clean' }
    if ($SkipTests) { $arguments += '-DskipTests' }
    $arguments += 'install'
    return $arguments
}

function Invoke-MavenBuild {
    param([string[]]$MavenArgs, [string]$Label)
    Write-Host ''
    Write-Host ("==> {0}" -f $Label) -ForegroundColor Cyan
    Write-Host ("    {0} {1}" -f $global:TenancyMvn, ($MavenArgs -join ' ')) -ForegroundColor DarkGray
    # Pipe to Out-Host so Maven output is displayed instead of captured by the caller
    # (otherwise `$exit = Invoke-MavenBuild` would capture every Maven output line).
    & $global:TenancyMvn @MavenArgs | Out-Host
    return $LASTEXITCODE
}

$selected = $allModules
if ($Module -and $Module.Count -gt 0) {
    $selected = $Module
}

# Determine whether the repository is structurally complete (every module has a pom.xml).
$missingModules = @()
foreach ($name in $allModules) {
    if (-not (Test-Path (Join-Path $root "$name\pom.xml"))) { $missingModules += $name }
}
$complete = ($missingModules.Count -eq 0)

$commonArgs = Get-CommonArgs
$failures = @()
$built = @()
$skipped = @()

if ($complete -and (-not $Module -or $Module.Count -eq 0) -and -not $Reactor) {
    $exit = Invoke-MavenBuild -MavenArgs (@($commonArgs) + @('-f', (Join-Path $root 'pom.xml'))) -Label 'reactor install'
    if ($exit -ne 0) { $failures += 'reactor' } else { $built += 'reactor' }
}
elseif ($complete -and $Module -and $Module.Count -gt 0) {
    $pl = ($selected -join ',')
    $exit = Invoke-MavenBuild -MavenArgs (@($commonArgs) + @('-f', (Join-Path $root 'pom.xml'), '-pl', $pl, '-am')) -Label "reactor install (pl=$pl)"
    if ($exit -ne 0) { $failures += $pl } else { $built += $pl }
}
else {
    if (-not $complete) {
        Write-Warning ("Reactor incomplete; missing module pom(s): {0}. Building available modules one-by-one." -f ($missingModules -join ', '))
    }
    foreach ($name in $selected) {
        $pom = Join-Path $root "$name\pom.xml"
        if (-not (Test-Path $pom)) {
            Write-Host ("[SKIP] {0} (no pom.xml yet)" -f $name) -ForegroundColor Yellow
            $skipped += $name
            continue
        }
        $exit = Invoke-MavenBuild -MavenArgs (@($commonArgs) + @('-f', $pom)) -Label $name
        if ($exit -ne 0) {
            Write-Host ("[FAIL] {0} (exit {1})" -f $name, $exit) -ForegroundColor Red
            $failures += $name
            if (-not $KeepGoing) { break }
        }
        else {
            Write-Host ("[ OK ] {0}" -f $name) -ForegroundColor Green
            $built += $name
        }
    }
}

Write-Host ''
Write-Host '---------------- build summary ----------------'
Write-Host ("built   : {0}" -f (($built | ForEach-Object { $_ }) -join ', '))
if ($skipped.Count -gt 0) { Write-Host ("skipped : {0}" -f ($skipped -join ', ')) -ForegroundColor Yellow }
if ($failures.Count -gt 0) {
    Write-Host ("failed  : {0}" -f ($failures -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host 'result  : PASS' -ForegroundColor Green
exit 0
