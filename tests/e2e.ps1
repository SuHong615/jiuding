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
    End-to-end test: login -> create tenant -> submit -> approve -> MCP tools/call -> CLI query.

.DESCRIPTION
    Runs against a running gateway. Default base URL is http://localhost:18080 because
    port 8080 is occupied by another local app on the development box (docker mode uses 8080).

    The test prints one PASS/FAIL/SKIP line per assertion plus a summary, and exits with 1
    when any assertion fails. If the gateway is unreachable it reports everything as SKIP
    and exits 0 (services simply are not running - this is not a test failure).

.EXAMPLE
    pwsh ./tests/e2e.ps1
    pwsh ./tests/e2e.ps1 -BaseUrl http://localhost:8080 -Verbose
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$AdminUser = 'admin',
    [string]$AdminPassword = 'Admin@123',
    [string]$ApproverUser = 'approver',
    [string]$ApproverPassword = 'Approver@123',
    [string]$McpApiKey = 'dev-mcp-key',
    [int]$TimeoutSeconds = 120,
    [int]$PollIntervalSeconds = 3
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'scripts\env.ps1')

$script:Results = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$Detail = ''
    )
    $script:Results.Add([pscustomobject]@{ Check = $Name; Status = $Status; Detail = $Detail })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } 'SKIP' { 'Yellow' } default { 'Gray' } }
    Write-Host ("[{0}] {1}{2}" -f $Status, $Name, $(if ($Detail) { " - $Detail" } else { '' })) -ForegroundColor $color
}

function Show-Summary {
    Write-Host ''
    Write-Host '---------------- e2e summary ----------------'
    $script:Results | Format-Table -AutoSize | Out-String | Write-Host
    $passed = @($script:Results | Where-Object { $_.Status -eq 'PASS' }).Count
    $failed = @($script:Results | Where-Object { $_.Status -eq 'FAIL' }).Count
    $skipped = @($script:Results | Where-Object { $_.Status -eq 'SKIP' }).Count
    Write-Host ("PASS={0}  FAIL={1}  SKIP={2}" -f $passed, $failed, $skipped)
    if ($failed -gt 0) {
        Write-Host 'result: FAIL' -ForegroundColor Red
        exit 1
    }
    Write-Host 'result: PASS' -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------- HTTP helpers
function Invoke-TenancyHttp {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 15
    )
    $requestHeaders = @{}
    foreach ($key in $Headers.Keys) { $requestHeaders[$key] = [string]$Headers[$key] }
    $params = @{
        Uri                = $Uri
        Method             = $Method
        Headers            = $requestHeaders
        TimeoutSec         = $TimeoutSec
        UseBasicParsing    = $true
        ErrorAction        = 'Stop'
        MaximumRedirection = 3
    }
    if ($null -ne $Body) {
        if ($Body -is [string]) { $params['Body'] = $Body }
        else { $params['Body'] = ($Body | ConvertTo-Json -Depth 12 -Compress) }
        $params['ContentType'] = 'application/json; charset=utf-8'
    }
    try {
        $response = Invoke-WebRequest @params
        $content = $response.Content
        if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
        else { $content = [string]$content }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content    = $content
            Headers    = $response.Headers
        }
    }
    catch {
        $exception = $_.Exception
        $response = $exception.Response
        if ($response) {
            $content = ''
            try {
                if ($response.PSObject.Properties.Name -contains 'Content') {
                    $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                else {
                    $stream = $response.GetResponseStream()
                    $reader = New-Object System.IO.StreamReader($stream)
                    $content = $reader.ReadToEnd()
                    $reader.Close()
                }
            }
            catch { $content = '' }
            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Content    = $content
                Headers    = $null
            }
        }
        throw
    }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 15
    )
    $response = Invoke-TenancyHttp -Uri ($BaseUrl.TrimEnd('/') + $Path) -Method $Method `
        -Headers $Headers -Body $Body -TimeoutSec $TimeoutSec
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
        throw ("HTTP {0} on {1}: {2}" -f $response.StatusCode, $Path, $response.Content)
    }
    $envelope = $response.Content | ConvertFrom-Json
    if ($null -ne $envelope.code -and [int]$envelope.code -ne 0) {
        throw ("API error {0}: {1}" -f $envelope.code, $envelope.message)
    }
    return $envelope.data
}

# ---------------------------------------------------------------- preflight
Write-Host ("==> e2e against {0}" -f $BaseUrl) -ForegroundColor Cyan
$gatewayUp = $false
try {
    $health = Invoke-TenancyHttp -Uri ($BaseUrl.TrimEnd('/') + '/actuator/health') -TimeoutSec 5
    $gatewayUp = ($health.StatusCode -ge 200 -and $health.StatusCode -lt 300)
}
catch {
    $gatewayUp = $false
}
if (-not $gatewayUp) {
    Add-Check 'preflight gateway' 'SKIP' ("not reachable at {0}; start it with scripts/run-local.ps1" -f $BaseUrl)
    Add-Check 'login' 'SKIP' 'gateway down'
    Add-Check 'tenant create' 'SKIP' 'gateway down'
    Add-Check 'tenant submit' 'SKIP' 'gateway down'
    Add-Check 'approval decision' 'SKIP' 'gateway down'
    Add-Check 'tenant active' 'SKIP' 'gateway down'
    Add-Check 'mcp tools/call' 'SKIP' 'gateway down'
    Add-Check 'cli query' 'SKIP' 'gateway down'
    Show-Summary
}
Add-Check 'preflight gateway' 'PASS' $BaseUrl

# ---------------------------------------------------------------- 1. login
$adminToken = $null
try {
    $login = Invoke-Api -Path '/api/auth/login' -Method 'POST' -Headers @{ 'X-Forwarded-For' = ('198.51.100.' + (Get-Random -Minimum 10 -Maximum 250)) } -Body @{ username = $AdminUser; password = $AdminPassword }
    $adminToken = [string]$login.token
    if ([string]::IsNullOrWhiteSpace($adminToken)) { throw 'login response has no token' }
    $principal = [string]$login.principal.username
    Add-Check 'login' 'PASS' ("user={0} roles={1}" -f $principal, ($login.principal.roles -join ','))
}
catch {
    Add-Check 'login' 'FAIL' $_.Exception.Message
    Add-Check 'tenant create' 'SKIP' 'login failed'
    Add-Check 'tenant submit' 'SKIP' 'login failed'
    Add-Check 'approval decision' 'SKIP' 'login failed'
    Add-Check 'tenant active' 'SKIP' 'login failed'
    Add-Check 'mcp tools/call' 'SKIP' 'login failed'
    Add-Check 'cli query' 'SKIP' 'login failed'
    Show-Summary
}
$authHeaders = @{ Authorization = "Bearer $adminToken" }

# ---------------------------------------------------------------- 2. create tenant
$tenantId = $null
$tenant = $null
$lastCreateError = $null
for ($attempt = 1; $attempt -le 3; $attempt++) {
    $tenantCode = 'e2e-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' + (Get-Random -Minimum 1000 -Maximum 9999)
    try {
        $tenant = Invoke-Api -Path '/api/tenants' -Method 'POST' -Headers $authHeaders -Body @{
            tenantCode   = $tenantCode
            name         = 'E2E Enterprise Tenant'
            contactName  = 'E2E Bot'
            contactEmail = 'e2e@example.com'
            plan         = 'ENTERPRISE'
            region       = 'cn-shanghai'
            quotaSeats   = 10
            source       = 'API'
            autoSubmit   = $false
        }
        $tenantId = [string]$tenant.id
        if ([string]::IsNullOrWhiteSpace($tenantId)) { throw 'create response has no id' }
        if ([string]$tenant.status -ne 'DRAFT') { throw ("expected DRAFT but got {0}" -f $tenant.status) }
        $lastCreateError = $null
        break
    }
    catch {
        $lastCreateError = $_
        if ($attempt -lt 3 -and $_.Exception.Message -match 'HTTP 50[234]') {
            Write-Host '[retry] tenant create hit an upstream restart; retrying with a fresh code...' -ForegroundColor DarkYellow
            Start-Sleep -Seconds 3
        }
        else { break }
    }
}
if (-not $tenantId) {
    Add-Check 'tenant create' 'FAIL' $lastCreateError.Exception.Message
    Add-Check 'tenant submit' 'SKIP' 'tenant create failed'
    Add-Check 'approval decision' 'SKIP' 'tenant create failed'
    Add-Check 'tenant active' 'SKIP' 'tenant create failed'
    Add-Check 'mcp tools/call' 'SKIP' 'tenant create failed'
    Add-Check 'cli query' 'SKIP' 'tenant create failed'
    Show-Summary
}
Add-Check 'tenant create' 'PASS' ("id={0} code={1} status={2}" -f $tenantId, $tenantCode, $tenant.status)

# ---------------------------------------------------------------- 3. submit
$tenantStatus = ''
$submitError = $null
$allowed = @('SUBMITTED', 'RISK_REVIEW', 'PENDING_APPROVAL', 'APPROVED', 'PROVISIONING', 'ACTIVE')
for ($attempt = 1; $attempt -le 3; $attempt++) {
    try {
        $submitted = Invoke-Api -Path ("/api/tenants/{0}/submit" -f $tenantId) -Method 'POST' -Headers $authHeaders -Body @{}
        $tenantStatus = [string]$submitted.status
        $submitError = $null
        break
    }
    catch {
        $submitError = $_
        if ($attempt -lt 3 -and $_.Exception.Message -match 'HTTP 50[234]') {
            Write-Host '[retry] submit hit an upstream restart; retrying in 3s...' -ForegroundColor DarkYellow
            Start-Sleep -Seconds 3
        }
        else { break }
    }
}
if (-not $tenantStatus) {
    # The service may have processed the submit but the response was lost (restart).
    try {
        $currentTenant = Invoke-Api -Path ("/api/tenants/{0}" -f $tenantId) -Headers $authHeaders
        $tenantStatus = [string]$currentTenant.status
    }
    catch { $tenantStatus = '' }
}
if ($allowed -contains $tenantStatus) {
    Add-Check 'tenant submit' 'PASS' ("status={0}" -f $tenantStatus)
}
else {
    $submitDetail = if ($submitError) { $submitError.Exception.Message } else { "unexpected status after submit: $tenantStatus" }
    Add-Check 'tenant submit' 'FAIL' $submitDetail
    Add-Check 'approval decision' 'SKIP' 'submit failed'
    Add-Check 'tenant active' 'SKIP' 'submit failed'
    Add-Check 'mcp tools/call' 'SKIP' 'submit failed'
    Add-Check 'cli query' 'SKIP' 'submit failed'
    Show-Summary
}

# ---------------------------------------------------------------- 4. approval decision
$needsDecision = ($tenantStatus -eq 'PENDING_APPROVAL')
if (-not $needsDecision -and $tenantStatus -in @('APPROVED', 'PROVISIONING', 'ACTIVE')) {
    Add-Check 'approval decision' 'SKIP' ("auto-decided (status={0})" -f $tenantStatus)
}
elseif (-not $needsDecision -and $tenantStatus -eq 'SUBMITTED') {
    Add-Check 'approval decision' 'SKIP' 'approval-service unavailable; tenant degraded to SUBMITTED'
}
else {
    $approvalId = $null
    try {
        $approvals = Invoke-Api -Path '/api/approvals/pending' -Headers $authHeaders
        foreach ($item in @($approvals)) {
            if ([string]$item.tenantId -eq $tenantId) { $approvalId = [string]$item.id; break }
        }
        if (-not $approvalId) {
            $page = Invoke-Api -Path ('/api/approvals?page=0&size=100') -Headers $authHeaders
            foreach ($item in @($page.items)) {
                if ([string]$item.tenantId -eq $tenantId) { $approvalId = [string]$item.id; break }
            }
        }
        if (-not $approvalId) { throw ("no approval found for tenant {0}" -f $tenantId) }
        $approverLogin = Invoke-Api -Path '/api/auth/login' -Method 'POST' -Body @{ username = $ApproverUser; password = $ApproverPassword }
        $approverToken = [string]$approverLogin.token
        if ([string]::IsNullOrWhiteSpace($approverToken)) { throw 'approver login response has no token' }
        $approverHeaders = @{ Authorization = "Bearer $approverToken" }
        $decided = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method 'POST' `
            -Headers $approverHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e automated approval' }
        Add-Check 'approval decision' 'PASS' ("approvalId={0} state={1}" -f $approvalId, $decided.state)
    }
    catch {
        Add-Check 'approval decision' 'FAIL' $_.Exception.Message
    }
}

# ---------------------------------------------------------------- 5. tenant becomes ACTIVE
$finalStatus = ''
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    try {
        $current = Invoke-Api -Path ("/api/tenants/{0}" -f $tenantId) -Headers $authHeaders
        $finalStatus = [string]$current.status
        if ($finalStatus -eq 'ACTIVE') { break }
    }
    catch {
        $finalStatus = 'ERROR:' + $_.Exception.Message
    }
    Start-Sleep -Seconds $PollIntervalSeconds
}
if ($finalStatus -eq 'ACTIVE') {
    Add-Check 'tenant active' 'PASS' ("status=ACTIVE tenantId={0}" -f $tenantId)
}
else {
    Add-Check 'tenant active' 'FAIL' ("last status={0} after {1}s" -f $finalStatus, $TimeoutSeconds)
}

# ---------------------------------------------------------------- 6. MCP tools/call
try {
    $rpcRequest = @{
        jsonrpc = '2.0'
        id      = 1
        method  = 'tools/call'
        params  = @{ name = 'tenant.list'; arguments = @{ keyword = $tenantCode; page = 0; size = 10 } }
    } | ConvertTo-Json -Depth 10 -Compress
    $rpcResponse = Invoke-TenancyHttp -Uri ($BaseUrl.TrimEnd('/') + '/mcp') -Method 'POST' `
        -Headers @{ 'X-API-Key' = $McpApiKey } -Body $rpcRequest
    if ($rpcResponse.StatusCode -ge 400) { throw ("HTTP {0}: {1}" -f $rpcResponse.StatusCode, $rpcResponse.Content) }
    $rpc = $rpcResponse.Content | ConvertFrom-Json
    if ($null -ne $rpc.error) { throw ("JSON-RPC error {0}: {1}" -f $rpc.error.code, $rpc.error.message) }
    if ($null -eq $rpc.result) { throw 'MCP response has no result' }
    if ($rpc.result.isError -eq $true) {
        throw ("tool isError: {0}" -f ($rpc.result.structuredContent | ConvertTo-Json -Compress))
    }
    $toolPayload = $rpc.result.structuredContent
    if ($null -eq $toolPayload -and $rpc.result.content -and $rpc.result.content.Count -gt 0) {
        $toolPayload = $rpc.result.content[0].text | ConvertFrom-Json
    }
    $mcpText = ($toolPayload | ConvertTo-Json -Depth 10 -Compress)
    if ($mcpText -notmatch [regex]::Escape($tenantCode)) {
        throw ("tenant {0} not found in tenant.list result" -f $tenantCode)
    }
    Add-Check 'mcp tools/call' 'PASS' "tenant.list contains $tenantCode"
}
catch {
    Add-Check 'mcp tools/call' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- 7. CLI query
$cliJar = Get-TenancyCliJar
$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
if (-not $cliJar) {
    Add-Check 'cli query' 'SKIP' 'tenancy-cli jar not built (scripts/build.ps1 -Module tenancy-cli)'
}
else {
    try {
        $cliOutput = & $javaExe -jar $cliJar --base-url $BaseUrl --token $adminToken `
            --output json tenant list --keyword $tenantCode 2>&1 | Out-String
        $cliExit = $LASTEXITCODE
        if ($cliExit -ne 0) { throw ("tenancy CLI exited {0}: {1}" -f $cliExit, $cliOutput.Trim()) }
        if ($cliOutput -notmatch [regex]::Escape($tenantCode)) {
            throw ("tenant {0} not present in CLI output" -f $tenantCode)
        }
        Add-Check 'cli query' 'PASS' ("tenancy tenant list --keyword {0}" -f $tenantCode)
    }
    catch {
        Add-Check 'cli query' 'FAIL' $_.Exception.Message
    }
}

Show-Summary
