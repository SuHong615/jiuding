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
# End-to-end verification against a locally running platform (no Docker required).
# Usage: pwsh integration/verify.ps1 [-BaseUrl http://localhost:8080]
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$TenantUrl = 'http://localhost:8081',
    [string]$ApprovalUrl = 'http://localhost:8082',
    [string]$AgentUrl = 'http://localhost:8083',
    [string]$McpUrl = 'http://localhost:8084',
    [int]$PollSeconds = 30
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$results = [System.Collections.Generic.List[object]]::new()
$script:createdTenant = $null
$script:approvalId = $null

function Add-Result([string]$name, [bool]$ok, [string]$detail) {
    $results.Add([pscustomobject]@{ Step = $name; Result = if ($ok) { 'PASS' } else { 'FAIL' }; Detail = $detail })
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1} - {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail) -ForegroundColor $color
}

function Invoke-Api {
    param(
        [string]$Method, [string]$Path, [string]$Token, $Body,
        [hashtable]$Headers = @{}, [string]$Base = $script:BaseUrl
    )
    $requestHeaders = @{}
    if ($Token) { $requestHeaders['Authorization'] = "Bearer $Token" }
    foreach ($k in $Headers.Keys) { $requestHeaders[$k] = $Headers[$k] }
    $params = @{
        Uri = "$Base$Path"; Method = $Method; Headers = $requestHeaders
        ContentType = 'application/json; charset=utf-8'; TimeoutSec = 30
    }
    if ($null -ne $Body) { $params['Body'] = ($Body | ConvertTo-Json -Depth 12 -Compress) }
    $response = Invoke-WebRequest @params -UseBasicParsing
    $payload = $response.Content | ConvertFrom-Json
    if ($null -ne $payload -and $payload.PSObject.Properties.Name -contains 'code' -and $payload.code -ne 0) {
        throw "API error code=$($payload.code) message=$($payload.message) path=$Path"
    }
    if ($null -ne $payload -and $payload.PSObject.Properties.Name -contains 'data') { return $payload.data }
    if ($null -ne $payload -and $payload.PSObject.Properties.Name -contains 'result') { return $payload.result }
    return $payload
}

function Wait-Until {
    param([scriptblock]$Probe, [int]$TimeoutSeconds = 30, [int]$IntervalMs = 800)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try { $value = & $Probe; if ($value) { return $value } } catch { }
        Start-Sleep -Milliseconds $IntervalMs
    } while ((Get-Date) -lt $deadline)
    return $null
}

$script:BaseUrl = $BaseUrl
Write-Host "== Tenancy Center E2E verification ==" -ForegroundColor Cyan
Write-Host "base url: $BaseUrl`n"

# 0. health
$health = @(
    @{ name = 'gateway'; url = "$BaseUrl/actuator/health" },
    @{ name = 'tenant-service'; url = "$TenantUrl/actuator/health" },
    @{ name = 'approval-service'; url = "$ApprovalUrl/actuator/health" },
    @{ name = 'agent-service'; url = "$AgentUrl/actuator/health" },
    @{ name = 'mcp-service'; url = "$McpUrl/actuator/health" }
)
foreach ($h in $health) {
    try {
        $r = Invoke-WebRequest -Uri $h.url -UseBasicParsing -TimeoutSec 5
        Add-Result "health:$($h.name)" ($r.StatusCode -eq 200) "HTTP $($r.StatusCode)"
    } catch {
        Add-Result "health:$($h.name)" $false $_.Exception.Message
    }
}

# 1. login admin
$adminToken = $null
try {
    $login = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ username = 'admin'; password = 'Admin@123' }
    $adminToken = $login.token
    Add-Result 'auth:login-admin' ([bool]$adminToken) ("roles=" + ($login.principal.roles -join ','))
} catch {
    Add-Result 'auth:login-admin' $false $_.Exception.Message
    throw 'cannot continue without an admin token'
}

# 2. create tenant
$unique = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$tenantCode = "e2e-$unique"
try {
    $body = @{
        tenantCode = $tenantCode; name = "E2E Tenant $unique"
        contactName = 'E2E Bot'; contactEmail = 'e2e@example.com'; contactPhone = '+86-13800000000'
        plan = 'PRO'; region = 'cn-shanghai'; isolationMode = 'SHARED_SCHEMA'
        quotaSeats = 120; quotaStorageGb = 200; quotaApiRpm = 1200
        source = 'MANUAL'; attributes = @{ industry = 'finance' }; autoSubmit = $false
    }
    $tenant = Invoke-Api -Method POST -Path '/api/tenants' -Token $adminToken -Body $body
    $script:createdTenant = $tenant
    Add-Result 'tenant:create' ($tenant.status -eq 'DRAFT') ("id=$($tenant.id) status=$($tenant.status)")
} catch {
    Add-Result 'tenant:create' $false $_.Exception.Message
}

# 3. submit for approval
if ($script:createdTenant) {
    try {
        $submitted = Invoke-Api -Method POST -Path "/api/tenants/$($script:createdTenant.id)/submit" -Token $adminToken
        Add-Result 'tenant:submit' ($submitted.status -ne 'DRAFT') "status=$($submitted.status)"
    } catch {
        Add-Result 'tenant:submit' $false $_.Exception.Message
    }
}

# 4. approval created (wait until it shows up)
if ($script:createdTenant) {
    $found = Wait-Until -TimeoutSeconds 20 -Probe {
        $page = Invoke-Api -Method GET -Path '/api/approvals?page=0&size=50' -Token $adminToken
        $items = $page.items
        if (-not $items) { $page }
        $match = $items | Where-Object { $_.tenantId -eq $script:createdTenant.id } | Select-Object -First 1
        if ($match) { $match } else { $null }
    }
    if ($found) {
        $script:approvalId = $found.id
        Add-Result 'approval:created' $true ("id=$($found.id) state=$($found.state) risk=$($found.riskLevel) auto=$($found.autoDecided)")
    } else {
        Add-Result 'approval:created' $false 'no approval record found within 20s'
    }
}

# 5. human approval (if the workflow is pending)
if ($script:approvalId) {
    $pending = Wait-Until -TimeoutSeconds 15 -Probe {
        $a = Invoke-Api -Method GET -Path "/api/approvals/$($script:approvalId)" -Token $adminToken
        if ($a.state -eq 'PENDING_APPROVAL') { $a } else { $null }
    }
    if ($pending) {
        try {
            $approver = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ username = 'approver'; password = 'Approver@123' }
            $approverToken = $approver.token
            $decision = Invoke-Api -Method POST -Path "/api/approvals/$($script:approvalId)/decisions" -Token $approverToken -Body @{
                decision = 'APPROVE'; comment = 'E2E automated approval'
            }
            Add-Result 'approval:decide' ($decision.state -in @('APPROVED', 'PROVISIONING', 'ACTIVE')) "state=$($decision.state)"
        } catch {
            Add-Result 'approval:decide' $false $_.Exception.Message
        }
    } else {
        Add-Result 'approval:decide' $true 'auto-approved by policy (no human node)'
    }
}

# 6. tenant becomes ACTIVE
if ($script:createdTenant) {
    $active = Wait-Until -TimeoutSeconds $PollSeconds -Probe {
        $t = Invoke-Api -Method GET -Path "/api/tenants/$($script:createdTenant.id)" -Token $adminToken
        if ($t.status -eq 'ACTIVE') { $t } else { $null }
    }
    if ($active) {
        Add-Result 'tenant:activated' $true "status=$($active.status) risk=$($active.riskLevel)"
    } else {
        $t = Invoke-Api -Method GET -Path "/api/tenants/$($script:createdTenant.id)" -Token $adminToken
        Add-Result 'tenant:activated' $false "final status=$($t.status)"
    }
}

# 7. MCP protocol
$mcpHeaders = @{ 'X-API-Key' = 'dev-mcp-key' }
try {
    $init = Invoke-Api -Method POST -Path '/mcp' -Headers $mcpHeaders -Body @{
        jsonrpc = '2.0'; id = 1; method = 'initialize'
        params = @{ protocolVersion = '2025-06-18'; capabilities = @{}; clientInfo = @{ name = 'e2e'; version = '1.0' } }
    }
    Add-Result 'mcp:initialize' ($null -ne $init) ("server=" + $init.serverInfo.name + " tools=" + $null)
} catch {
    Add-Result 'mcp:initialize' $false $_.Exception.Message
}
try {
    $tools = Invoke-Api -Method POST -Path '/mcp' -Headers $mcpHeaders -Body @{ jsonrpc = '2.0'; id = 2; method = 'tools/list' }
    $count = if ($tools.tools) { ($tools.tools | Measure-Object).Count } else { 0 }
    Add-Result 'mcp:tools-list' ($count -ge 14) "tools=$count"
} catch {
    Add-Result 'mcp:tools-list' $false $_.Exception.Message
}
if ($script:createdTenant) {
    try {
        $call = Invoke-Api -Method POST -Path '/mcp' -Headers $mcpHeaders -Body @{
            jsonrpc = '2.0'; id = 3; method = 'tools/call'
            params = @{ name = 'tenant.get'; arguments = @{ tenantId = $script:createdTenant.id } }
        }
        $text = $call.content[0].text
        Add-Result 'mcp:tools-call-tenant-get' ([bool]$text -and $text.Contains($tenantCode)) "isError=$($call.isError)"
    } catch {
        Add-Result 'mcp:tools-call-tenant-get' $false $_.Exception.Message
    }
}

# 8. CLI (optional, only if the CLI jar has been built)
$cliJar = Get-ChildItem -Path (Join-Path $root 'tenancy-cli\target') -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc|original' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($cliJar) {
    try {
        $output = & java -jar $cliJar.FullName --base-url $BaseUrl --token $adminToken --output json tenant list 2>&1
        Add-Result 'cli:tenant-list' ($LASTEXITCODE -eq 0 -and ($output -join "`n").Contains($tenantCode)) ("exit=$LASTEXITCODE")
    } catch {
        Add-Result 'cli:tenant-list' $false $_.Exception.Message
    }
} else {
    $results.Add([pscustomobject]@{ Step = 'cli:tenant-list'; Result = 'SKIP'; Detail = 'cli jar not built' })
    Write-Host '[SKIP] cli:tenant-list - cli jar not built' -ForegroundColor Yellow
}

# 9. overview + frontend API surface
try {
    $overview = Invoke-Api -Method GET -Path '/api/platform/overview' -Token $adminToken
    Add-Result 'platform:overview' ($null -ne $overview.tenants) ("tenants=" + $overview.tenants.total)
} catch {
    Add-Result 'platform:overview' $false $_.Exception.Message
}

Write-Host "`n== summary ==" -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host
$failed = ($results | Where-Object { $_.Result -eq 'FAIL' } | Measure-Object).Count
$passed = ($results | Where-Object { $_.Result -eq 'PASS' } | Measure-Object).Count
$skipped = ($results | Where-Object { $_.Result -eq 'SKIP' } | Measure-Object).Count
Write-Host ("PASS={0} FAIL={1} SKIP={2}" -f $passed, $failed, $skipped) -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'Red' })
if ($failed -gt 0) { exit 1 } else { exit 0 }
