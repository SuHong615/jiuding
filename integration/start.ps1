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
# Build and start every service as a local process (no Docker required).
# Logs: integration/.run/<service>.log ; PIDs: integration/.run/<service>.pid
#
# NOTE (this machine): ports 8080 and 5173 are already used by another local
# application, so the local gateway runs on 18080 and the frontend dev server on
# 15173. Docker deployments keep the documented 8080/8088 mapping.
param(
    [switch]$SkipBuild,
    [int]$HealthTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $PSScriptRoot '.run'
New-Item -ItemType Directory -Force -Path $run | Out-Null

# This host ships duplicated proxy variables in both cases (HTTP_PROXY/http_proxy),
# which makes Start-Process throw while building its environment block. Drop the
# lowercase duplicates for this process tree only.
foreach ($name in 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy', 'ftp_proxy') {
    Remove-Item "Env:$name" -ErrorAction SilentlyContinue
}

$mvn = 'D:\deepseekHarness\env\apache-maven-3.9.9\bin\mvn.cmd'
$env:Path = 'D:\deepseekHarness\env\apache-maven-3.9.9\bin;' + $env:Path

# Shared security configuration must be identical across services.
$env:TENANCY_SECURITY_JWT_SECRET = 'tenancy-center-dev-secret-change-me-please'
$env:TENANCY_SECURITY_API_KEYS = 'dev-mcp-key'
$env:TENANCY_INTERNAL_TOKEN = 'dev-internal-token'
$env:TENANT_SERVICE_URL = 'http://localhost:8081'
$env:APPROVAL_SERVICE_URL = 'http://localhost:8082'
$env:AGENT_SERVICE_URL = 'http://localhost:8083'
$env:MCP_SERVICE_URL = 'http://localhost:8084'
$env:TENANCY_SKILLS_DIR = (Join-Path $root 'skills')

$services = @(
    @{ name = 'tenant-service';   module = 'tenant-service';   port = 8081 },
    @{ name = 'approval-service'; module = 'approval-service'; port = 8082 },
    @{ name = 'agent-service';    module = 'agent-service';    port = 8083 },
    @{ name = 'mcp-service';      module = 'mcp-service';      port = 8084 },
    @{ name = 'gateway';          module = 'gateway';          port = 18080 }
)

function Write-Step($message) { Write-Host "[start] $message" -ForegroundColor Cyan }

if (-not $SkipBuild) {
    Write-Step 'building backend modules (skip tests)...'
    & $mvn -B -q -f (Join-Path $root 'pom.xml') -DskipTests install
    if ($LASTEXITCODE -ne 0) { throw 'maven build failed' }
}

foreach ($svc in $services) {
    $existingPid = Join-Path $run "$($svc.name).pid"
    if (Test-Path $existingPid) {
        $old = Get-Content $existingPid -ErrorAction SilentlyContinue
        if ($old -and (Get-Process -Id $old -ErrorAction SilentlyContinue)) {
            Write-Step "$($svc.name) already running (pid $old), skipping"
            continue
        }
    }
    $moduleDir = Join-Path $root $svc.module
    $jar = Get-ChildItem -Path (Join-Path $moduleDir 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc|original' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        Write-Step "no jar for $($svc.name); packaging module"
        & $mvn -B -q -f (Join-Path $moduleDir 'pom.xml') -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "packaging $($svc.name) failed" }
        $jar = Get-ChildItem -Path (Join-Path $moduleDir 'target') -Filter '*.jar' |
            Where-Object { $_.Name -notmatch 'sources|javadoc|original' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    if (-not $jar) { throw "jar not found for $($svc.name)" }

    $log = Join-Path $run "$($svc.name).log"
    $err = Join-Path $run "$($svc.name).err.log"
    Write-Step "starting $($svc.name) on :$($svc.port) -> $($jar.Name)"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'cmd.exe'
    $psi.Arguments = "/c java -jar `"$($jar.FullName)`" --spring.profiles.active=dev --server.port=$($svc.port) > `"$log`" 2> `"$err`""
    $psi.WorkingDirectory = $moduleDir
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($psi)
    Set-Content -Path $existingPid -Value $process.Id
    Start-Sleep -Milliseconds 500
}

Write-Step "waiting for health endpoints (timeout ${HealthTimeoutSeconds}s)..."
$deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
foreach ($svc in $services) {
    $url = "http://localhost:$($svc.port)/actuator/health"
    $up = $false
    while ((Get-Date) -lt $deadline) {
        $pidFile = Join-Path $run "$($svc.name).pid"
        $svcPid = Get-Content $pidFile -ErrorAction SilentlyContinue
        if (-not (Get-Process -Id $svcPid -ErrorAction SilentlyContinue)) { break }
        try {
            $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -eq 200) { $up = $true; break }
        } catch { Start-Sleep -Milliseconds 800 }
    }
    if ($up) {
        Write-Host ("  {0,-18} UP   http://localhost:{1}" -f $svc.name, $svc.port) -ForegroundColor Green
    } else {
        Write-Host ("  {0,-18} DOWN (see integration/.run/{0}.log)" -f $svc.name) -ForegroundColor Red
    }
}
