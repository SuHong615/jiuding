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
    Stop every process started by scripts/run-local.ps1.

.DESCRIPTION
    Reads <repo>/.run/*.pid and kills the whole process tree (cmd.exe wrapper ->
    java/node child) with taskkill /T /F. Idempotent: stale PID files are removed.
    Logs are kept unless -PurgeLogs is passed.

.EXAMPLE
    pwsh ./scripts/stop-local.ps1
    pwsh ./scripts/stop-local.ps1 -PurgeLogs
#>
[CmdletBinding()]
param(
    [switch]$PurgeLogs
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$root = $global:TenancyRoot
$runDir = Join-Path $root '.run'
$logDir = Join-Path $runDir 'logs'

if (-not (Test-Path $runDir)) {
    Write-Host 'Nothing to stop: .run/ does not exist.' -ForegroundColor Yellow
    exit 0
}

$results = New-Object System.Collections.Generic.List[object]
$pidFiles = @(Get-ChildItem $runDir -Filter '*.pid' -File -ErrorAction SilentlyContinue)

foreach ($pidFile in $pidFiles) {
    $name = $pidFile.BaseName
    $processId = $null
    try { $processId = [int](Get-Content $pidFile.FullName -Raw).Trim() } catch { $processId = $null }

    if ($processId -and (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
        Write-Host ("[STOP] {0} (pid {1})" -f $name, $processId) -ForegroundColor Cyan
        & taskkill.exe /PID $processId /T /F 2>&1 | Out-Null
        Start-Sleep -Milliseconds 400
        $stillRunning = [bool](Get-Process -Id $processId -ErrorAction SilentlyContinue)
        if ($stillRunning) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
        $results.Add([pscustomobject]@{ Name = $name; Pid = $processId; Status = 'STOPPED' })
    }
    else {
        $results.Add([pscustomobject]@{ Name = $name; Pid = $processId; Status = 'NOT_RUNNING' })
        Write-Host ("[SKIP] {0}: process {1} is not running (stale pid file)" -f $name, $processId) -ForegroundColor Yellow
    }
    Remove-Item $pidFile.FullName -Force -ErrorAction SilentlyContinue
}

if ($PurgeLogs -and (Test-Path $logDir)) {
    Remove-Item $logDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host ("[CLEAN] removed {0}" -f $logDir) -ForegroundColor DarkGray
}

Write-Host ''
if ($results.Count -eq 0) {
    Write-Host 'No PID files found.' -ForegroundColor Yellow
}
else {
    $results | Format-Table -AutoSize | Out-String | Write-Host
}
Write-Host 'result: PASS' -ForegroundColor Green
exit 0
