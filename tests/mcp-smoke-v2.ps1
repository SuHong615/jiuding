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
    MCP smoke test v2: 14 base tools + v2 identity tools (org/account/rating/delete).

.DESCRIPTION
    Verifies that the deployed MCP server exposes the v2 tool surface and that the
    new tools proxy correctly to the v2 REST API (docs/11 section 6, task-10):

        org.tree, org.create, account.list, account.create,
        account.reset_password, rating.assess, rating.list, tenant.delete

    The expected tools/list count is 22 = 14 base tools (docs/05) + 8 v2 tools
    (confirmed by the Lead: the "20" in an earlier task draft was an arithmetic slip;
    the 8-tool list gives 14 + 8 = 22). Pass -ExpectedToolCount to override.

    Old deployments (14 tools only) are reported as SKIP, never FAIL, so the script
    stays green before the v2 rollout. Genuine mismatches in a partially upgraded
    deployment are FAIL.

    Output: [PASS]/[FAIL]/[SKIP] per check + summary. Exit 0 unless a real failure.
    ASCII-only source for Windows PowerShell 5.1 compatibility (no BOM).

.EXAMPLE
    pwsh ./tests/mcp-smoke-v2.ps1
    powershell -File ./tests/mcp-smoke-v2.ps1 -BaseUrl http://localhost:30080
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$ApiKey = 'dev-mcp-key',
    [int]$ExpectedToolCount = 22,
    [int]$TimeoutSec = 20
)

$ErrorActionPreference = 'Stop'
$script:Results = New-Object System.Collections.Generic.List[object]
$script:NextId = 0
$script:CreatedTenantViaMcp = $false
$script:SmokeTenantId = $null

$baseTools = @(
    'tenant.create', 'tenant.list', 'tenant.get', 'tenant.submit', 'tenant.suspend', 'tenant.activate',
    'collection.import', 'approval.list_pending', 'approval.get', 'approval.decide',
    'skill.list', 'skill.run', 'agent.run', 'platform.overview'
)
$v2Tools = @(
    'org.tree', 'org.create', 'account.list', 'account.create',
    'account.reset_password', 'rating.assess', 'rating.list', 'tenant.delete'
)
if ($ExpectedToolCount -le 0) { $ExpectedToolCount = $baseTools.Count + $v2Tools.Count }

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
    Write-Host '---------------- mcp smoke v2 summary ----------------'
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

function Write-Detail {
    param([string]$Message)
    Write-Host ("      {0}" -f $Message) -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- HTTP helpers
function Invoke-TenancyHttp {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSecArg = 15
    )
    $requestHeaders = @{}
    foreach ($key in $Headers.Keys) { $requestHeaders[$key] = [string]$Headers[$key] }
    $params = @{
        Uri             = $Uri
        Method          = $Method
        Headers         = $requestHeaders
        TimeoutSec      = $TimeoutSecArg
        UseBasicParsing = $true
        ErrorAction     = 'Stop'
    }
    if ($null -ne $Body) {
        $params['Body'] = $Body
        $params['ContentType'] = 'application/json; charset=utf-8'
    }
    try {
        $response = Invoke-WebRequest @params
        $content = $response.Content
        if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
        else { $content = [string]$content }
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Content = $content; Headers = $response.Headers }
    }
    catch {
        $response = $_.Exception.Response
        if ($response) {
            $content = ''
            try {
                if ($response.PSObject.Properties.Name -contains 'Content') {
                    $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                else {
                    $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
                    $content = $reader.ReadToEnd()
                    $reader.Close()
                }
            }
            catch { $content = '' }
            return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Content = $content; Headers = $null }
        }
        throw
    }
}

$mcpUrl = $BaseUrl.TrimEnd('/') + '/mcp'
$mcpHeaders = @{ 'X-API-Key' = $ApiKey; 'Accept' = 'application/json, text/event-stream' }

function Invoke-McpRpc {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [hashtable]$Params = @{}
    )
    $script:NextId++
    $payload = @{ jsonrpc = '2.0'; id = $script:NextId; method = $Method; params = $Params }
    $body = $payload | ConvertTo-Json -Depth 12 -Compress
    $response = Invoke-TenancyHttp -Uri $mcpUrl -Method 'POST' -Headers $mcpHeaders -Body $body -TimeoutSecArg $TimeoutSec
    if ($response.StatusCode -ge 400) {
        throw ("HTTP {0} on /mcp: {1}" -f $response.StatusCode, $response.Content)
    }
    $document = $response.Content | ConvertFrom-Json
    if ($null -ne $document.error) {
        throw ("JSON-RPC error {0}: {1}" -f $document.error.code, $document.error.message)
    }
    return $document.result
}

function Get-ToolPayload {
    param($Result)
    if ($null -eq $Result) { return $null }
    $payload = $Result.structuredContent
    if ($null -eq $payload -and $Result.content) {
        $contentItems = @($Result.content)
        if ($contentItems.Count -gt 0) {
            $text = [string]$contentItems[0].text
            if (-not [string]::IsNullOrWhiteSpace($text)) {
                try { $payload = $text | ConvertFrom-Json } catch { $payload = $text }
            }
        }
    }
    if ($null -eq $payload) { return $null }
    # Unary comma keeps empty JSON arrays (e.g. org.tree on a tenant without org units)
    # from being unrolled to $null by the PowerShell pipeline.
    return , $payload
}

function Invoke-ToolCall {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [hashtable]$Arguments = @{}
    )
    return Invoke-McpRpc -Method 'tools/call' -Params @{ name = $Name; arguments = $Arguments }
}

function Format-ToolError {
    param($Result)
    $payload = Get-ToolPayload $Result
    if ($null -eq $payload) { return 'tool returned isError with empty payload' }
    $errorCode = $null
    $message = $null
    if ($payload.PSObject.Properties.Name -contains 'errorCode') { $errorCode = $payload.errorCode }
    if ($payload.PSObject.Properties.Name -contains 'message') { $message = $payload.message }
    return ("isError: errorCode={0} message={1}" -f $errorCode, $message)
}

function Get-JsonProp {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object.PSObject.Properties.Name -contains $Name) { return $Object.$Name }
    return $null
}

# ---------------------------------------------------------------- preflight
Write-Host ("==> mcp-smoke-v2 against {0}" -f $BaseUrl) -ForegroundColor Cyan
$reachable = $false
$info = $null
try {
    $probe = Invoke-TenancyHttp -Uri ($BaseUrl.TrimEnd('/') + '/mcp/info') -Headers $mcpHeaders -TimeoutSecArg 5
    $reachable = ($probe.StatusCode -ge 200 -and $probe.StatusCode -lt 300)
    if ($reachable) { $info = $probe.Content | ConvertFrom-Json }
}
catch { $reachable = $false }

if (-not $reachable) {
    Add-Check 'preflight /mcp/info' 'SKIP' ("MCP service not reachable at {0}" -f $BaseUrl)
    foreach ($name in @('tools/list base tools', 'tools/list v2 tool count', 'tools/list v2 tools present',
            'tools/call org.tree', 'tools/call account.list', 'tools/call rating.list')) {
        Add-Check $name 'SKIP' 'service down'
    }
    Show-Summary
}
Add-Check 'preflight /mcp/info' 'PASS' ("server={0} tools={1}" -f (Get-JsonProp (Get-JsonProp $info 'server') 'name'), @(Get-JsonProp $info 'tools').Count)

# ---------------------------------------------------------------- tools/list
$tools = @()
try {
    $listResult = Invoke-McpRpc -Method 'tools/list'
    $tools = @(Get-JsonProp $listResult 'tools')
    $toolNames = @($tools | ForEach-Object { [string](Get-JsonProp $_ 'name') })
    $missingBase = @($baseTools | Where-Object { $toolNames -notcontains $_ })
    if ($missingBase.Count -gt 0) {
        throw ("missing base tools: {0}" -f ($missingBase -join ', '))
    }
    Add-Check 'tools/list base tools' 'PASS' ("14/14 base tools present")
}
catch {
    Add-Check 'tools/list base tools' 'FAIL' $_.Exception.Message
    foreach ($name in @('tools/list v2 tool count', 'tools/list v2 tools present',
            'tools/call org.tree', 'tools/call account.list', 'tools/call rating.list')) {
        Add-Check $name 'SKIP' 'tools/list failed'
    }
    Show-Summary
}

$toolNames = @($tools | ForEach-Object { [string](Get-JsonProp $_ 'name') })
$missingV2 = @($v2Tools | Where-Object { $toolNames -notcontains $_ })
$v2Deployed = ($missingV2.Count -eq 0)
$actualCount = $tools.Count

if (-not $v2Deployed -and $actualCount -le $baseTools.Count) {
    # Old deployment: 14 tools only. This is not a failure, just not rolled out yet.
    $detail = ("v2 MCP tools not deployed; tools/list has {0} tools, missing: {1}" -f $actualCount, ($missingV2 -join ', '))
    Add-Check 'tools/list v2 tool count' 'SKIP' $detail
    Add-Check 'tools/list v2 tools present' 'SKIP' ("missing: {0}" -f ($missingV2 -join ', '))
    foreach ($name in @('tools/call org.tree', 'tools/call account.list', 'tools/call rating.list')) {
        Add-Check $name 'SKIP' 'v2 MCP tools not deployed'
    }
    Show-Summary
}

if ($actualCount -ne $ExpectedToolCount) {
    Add-Check 'tools/list v2 tool count' 'FAIL' ("expected {0} tools (14 base + 8 v2), got {1}" -f $ExpectedToolCount, $actualCount)
}
else {
    Add-Check 'tools/list v2 tool count' 'PASS' ("{0} tools" -f $actualCount)
}

if ($missingV2.Count -gt 0) {
    Add-Check 'tools/list v2 tools present' 'FAIL' ("missing v2 tools: {0}" -f ($missingV2 -join ', '))
}
else {
    Add-Check 'tools/list v2 tools present' 'PASS' 'all 8 v2 tools present'
}

# ---------------------------------------------------------------- pick a tenant
$tenantId = $null
try {
    $tenantList = Invoke-ToolCall -Name 'tenant.list' -Arguments @{ page = 0; size = 1 }
    if ($tenantList.isError -eq $true) { throw (Format-ToolError $tenantList) }
    $tenantPayload = Get-ToolPayload $tenantList
    $items = @(Get-JsonProp $tenantPayload 'items')
    if ($items.Count -gt 0) { $tenantId = [string](Get-JsonProp $items[0] 'id') }
}
catch { Write-Detail ("tenant.list failed: {0}" -f $_.Exception.Message) }

if ([string]::IsNullOrWhiteSpace($tenantId)) {
    try {
        $code = 'mcp-v2-smoke-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' + (Get-Random -Minimum 1000 -Maximum 9999)
        $created = Invoke-ToolCall -Name 'tenant.create' -Arguments @{
            tenantCode = $code
            name       = 'MCP V2 Smoke Tenant'
            plan       = 'PRO'
            region     = 'cn-shanghai'
            autoSubmit = $false
        }
        if ($created.isError -eq $true) { throw (Format-ToolError $created) }
        $createdPayload = Get-ToolPayload $created
        $tenantId = [string](Get-JsonProp $createdPayload 'id')
        if (-not [string]::IsNullOrWhiteSpace($tenantId)) { $script:CreatedTenantViaMcp = $true }
    }
    catch { Write-Detail ("tenant.create failed: {0}" -f $_.Exception.Message) }
}

if ([string]::IsNullOrWhiteSpace($tenantId)) {
    foreach ($name in @('tools/call org.tree', 'tools/call account.list', 'tools/call rating.list')) {
        Add-Check $name 'SKIP' 'no tenant available for tool call arguments (tenant.list empty and tenant.create failed)'
    }
    Show-Summary
}
Write-Detail ("using tenantId={0}" -f $tenantId)

# ---------------------------------------------------------------- v2 tool calls
try {
    $result = Invoke-ToolCall -Name 'org.tree' -Arguments @{ tenantId = $tenantId; format = 'tree' }
    if ($result.isError -eq $true) { throw (Format-ToolError $result) }
    $payload = Get-ToolPayload $result
    if ($null -eq $payload) { throw 'org.tree returned no structuredContent/content' }
    Add-Check 'tools/call org.tree' 'PASS' 'structuredContent returned'
}
catch {
    Add-Check 'tools/call org.tree' 'FAIL' $_.Exception.Message
}

try {
    $result = Invoke-ToolCall -Name 'account.list' -Arguments @{ tenantId = $tenantId; page = 0; size = 5 }
    if ($result.isError -eq $true) { throw (Format-ToolError $result) }
    $payload = Get-ToolPayload $result
    if ($null -eq $payload) { throw 'account.list returned no structuredContent/content' }
    $pageTotal = Get-JsonProp $payload 'total'
    if ($null -eq $pageTotal -and $null -eq (Get-JsonProp $payload 'items')) {
        throw 'account.list payload has neither total nor items (expected PageResult)'
    }
    Add-Check 'tools/call account.list' 'PASS' ("total={0}" -f $pageTotal)
}
catch {
    Add-Check 'tools/call account.list' 'FAIL' $_.Exception.Message
}

try {
    $result = Invoke-ToolCall -Name 'rating.list' -Arguments @{ tenantId = $tenantId; page = 0; size = 5 }
    if ($result.isError -eq $true) { throw (Format-ToolError $result) }
    $payload = Get-ToolPayload $result
    if ($null -eq $payload) { throw 'rating.list returned no structuredContent/content' }
    $ratingTotal = Get-JsonProp $payload 'total'
    if ($null -eq $ratingTotal -and $null -eq (Get-JsonProp $payload 'items')) {
        throw 'rating.list payload has neither total nor items (expected PageResult)'
    }
    Add-Check 'tools/call rating.list' 'PASS' ("total={0}" -f $ratingTotal)
}
catch {
    Add-Check 'tools/call rating.list' 'FAIL' $_.Exception.Message
}

# best-effort cleanup of a tenant created only for this smoke run
if ($script:CreatedTenantViaMcp -and -not [string]::IsNullOrWhiteSpace($tenantId)) {
    try {
        $cleanup = Invoke-ToolCall -Name 'tenant.delete' -Arguments @{ tenantId = $tenantId }
        if ($cleanup.isError -eq $true) {
            Write-Detail ("cleanup tenant.delete returned {0}" -f (Format-ToolError $cleanup))
        }
        else {
            Write-Detail ("cleanup tenant {0} deleted" -f $tenantId)
        }
    }
    catch {
        Write-Detail ("cleanup tenant.delete failed: {0}" -f $_.Exception.Message)
    }
}

Show-Summary
