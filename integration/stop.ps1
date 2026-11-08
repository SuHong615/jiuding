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
$ErrorActionPreference = 'SilentlyContinue'
$run = Join-Path $PSScriptRoot '.run'
if (-not (Test-Path $run)) { Write-Host 'nothing to stop'; exit 0 }
Get-ChildItem $run -Filter '*.pid' | ForEach-Object {
    $svc = $_.BaseName
    $svcPid = Get-Content $_.FullName
    $proc = Get-Process -Id $svcPid -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "stopping $svc (pid $svcPid)"
        # The wrapper is cmd.exe -> java, so kill the whole tree to release jar locks.
        & taskkill /PID $svcPid /T /F | Out-Null
    }
    Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
}
Write-Host 'all services stopped'
