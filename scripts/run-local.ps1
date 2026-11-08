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
    Start the services locally (no Docker) in dependency order and wait for readiness.

.DESCRIPTION
    Ports (local process mode):
        gateway          18080   (8080 is taken by another local app -> use --server.port)
        tenant-service   8081
        approval-service 8082
        agent-service    8083
        mcp-service      8084
        frontend (vite)  15173   (5173 is taken -> --port, TENANCY_GATEWAY_URL points to 18080)

    PID files and logs are written under <repo>/.run/ (one .pid + .log/.err per process).
    Tolerant by design: a service that is merely not ready yet is reported as
    NOT_READY/STARTED and the script keeps going; missing modules are SKIPped.

.EXAMPLE
    pwsh ./scripts/run-local.ps1
    pwsh ./scripts/run-local.ps1 -SkipBuild -SkipFrontend
    pwsh ./scripts/run-local.ps1 -Force -ReadyTimeoutSeconds 120
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$InstallFrontend,
    [switch]$Force,
    [int]$ReadyTimeoutSeconds = 90,
    [int]$GatewayPort = 18080,
    [int]$FrontendPort = 15173
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$root = $global:TenancyRoot
$runDir = Join-Path $root '.run'
$logDir = Join-Path $runDir 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    $javaCmd = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if (-not $javaCmd) { throw 'java.exe not found; set JAVA_HOME.' }
    $javaExe = $javaCmd.Source
}
Add-TenancyPathEntry (Split-Path -Parent $javaExe)
$javaCommand = 'java.exe'

# ---------------------------------------------------------------- process helpers
function Start-ManagedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [string]$StdOut,
        [string]$StdErr
    )
    # NOTE: deliberately NOT using Start-Process. This box has duplicate
    # HTTP_PROXY/http_proxy style variables that make Start-Process throw
    # ("An item with the same key has already been added").
    # Quote-free executable tokens avoid cmd.exe's "first token quote" stripping rules;
    # if a full path with spaces is passed, wrap the whole command with /s /c ""..."".
    $exeToken = $FileName
    if ($exeToken -match '\s') { $exeToken = '"{0}"' -f $exeToken }
    $command = ('{0} {1} > "{2}" 2> "{3}"' -f $exeToken, $Arguments, $StdOut, $StdErr)
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = 'cmd.exe'
    if ($command.StartsWith('"')) {
        $info.Arguments = '/s /c "' + $command + '"'
    }
    else {
        $info.Arguments = '/c ' + $command
    }
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    return [System.Diagnostics.Process]::Start($info)
}

function Get-ManagedPid {
    param([string]$Name)
    $pidFile = Join-Path $runDir "$Name.pid"
    if (-not (Test-Path $pidFile)) { return $null }
    try { return [int](Get-Content $pidFile -Raw).Trim() } catch { return $null }
}

function Test-ProcessAlive {
    param([int]$ProcessId)
    if (-not $ProcessId) { return $false }
    return [bool](Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Stop-ManagedProcess {
    param([int]$ProcessId)
    if (-not $ProcessId) { return }
    & taskkill.exe /PID $ProcessId /T /F 2>&1 | Out-Null
}

# ---------------------------------------------------------------- service definitions
$services = @(
    [pscustomobject]@{ Name = 'tenant-service';   Module = 'tenant-service';   Port = 8081;             Args = @() },
    [pscustomobject]@{ Name = 'approval-service'; Module = 'approval-service'; Port = 8082;             Args = @() },
    [pscustomobject]@{ Name = 'agent-service';    Module = 'agent-service';    Port = 8083;             Args = @() },
    [pscustomobject]@{ Name = 'mcp-service';      Module = 'mcp-service';      Port = 8084;             Args = @() },
    [pscustomobject]@{ Name = 'gateway';          Module = 'gateway';          Port = $GatewayPort;     Args = @("--server.port=$GatewayPort") }
)

# Cross-service environment (process-wide, inherited by the started children).
$env:TENANT_SERVICE_URL = 'http://localhost:8081'
$env:APPROVAL_SERVICE_URL = 'http://localhost:8082'
$env:AGENT_SERVICE_URL = 'http://localhost:8083'
$env:MCP_SERVICE_URL = 'http://localhost:8084'
$env:TENANCY_TENANT_URL = $env:TENANT_SERVICE_URL
$env:TENANCY_APPROVAL_URL = $env:APPROVAL_SERVICE_URL
$env:TENANCY_AGENT_URL = $env:AGENT_SERVICE_URL
$env:TENANCY_SECURITY_JWT_SECRET = if ($env:TENANCY_SECURITY_JWT_SECRET) { $env:TENANCY_SECURITY_JWT_SECRET } else { 'tenancy-center-dev-secret-change-me-please' }
$env:TENANCY_SECURITY_INTERNAL_TOKEN = if ($env:TENANCY_SECURITY_INTERNAL_TOKEN) { $env:TENANCY_SECURITY_INTERNAL_TOKEN } else { 'dev-internal-token' }
$env:TENANCY_SECURITY_API_KEYS = if ($env:TENANCY_SECURITY_API_KEYS) { $env:TENANCY_SECURITY_API_KEYS } else { 'dev-mcp-key' }
$env:TENANCY_SKILLS_DIR = (Join-Path $root 'skills')
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:TENANCY_GATEWAY_URL = "http://localhost:$GatewayPort"
$env:VITE_GATEWAY_URL = $env:TENANCY_GATEWAY_URL

# ---------------------------------------------------------------- build missing jars
$neededBuild = @()
foreach ($service in $services) {
    if (-not (Get-TenancyModuleJar -Module $service.Module)) { $neededBuild += $service.Module }
}
if ($neededBuild.Count -gt 0) {
    if ($SkipBuild) {
        Write-Warning ("Missing jar(s) for: {0}; -SkipBuild set, those services will be skipped." -f ($neededBuild -join ', '))
    }
    else {
        Write-Host ("==> building missing modules: {0}" -f ($neededBuild -join ', ')) -ForegroundColor Cyan
        $buildList = New-Object System.Collections.Generic.List[string]
        $commonJar = Join-Path $global:TenancyM2Repo 'com\suhong\jiuding\tenancy-common\0.9.0-SNAPSHOT\tenancy-common-0.9.0-SNAPSHOT.jar'
        $kernelJar = Join-Path $global:TenancyM2Repo 'com\suhong\jiuding\agent-kernel\0.9.0-SNAPSHOT\agent-kernel-0.9.0-SNAPSHOT.jar'
        if (-not (Test-Path $kernelJar)) { $buildList.Add('agent-kernel') }
        if (-not (Test-Path $commonJar)) { $buildList.Add('tenancy-common') }
        foreach ($name in $neededBuild) { if ($buildList -notcontains $name) { $buildList.Add($name) } }
        & (Join-Path $PSScriptRoot 'build.ps1') -Module @($buildList) -SkipTests -KeepGoing
        if ($LASTEXITCODE -ne 0) {
            Write-Warning 'Some modules failed to build; continuing with whatever is available.'
        }
    }
}

# ---------------------------------------------------------------- idempotent start
$started = New-Object System.Collections.Generic.List[object]

foreach ($service in $services) {
    $pidFile = Join-Path $runDir "$($service.Name).pid"
    $existingPid = Get-ManagedPid -Name $service.Name
    if ((Test-ProcessAlive -ProcessId $existingPid) -and -not $Force) {
        $started.Add([pscustomobject]@{ Name = $service.Name; Port = $service.Port; Pid = $existingPid; Status = 'ALREADY_RUNNING' })
        Write-Host ("[SKIP] {0} already running (pid {1})" -f $service.Name, $existingPid) -ForegroundColor Yellow
        continue
    }
    if ($Force -and $existingPid) {
        Write-Host ("[STOP] {0} (pid {1})" -f $service.Name, $existingPid) -ForegroundColor Yellow
        Stop-ManagedProcess -ProcessId $existingPid
        Remove-Item $pidFile -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 800
    }
    if (Test-TenancyTcpPort -Port $service.Port) {
        $started.Add([pscustomobject]@{ Name = $service.Name; Port = $service.Port; Pid = $null; Status = 'PORT_IN_USE' })
        Write-Host ("[SKIP] {0}: port {1} is already in use by another process" -f $service.Name, $service.Port) -ForegroundColor Yellow
        continue
    }

    $jar = Get-TenancyModuleJar -Module $service.Module
    if (-not $jar) {
        $started.Add([pscustomobject]@{ Name = $service.Name; Port = $service.Port; Pid = $null; Status = 'NO_JAR' })
        Write-Host ("[SKIP] {0}: no jar (build it first: scripts/build.ps1 -Module {1})" -f $service.Name, $service.Module) -ForegroundColor Yellow
        continue
    }

    $stdout = Join-Path $logDir "$($service.Name).log"
    $stderr = Join-Path $logDir "$($service.Name).err.log"
    $jarArgs = @('-Dfile.encoding=UTF-8', '-jar', ('"{0}"' -f $jar), '--spring.profiles.active=dev')
    if ($service.Args.Count -gt 0) { $jarArgs += $service.Args }
    $argumentLine = $jarArgs -join ' '
    try {
        $process = Start-ManagedProcess -FileName $javaCommand -Arguments $argumentLine `
            -WorkingDirectory (Join-Path $root $service.Module) -StdOut $stdout -StdErr $stderr
        Set-Content -Path $pidFile -Value $process.Id -Encoding ASCII
        $started.Add([pscustomobject]@{ Name = $service.Name; Port = $service.Port; Pid = $process.Id; Status = 'STARTING' })
        Write-Host ("[START] {0} pid={1} port={2} log={3}" -f $service.Name, $process.Id, $service.Port, $stdout) -ForegroundColor Cyan
    }
    catch {
        $started.Add([pscustomobject]@{ Name = $service.Name; Port = $service.Port; Pid = $null; Status = 'LAUNCH_FAILED' })
        Write-Host ("[FAIL] {0}: {1}" -f $service.Name, $_.Exception.Message) -ForegroundColor Red
    }
}

# ---------------------------------------------------------------- readiness
foreach ($entry in $started) {
    if ($entry.Status -notin @('STARTING', 'ALREADY_RUNNING')) { continue }
    $url = "http://127.0.0.1:$($entry.Port)/actuator/health"
    $health = Wait-TenancyHttpHealth -Url $url -TimeoutSeconds $ReadyTimeoutSeconds
    $entry.Status = if ($health -eq 'UP') { 'READY' } elseif (Test-TenancyTcpPort -Port $entry.Port) { 'STARTED' } else { 'NOT_READY' }
    $color = switch ($entry.Status) { 'READY' { 'Green' } 'STARTED' { 'Yellow' } default { 'Red' } }
    Write-Host ("[{0}] {1}:{2} (pid {3})" -f $entry.Status, $entry.Name, $entry.Port, $entry.Pid) -ForegroundColor $color
}

# ---------------------------------------------------------------- frontend
if (-not $SkipFrontend) {
    $frontendDir = Join-Path $root 'frontend'
    $viteBin = Join-Path $frontendDir 'node_modules\vite\bin\vite.js'
    if (-not (Test-Path (Join-Path $frontendDir 'package.json'))) {
        Write-Host '[SKIP] frontend: no frontend/package.json' -ForegroundColor Yellow
    }
    elseif (-not (Test-Path $viteBin)) {
        if ($InstallFrontend -and $global:TenancyNpm) {
            Write-Host '==> npm install (frontend)' -ForegroundColor Cyan
            Push-Location $frontendDir
            try { & $global:TenancyNpm install --no-audit --no-fund } finally { Pop-Location }
        }
        else {
            Write-Host '[SKIP] frontend: node_modules/vite missing (run with -InstallFrontend or npm install in frontend/)' -ForegroundColor Yellow
        }
    }
    else {
        $existingPid = Get-ManagedPid -Name 'frontend'
        if ((Test-ProcessAlive -ProcessId $existingPid) -and -not $Force) {
            Write-Host ("[SKIP] frontend already running (pid {0})" -f $existingPid) -ForegroundColor Yellow
            $started.Add([pscustomobject]@{ Name = 'frontend'; Port = $FrontendPort; Pid = $existingPid; Status = 'ALREADY_RUNNING' })
        }
        elseif ($global:TenancyNode) {
            Add-TenancyPathEntry (Split-Path -Parent $global:TenancyNode)
            $stdout = Join-Path $logDir 'frontend.log'
            $stderr = Join-Path $logDir 'frontend.err.log'
            $nodeArgs = ('"{0}" --host 127.0.0.1 --port {1} --strictPort' -f $viteBin, $FrontendPort)
            try {
                $process = Start-ManagedProcess -FileName 'node.exe' -Arguments $nodeArgs `
                    -WorkingDirectory $frontendDir -StdOut $stdout -StdErr $stderr
                Set-Content -Path (Join-Path $runDir 'frontend.pid') -Value $process.Id -Encoding ASCII
                Start-Sleep -Seconds 2
                if (Test-TenancyTcpPort -Port $FrontendPort) {
                    $started.Add([pscustomobject]@{ Name = 'frontend'; Port = $FrontendPort; Pid = $process.Id; Status = 'READY' })
                    Write-Host ("[READY] frontend http://localhost:{0} (gateway {1})" -f $FrontendPort, $env:TENANCY_GATEWAY_URL) -ForegroundColor Green
                }
                else {
                    $started.Add([pscustomobject]@{ Name = 'frontend'; Port = $FrontendPort; Pid = $process.Id; Status = 'NOT_READY' })
                    Write-Host ("[NOT_READY] frontend pid={0} log={1}" -f $process.Id, $stderr) -ForegroundColor Yellow
                }
            }
            catch {
                Write-Host ("[FAIL] frontend: {0}" -f $_.Exception.Message) -ForegroundColor Red
            }
        }
        else {
            Write-Host '[SKIP] frontend: node not found' -ForegroundColor Yellow
        }
    }
}

# ---------------------------------------------------------------- summary
Write-Host ''
Write-Host '---------------- local services ----------------'
$started | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($started | Where-Object { $_.Status -in @('LAUNCH_FAILED', 'NO_JAR') }).Count
$ready = @($started | Where-Object { $_.Status -in @('READY', 'STARTED', 'ALREADY_RUNNING') }).Count
Write-Host ("ready={0}  failed={1}  run dir={2}" -f $ready, $failed, $runDir)
if ($failed -gt 0) {
    Write-Host 'result: PARTIAL (see warnings above)' -ForegroundColor Yellow
    exit 1
}
Write-Host ("Gateway: http://localhost:{0}    Frontend: http://localhost:{1}" -f $GatewayPort, $FrontendPort) -ForegroundColor Green
Write-Host 'Next: pwsh ./scripts/verify.ps1' -ForegroundColor Green
exit 0
