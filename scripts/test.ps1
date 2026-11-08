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
    Full test run: `mvn test` for the whole reactor (or a module subset).

.EXAMPLE
    pwsh ./scripts/test.ps1                     # reactor tests (or per module when incomplete)
    pwsh ./scripts/test.ps1 -Module tenancy-cli
    pwsh ./scripts/test.ps1 -KeepGoing          # continue after a failing module

.NOTES
    Modules that have no pom.xml yet are reported as SKIP (parallel development).
    Exit code 1 only when a module that exists fails its tests.
#>
[CmdletBinding()]
param(
    [string[]]$Module,
    [switch]$KeepGoing,
    [switch]$Offline
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
$selected = if ($Module -and $Module.Count -gt 0) { $Module } else { $allModules }

$missing = @()
foreach ($name in $allModules) {
    if (-not (Test-Path (Join-Path $root "$name\pom.xml"))) { $missing += $name }
}

$results = New-Object System.Collections.Generic.List[object]

function Invoke-ModuleTest {
    param([string]$Name)
    $pom = Join-Path $root "$Name\pom.xml"
    if (-not (Test-Path $pom)) {
        $results.Add([pscustomobject]@{ Module = $Name; Status = 'SKIP'; Detail = 'no pom.xml yet' })
        Write-Host ("[SKIP] {0}" -f $Name) -ForegroundColor Yellow
        return $true
    }
    Write-Host ''
    Write-Host ("==> test {0}" -f $Name) -ForegroundColor Cyan
    $arguments = @('-B', '-f', $pom, 'test')
    if ($Offline) { $arguments += '--offline' }
    # Pipe to Out-Host so Maven output is displayed and not captured into the boolean return value.
    & $global:TenancyMvn @arguments | Out-Host
    $exit = $LASTEXITCODE
    if ($exit -eq 0) {
        $results.Add([pscustomobject]@{ Module = $Name; Status = 'PASS'; Detail = '' })
        Write-Host ("[ OK ] {0}" -f $Name) -ForegroundColor Green
        return $true
    }
    $results.Add([pscustomobject]@{ Module = $Name; Status = 'FAIL'; Detail = "mvn exited $exit" })
    Write-Host ("[FAIL] {0} (exit {1})" -f $Name, $exit) -ForegroundColor Red
    return $false
}

$allPassed = $true
if ($missing.Count -eq 0 -and (-not $Module -or $Module.Count -eq 0)) {
    Write-Host '==> reactor test' -ForegroundColor Cyan
    $arguments = @('-B', '-f', (Join-Path $root 'pom.xml'), 'test')
    if ($Offline) { $arguments += '--offline' }
    & $global:TenancyMvn @arguments
    $exit = $LASTEXITCODE
    $status = if ($exit -eq 0) { 'PASS' } else { 'FAIL' }
    $results.Add([pscustomobject]@{ Module = 'reactor'; Status = $status; Detail = "exit $exit" })
    if ($exit -ne 0) { $allPassed = $false }
}
else {
    foreach ($name in $selected) {
        $ok = Invoke-ModuleTest -Name $name
        if (-not $ok) {
            $allPassed = $false
            if (-not $KeepGoing) { break }
        }
    }
}

Write-Host ''
Write-Host '---------------- test summary ----------------'
$results | Format-Table -AutoSize | Out-String | Write-Host
$passed = @($results | Where-Object { $_.Status -eq 'PASS' }).Count
$failed = @($results | Where-Object { $_.Status -eq 'FAIL' }).Count
$skipped = @($results | Where-Object { $_.Status -eq 'SKIP' }).Count
Write-Host ("PASS={0}  FAIL={1}  SKIP={2}" -f $passed, $failed, $skipped)
if ($failed -gt 0 -or -not $allPassed) {
    Write-Host 'result: FAIL' -ForegroundColor Red
    exit 1
}
Write-Host 'result: PASS' -ForegroundColor Green
exit 0
