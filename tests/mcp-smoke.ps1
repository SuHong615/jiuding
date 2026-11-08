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
    MCP protocol smoke test: initialize / notifications/initialized / ping / tools/list /
    tools/call / unknown method / GET /mcp/info.

.DESCRIPTION
    Uses JSON-RPC 2.0 over POST /mcp (docs/05-mcp-spec.md). Default base URL is
    http://localhost:18080 (local process mode); docker mode uses http://localhost:8080.

    Prints one PASS/FAIL/SKIP line per assertion plus a summary; exit code 1 on failure.
    If the gateway/MCP service is unreachable everything is SKIP and exit code is 0.

.EXAMPLE
    pwsh ./tests/mcp-smoke.ps1
    pwsh ./tests/mcp-smoke.ps1 -BaseUrl http://localhost:8080 -ApiKey dev-mcp-key
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$ApiKey = 'dev-mcp-key',
    [int]$TimeoutSec = 15
)

$ErrorActionPreference = 'Stop'
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
    Write-Host '---------------- mcp smoke summary ----------------'
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
        Uri             = $Uri
        Method          = $Method
        Headers         = $requestHeaders
        TimeoutSec      = $TimeoutSec
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
$headers = @{ 'X-API-Key' = $ApiKey; 'Accept' = 'application/json, text/event-stream' }
$nextId = 1

function Invoke-Mcp {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [hashtable]$Params = @{},
        [switch]$Notification
    )
    $payload = @{ jsonrpc = '2.0'; method = $Method; params = $Params }
    if (-not $Notification) {
        $script:nextId++
        $payload['id'] = $script:nextId
    }
    $body = $payload | ConvertTo-Json -Depth 10 -Compress
    return Invoke-TenancyHttp -Uri $mcpUrl -Method 'POST' -Headers $headers -Body $body -TimeoutSec $TimeoutSec
}

# ---------------------------------------------------------------- preflight
$reachable = $false
try {
    $probe = Invoke-TenancyHttp -Uri ($BaseUrl.TrimEnd('/') + '/mcp/info') -Headers $headers -TimeoutSec 5
    $reachable = ($probe.StatusCode -ge 200 -and $probe.StatusCode -lt 300)
}
catch {
    $reachable = $false
}
if (-not $reachable) {
    Add-Check 'preflight /mcp/info' 'SKIP' ("MCP service not reachable at {0}" -f $BaseUrl)
    Add-Check 'initialize' 'SKIP' 'service down'
    Add-Check 'notifications/initialized' 'SKIP' 'service down'
    Add-Check 'ping' 'SKIP' 'service down'
    Add-Check 'tools/list' 'SKIP' 'service down'
    Add-Check 'tools/call tenant.list' 'SKIP' 'service down'
    Add-Check 'unknown method' 'SKIP' 'service down'
    Show-Summary
}
Add-Check 'preflight /mcp/info' 'PASS' $BaseUrl

$info = $null
try {
    $info = ($probe.Content | ConvertFrom-Json)
    if ($null -eq $info.protocolVersion) { throw '/mcp/info missing protocolVersion' }
    Add-Check 'GET /mcp/info' 'PASS' ("server={0} tools={1}" -f $info.server.name, @($info.tools).Count)
}
catch {
    Add-Check 'GET /mcp/info' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- initialize
$sessionId = $null
try {
    $response = Invoke-Mcp -Method 'initialize' -Params @{
        protocolVersion = '2025-06-18'
        capabilities    = @{}
        clientInfo      = @{ name = 'mcp-smoke'; version = '1.0' }
    }
    if ($response.StatusCode -ge 400) { throw ("HTTP {0}: {1}" -f $response.StatusCode, $response.Content) }
    $document = $response.Content | ConvertFrom-Json
    if ($null -ne $document.error) { throw ("JSON-RPC error {0}: {1}" -f $document.error.code, $document.error.message) }
    if (-not $document.result.protocolVersion) { throw 'initialize result missing protocolVersion' }
    if (-not $document.result.serverInfo.name) { throw 'initialize result missing serverInfo.name' }
    $headersFromResponse = $response.Headers
    if ($headersFromResponse) {
        if ($headersFromResponse['Mcp-Session-Id']) { $sessionId = [string]$headersFromResponse['Mcp-Session-Id'] }
        elseif ($headersFromResponse['mcp-session-id']) { $sessionId = [string]$headersFromResponse['mcp-session-id'] }
    }
    Add-Check 'initialize' 'PASS' ("protocol={0} server={1}" -f $document.result.protocolVersion, $document.result.serverInfo.name)
}
catch {
    Add-Check 'initialize' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- notifications/initialized
try {
    $notification = Invoke-Mcp -Method 'notifications/initialized' -Notification
    if ($notification.StatusCode -in @(200, 202, 204)) {
        Add-Check 'notifications/initialized' 'PASS' ("HTTP {0}" -f $notification.StatusCode)
    }
    else {
        Add-Check 'notifications/initialized' 'FAIL' ("expected 202, got HTTP {0}" -f $notification.StatusCode)
    }
}
catch {
    Add-Check 'notifications/initialized' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- ping
try {
    $ping = Invoke-Mcp -Method 'ping'
    $pingDocument = $ping.Content | ConvertFrom-Json
    if ($null -ne $pingDocument.error) { throw ("JSON-RPC error {0}: {1}" -f $pingDocument.error.code, $pingDocument.error.message) }
    Add-Check 'ping' 'PASS' 'result received'
}
catch {
    Add-Check 'ping' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- tools/list
$expectedTools = @(
    'tenant.create', 'tenant.list', 'tenant.get', 'tenant.submit', 'tenant.suspend', 'tenant.activate',
    'collection.import', 'approval.list_pending', 'approval.get', 'approval.decide',
    'skill.list', 'skill.run', 'agent.run', 'platform.overview'
)
$v2Tools = @(
    'org.tree', 'org.create', 'account.list', 'account.create', 'account.reset_password',
    'rating.assess', 'rating.list', 'tenant.delete'
)
try {
    $listResponse = Invoke-Mcp -Method 'tools/list'
    $listDocument = $listResponse.Content | ConvertFrom-Json
    if ($null -ne $listDocument.error) { throw ("JSON-RPC error {0}: {1}" -f $listDocument.error.code, $listDocument.error.message) }
    $tools = @($listDocument.result.tools)
    $toolNames = @($tools | ForEach-Object { $_.name })
    $missingTools = @($expectedTools | Where-Object { $toolNames -notcontains $_ })
    $toolCount = $tools.Count
    # Backward compatible during the v2 rollout: 14 = v1 only, 22 = v1 + 8 identity tools.
    if ($toolCount -ne 14 -and $toolCount -ne 22) {
        throw ("expected 14 (v1) or 22 (v1+v2) tools, got {0}" -f $toolCount)
    }
    if ($missingTools.Count -gt 0) { throw ("missing v1 tools: {0}" -f ($missingTools -join ', ')) }
    $missingV2 = @($v2Tools | Where-Object { $toolNames -notcontains $_ })
    if ($toolCount -eq 22 -and $missingV2.Count -gt 0) {
        throw ("missing v2 tools: {0}" -f ($missingV2 -join ', '))
    }
    Add-Check 'tools/list' 'PASS' ("{0} tools, schemas present={1}" -f $toolCount, (@($tools | Where-Object { $_.inputSchema }).Count))
}
catch {
    Add-Check 'tools/list' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- tools/call
try {
    $callResponse = Invoke-Mcp -Method 'tools/call' -Params @{
        name      = 'tenant.list'
        arguments = @{ page = 0; size = 5 }
    }
    $callDocument = $callResponse.Content | ConvertFrom-Json
    if ($null -ne $callDocument.error) { throw ("JSON-RPC error {0}: {1}" -f $callDocument.error.code, $callDocument.error.message) }
    if ($callDocument.result.isError -eq $true) {
        throw ("tool returned isError: {0}" -f ($callDocument.result.structuredContent | ConvertTo-Json -Compress))
    }
    $hasContent = (@($callDocument.result.content).Count -gt 0) -or ($null -ne $callDocument.result.structuredContent)
    if (-not $hasContent) { throw 'tools/call result has neither content nor structuredContent' }
    Add-Check 'tools/call tenant.list' 'PASS' 'content+structuredContent returned'
}
catch {
    Add-Check 'tools/call tenant.list' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- unknown method -> -32601
try {
    $unknown = Invoke-Mcp -Method 'no/such-method'
    $unknownDocument = $unknown.Content | ConvertFrom-Json
    if ($null -eq $unknownDocument.error) { throw 'expected JSON-RPC error for unknown method' }
    if ([int]$unknownDocument.error.code -ne -32601) {
        throw ("expected error code -32601, got {0}" -f $unknownDocument.error.code)
    }
    Add-Check 'unknown method' 'PASS' 'error.code=-32601'
}
catch {
    Add-Check 'unknown method' 'FAIL' $_.Exception.Message
}

Show-Summary
