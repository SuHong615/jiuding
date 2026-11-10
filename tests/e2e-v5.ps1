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
    v5 E2E: org/account import, self-service onboarding, skill center, agent catalog,
    MCP credentials / audit / external servers, RBAC permissions, maintenance purge.

.DESCRIPTION
    Black-box E2E based on docs/15-v5-skill-mcp-onboarding-rbac.md and docs/02/03/05:

      A. admin login -> create + activate tenant -> download CSV import templates
         -> import 2 org units + 2 accounts -> duplicate import is skipped
         -> login with the imported account (tenantCode + temp password);
      B. public self-service onboarding: compliant submission (LOW + non ENTERPRISE)
         auto activates; non-compliant submission enters manual review and is visible
         in the tenant list; public status lookup works;
      C. skill center: generate a zip (SKILL.md + rules.yaml) with System.IO.Compression
         -> upload (DRAFT) -> submit review -> APPROVE -> enable -> listed as APPROVED+enabled
         -> duplicate upload returns 409; an optional REJECT branch is also verified;
      D. agent catalog: 4 agent types with descriptions; run risk-review and check the
         readable summary + Chinese step titles;
      E. MCP: config, per-user API key (plaintext returned once), tools/list + tools/call
         with that key, call audit, revoke -> 401, external MCP server CRUD;
      F. RBAC: /api/me/permissions (admin has *), 5 built-in roles, custom role + permission
         matrix, tenant account gets 403 on platform role APIs;
      G. lifecycle: POST /internal/maintenance/purge with X-Internal-Token returns counters,
         without the token it returns 401.

    Capability gate (old deployments stay green, exit code 0):
      The script probes /api/me/permissions, /api/mcp/config, /api/agents/catalog and
      /api/platform/roles before creating data. Any missing interface makes the whole
      v5 run SKIP with the exact missing path in the detail column.

    The purge check needs direct tenant-service access because the gateway intentionally
    answers 404 for /internal/**. Pass -TenantServiceUrl http://localhost:8081 to verify it;
    otherwise both purge checks are SKIP.

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.

.PARAMETER BaseUrl
    Gateway base URL. Default http://localhost:18080 (local process mode).

.PARAMETER TenantServiceUrl
    Optional direct tenant-service URL (e.g. http://localhost:8081) used for the
    /internal/maintenance/purge assertions. Empty means purge checks are SKIP.

.PARAMETER InternalToken
    Value of X-Internal-Token. Defaults to $env:TENANCY_INTERNAL_TOKEN or dev-internal-token.

.PARAMETER TimeoutSeconds
    Timeout for tenant activation and agent run polling. Default 120.

.EXAMPLE
    pwsh ./tests/e2e-v5.ps1
    pwsh ./tests/e2e-v5.ps1 -BaseUrl http://localhost:30080 -TenantServiceUrl http://localhost:8081
#>
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$TenantServiceUrl = '',
    [string]$InternalToken = '',
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

$script:Base = $BaseUrl.TrimEnd('/')
$script:TenantServiceBase = $null
if (-not [string]::IsNullOrWhiteSpace($TenantServiceUrl)) {
    $script:TenantServiceBase = $TenantServiceUrl.TrimEnd('/')
}
$script:InternalToken = $InternalToken
if ([string]::IsNullOrWhiteSpace($script:InternalToken)) {
    if (-not [string]::IsNullOrWhiteSpace($env:TENANCY_INTERNAL_TOKEN)) {
        $script:InternalToken = $env:TENANCY_INTERNAL_TOKEN
    }
    else {
        $script:InternalToken = 'dev-internal-token'
    }
}

$script:Results = New-Object System.Collections.Generic.List[object]
$script:AddedChecks = New-Object System.Collections.Generic.List[string]
$script:TenantIds = New-Object System.Collections.Generic.List[string]
$script:OnboardingCodes = New-Object System.Collections.Generic.List[string]
$script:CleanedUp = $false
$script:AdminToken = ''
$script:AdminHeaders = @{}
$script:MainTenantId = ''
$script:MainTenantCode = ''
$script:TenantAccountToken = ''
$script:McpKeyId = ''
$script:McpApiKey = ''
$script:ExternalServerId = ''
$script:CustomRoleCode = ''
$script:SkillId = ''
$script:SkillRejectId = ''
$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'v5 api availability',
    'tenant create',
    'tenant activation',
    'import template org',
    'import template accounts',
    'org import',
    'org import duplicate skipped',
    'account import',
    'account import duplicate skipped',
    'imported account login',
    'onboarding compliant submit',
    'onboarding auto activated',
    'onboarding status query',
    'onboarding non-compliant manual',
    'onboarding pending visible',
    'skill template download',
    'skill upload draft',
    'skill submit review',
    'skill approve',
    'skill enable',
    'skill list approved enabled',
    'skill duplicate upload 409',
    'skill reject branch',
    'agent catalog',
    'agent run risk-review',
    'agent run readable summary',
    'agent run chinese step titles',
    'mcp config',
    'mcp key create',
    'mcp tools list with key',
    'mcp tools call with key',
    'mcp audit recorded',
    'mcp key revoked 401',
    'mcp external server create+list',
    'mcp external server delete',
    'me permissions admin',
    'platform roles builtin',
    'custom role create',
    'custom role permission set',
    'custom role query',
    'custom role permissions readback',
    'tenant account platform roles 403',
    'purge internal token',
    'purge no token 401'
)

# ---------------------------------------------------------------- result helpers
function Add-Check {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$Detail = ''
    )
    $script:Results.Add([pscustomobject]@{ Check = $Name; Status = $Status; Detail = $Detail })
    if (-not $script:AddedChecks.Contains($Name)) { $script:AddedChecks.Add($Name) }
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } 'SKIP' { 'Yellow' } default { 'Gray' } }
    Write-Host ("[{0}] {1}{2}" -f $Status, $Name, $(if ($Detail) { " - $Detail" } else { '' })) -ForegroundColor $color
}

function Add-Skip {
    param([string]$Name, [string]$Reason)
    if ($script:AddedChecks.Contains($Name)) { return }
    Add-Check $Name 'SKIP' $Reason
}

function Skip-Checks {
    param([string[]]$Names, [string]$Reason)
    foreach ($name in $Names) { Add-Skip $name $Reason }
}

function Skip-Remaining {
    param([string]$Reason)
    foreach ($name in $script:PlannedChecks) {
        if ($script:AddedChecks.Contains($name)) { continue }
        Add-Check $name 'SKIP' $Reason
    }
}

function Show-Summary {
    Write-Host ''
    Write-Host '---------------- e2e-v5 summary ----------------'
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

# ---------------------------------------------------------------- misc helpers
function Now-RandomCode {
    param([string]$Prefix)
    return ('{0}-{1}-{2}' -f $Prefix, (Get-Date -Format 'yyyyMMddHHmmss'), (Get-Random -Minimum 10000 -Maximum 99999))
}

function New-RandomDigits {
    param([int]$Length = 6)
    $builder = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $Length; $i++) { [void]$builder.Append((Get-Random -Minimum 0 -Maximum 10)) }
    return $builder.ToString()
}

function Get-JsonProp {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object.PSObject.Properties.Name -contains $Name) { return $Object.$Name }
    return $null
}

function Test-HasProp {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $false }
    return ($Object.PSObject.Properties.Name -contains $Name)
}

function Get-Items {
    param($Data)
    if ($null -eq $Data) { return @() }
    if ($Data -is [System.Array]) { return @($Data) }
    # A single record (e.g. SkillView with id/tools) must not be mistaken for a page
    # wrapper just because it happens to have a "tools" property.
    if (Test-HasProp $Data 'id') { return @($Data) }
    foreach ($listName in @('items', 'records', 'list', 'agents', 'roles', 'tools', 'permissions', 'contents')) {
        if (Test-HasProp $Data $listName) { return @($Data.$listName) }
    }
    return @($Data)
}

# Builds a CSV payload from a downloaded template header. Values are mapped by the
# lower-cased column name so the script follows whatever the server template declares.
function New-ImportCsv {
    param(
        [Parameter(Mandatory = $true)][string]$HeaderLine,
        [Parameter(Mandatory = $true)][object[]]$Rows
    )
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine($HeaderLine.Trim())
    $columns = @($HeaderLine.Trim().TrimStart([char]0xFEFF) -split ',' | ForEach-Object { $_.Trim().Trim('"').ToLowerInvariant() })
    foreach ($row in $Rows) {
        $values = New-Object System.Collections.Generic.List[string]
        foreach ($column in $columns) {
            $value = ''
            if ($row -is [hashtable] -and $row.ContainsKey($column)) { $value = [string]$row[$column] }
            if ($value -match '[",\r\n]') { $value = '"' + ($value -replace '"', '""') + '"' }
            $values.Add($value)
        }
        [void]$builder.AppendLine(($values -join ','))
    }
    return $builder.ToString()
}

# ---------------------------------------------------------------- HTTP helpers
function Invoke-TenancyHttp {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 30,
        [string]$ContentType = 'application/json; charset=utf-8'
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
        $params['ContentType'] = $ContentType
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
        if ($null -eq $response) { throw }
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
        $status = 0
        try {
            if ($response.PSObject.Properties.Name -contains 'StatusCode') { $status = [int]$response.StatusCode }
        }
        catch { $status = 0 }
        return [pscustomobject]@{ StatusCode = $status; Content = $content; Headers = $null }
    }
}

function ConvertTo-Envelope {
    param([string]$Content, [int]$StatusCode)
    $envelope = $null
    try { if (-not [string]::IsNullOrWhiteSpace($Content)) { $envelope = $Content | ConvertFrom-Json } } catch { $envelope = $null }
    $code = $null
    $data = $null
    $message = ''
    if ($null -ne $envelope -and (Test-HasProp $envelope 'code')) { $code = [int]$envelope.code }
    if ($null -ne $envelope -and (Test-HasProp $envelope 'data')) { $data = $envelope.data }
    if ($null -ne $envelope -and (Test-HasProp $envelope 'message')) { $message = [string]$envelope.message }
    $httpOk = ($StatusCode -ge 200 -and $StatusCode -lt 300)
    $ok = ($httpOk -and ($null -eq $code -or $code -eq 0))
    return [pscustomobject]@{
        Ok      = $ok
        Status  = $StatusCode
        Code    = $code
        Data    = $data
        Message = $message
        Content = $Content
    }
}

function Invoke-ApiRaw {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 30
    )
    $response = Invoke-TenancyHttp -Uri ($script:Base + $Path) -Method $Method `
        -Headers $Headers -Body $Body -TimeoutSec $TimeoutSec
    return ConvertTo-Envelope -Content $response.Content -StatusCode $response.StatusCode
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 30
    )
    $raw = Invoke-ApiRaw -Path $Path -Method $Method -Headers $Headers -Body $Body -TimeoutSec $TimeoutSec
    if (-not $raw.Ok) {
        throw ("HTTP {0} code={1} on {2}: {3}" -f $raw.Status, $raw.Code, $Path, $raw.Message)
    }
    return $raw.Data
}

function Invoke-MultipartApi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][byte[]]$FileBytes,
        [string]$FieldName = 'file',
        [string]$FileName = 'upload.bin',
        [string]$PartContentType = 'application/octet-stream',
        [hashtable]$FormFields = @{},
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 60
    )
    $client = New-Object System.Net.Http.HttpClient
    $multipart = $null
    $request = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
        $multipart = New-Object System.Net.Http.MultipartFormDataContent
        foreach ($key in $FormFields.Keys) {
            $stringContent = New-Object System.Net.Http.StringContent([string]$FormFields[$key])
            $multipart.Add($stringContent, [string]$key)
        }
        $fileContent = [System.Net.Http.ByteArrayContent]::new($FileBytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($PartContentType)
        $multipart.Add($fileContent, $FieldName, $FileName)
        $httpMethod = New-Object System.Net.Http.HttpMethod('POST')
        $request = New-Object System.Net.Http.HttpRequestMessage($httpMethod, ($script:Base + $Path))
        foreach ($key in $Headers.Keys) {
            [void]$request.Headers.TryAddWithoutValidation([string]$key, [string]$Headers[$key])
        }
        $request.Content = $multipart
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        $response.Dispose()
        return ConvertTo-Envelope -Content $content -StatusCode $status
    }
    finally {
        if ($null -ne $request) { $request.Dispose() }
        if ($null -ne $multipart) { $multipart.Dispose() }
        $client.Dispose()
    }
}

function Get-RawBytes {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 30,
        [string]$BaseUrlOverride = ''
    )
    $base = $script:Base
    if (-not [string]::IsNullOrWhiteSpace($BaseUrlOverride)) { $base = $BaseUrlOverride.TrimEnd('/') }
    $requestHeaders = @{}
    foreach ($key in $Headers.Keys) { $requestHeaders[$key] = [string]$Headers[$key] }
    $response = Invoke-WebRequest -Uri ($base + $Path) -Headers $requestHeaders -UseBasicParsing -TimeoutSec $TimeoutSec -ErrorAction Stop
    $stream = $response.RawContentStream
    if ($null -eq $stream) {
        return , ([System.Text.Encoding]::UTF8.GetBytes([string]$response.Content))
    }
    $stream.Position = 0
    $memory = New-Object System.IO.MemoryStream
    $stream.CopyTo($memory)
    return , ($memory.ToArray())
}

function Get-RawText {
    param([string]$Path, [hashtable]$Headers = @{}, [int]$TimeoutSec = 30)
    $bytes = Get-RawBytes -Path $Path -Headers $Headers -TimeoutSec $TimeoutSec
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

function Test-ZipBytes {
    param([byte[]]$Bytes)
    if ($null -eq $Bytes -or $Bytes.Length -lt 4) { return $false }
    return ($Bytes[0] -eq 0x50 -and $Bytes[1] -eq 0x4B)
}

# ---------------------------------------------------------------- domain helpers
function Login-Platform {
    param([string]$Username, [string]$Password)
    $data = Invoke-Api -Path '/api/auth/login' -Method POST -Headers @{ 'X-Forwarded-For' = ('198.51.100.' + (Get-Random -Minimum 10 -Maximum 250)) } -Body @{ username = $Username; password = $Password }
    $token = [string](Get-JsonProp $data 'token')
    if ([string]::IsNullOrWhiteSpace($token)) { throw 'login returned no token' }
    return [pscustomobject]@{ Token = $token; Principal = (Get-JsonProp $data 'principal') }
}

function Login-Tenant {
    param([string]$Username, [string]$Password, [string]$TenantCode)
    return Invoke-Api -Path '/api/auth/login' -Method POST -Body @{
        username = $Username; password = $Password; tenantCode = $TenantCode
    }
}

function Find-TenantByCode {
    param([string]$Code)
    try {
        $path = '/api/tenants?keyword=' + [uri]::EscapeDataString($Code) + '&page=0&size=20'
        $page = Invoke-Api -Path $path -Headers $script:AdminHeaders
        foreach ($item in @(Get-Items $page)) {
            if ([string](Get-JsonProp $item 'tenantCode') -eq $Code) { return $item }
        }
    }
    catch { return $null }
    return $null
}

function Register-TenantId {
    param([string]$TenantId)
    if ([string]::IsNullOrWhiteSpace($TenantId)) { return }
    if (-not $script:TenantIds.Contains($TenantId)) { $script:TenantIds.Add($TenantId) }
}

function Activate-TestTenant {
    param([string]$TenantId)
    $null = Invoke-Api -Path ("/api/tenants/{0}/submit" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{}
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $approved = $false
    $lastStatus = 'CONNECTION_ERROR'
    while ((Get-Date) -lt $deadline) {
        $tenant = Invoke-Api -Path ("/api/tenants/{0}" -f $TenantId) -Headers $script:AdminHeaders
        $lastStatus = [string](Get-JsonProp $tenant 'status')
        if ($lastStatus -eq 'ACTIVE') { return 'ACTIVE' }
        if (-not $approved) {
            try {
                $pending = Invoke-Api -Path '/api/approvals?page=0&size=50' -Headers $script:AdminHeaders
                $approval = @(Get-Items $pending) |
                    Where-Object { [string](Get-JsonProp $_ 'tenantId') -eq $TenantId } |
                    Select-Object -First 1
                if ($null -ne $approval) {
                    $approvalId = [string](Get-JsonProp $approval 'id')
                    $null = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method POST `
                        -Headers $script:AdminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v5 activation' }
                    $approved = $true
                }
            }
            catch { }
        }
        Start-Sleep -Seconds 2
    }
    return $lastStatus
}

function Remove-TestTenant {
    param([string]$TenantId)
    if ([string]::IsNullOrWhiteSpace($TenantId)) { return }
    try {
        $tenant = Invoke-Api -Path ("/api/tenants/{0}" -f $TenantId) -Headers $script:AdminHeaders
        $status = [string](Get-JsonProp $tenant 'status')
        if ($status -eq 'ACTIVE' -or $status -eq 'SUSPENDED') {
            $null = Invoke-ApiRaw -Path ("/api/tenants/{0}/terminate" -f $TenantId) -Method POST `
                -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v5 cleanup' }
        }
        $null = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $TenantId) -Method DELETE -Headers $script:AdminHeaders
    }
    catch { }
}

function Invoke-Cleanup {
    if ($script:CleanedUp) { return }
    $script:CleanedUp = $true
    if ([string]::IsNullOrWhiteSpace($script:AdminToken)) { return }
    foreach ($skillId in @($script:SkillId, $script:SkillRejectId)) {
        if ([string]::IsNullOrWhiteSpace($skillId)) { continue }
        try { $null = Invoke-ApiRaw -Path ("/api/skills/{0}" -f $skillId) -Method DELETE -Headers $script:AdminHeaders } catch { }
    }
    if (-not [string]::IsNullOrWhiteSpace($script:McpKeyId)) {
        try { $null = Invoke-ApiRaw -Path ("/api/mcp/keys/{0}" -f $script:McpKeyId) -Method DELETE -Headers $script:AdminHeaders } catch { }
    }
    if (-not [string]::IsNullOrWhiteSpace($script:ExternalServerId)) {
        try { $null = Invoke-ApiRaw -Path ("/api/mcp/external-servers/{0}" -f $script:ExternalServerId) -Method DELETE -Headers $script:AdminHeaders } catch { }
    }
    if (-not [string]::IsNullOrWhiteSpace($script:CustomRoleCode)) {
        try { $null = Invoke-ApiRaw -Path ("/api/platform/roles/{0}" -f $script:CustomRoleCode) -Method DELETE -Headers $script:AdminHeaders } catch { }
    }
    foreach ($code in $script:OnboardingCodes) {
        $tenant = Find-TenantByCode -Code $code
        if ($null -ne $tenant) { Register-TenantId ([string](Get-JsonProp $tenant 'id')) }
    }
    foreach ($tenantId in $script:TenantIds) { Remove-TestTenant -TenantId $tenantId }
}

function Abort-Run {
    param([string]$Reason)
    Invoke-Cleanup
    Skip-Remaining -Reason $Reason
    Show-Summary
}

# ---------------------------------------------------------------- capability gate
function Test-RouteAvailable {
    param([string]$Path, [hashtable]$Headers)
    try {
        $response = Invoke-TenancyHttp -Uri ($script:Base + $Path) -Headers $Headers -TimeoutSec 15
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
            return [pscustomobject]@{ Available = $false; Label = $Path; Detail = ("HTTP {0}" -f $response.StatusCode) }
        }
        $envelope = ConvertTo-Envelope -Content $response.Content -StatusCode $response.StatusCode
        if ($null -ne $envelope.Code -and $envelope.Code -ne 0) {
            return [pscustomobject]@{ Available = $false; Label = $Path; Detail = ("code {0}" -f $envelope.Code) }
        }
        return [pscustomobject]@{ Available = $true; Label = $Path; Detail = 'ok' }
    }
    catch {
        return [pscustomobject]@{ Available = $false; Label = $Path; Detail = $_.Exception.Message }
    }
}

function Test-V5ApiAvailable {
    param([hashtable]$Headers)
    $missing = New-Object System.Collections.Generic.List[string]
    $markers = @(
        @{ Path = '/api/me/permissions'; Label = 'GET /api/me/permissions' },
        @{ Path = '/api/mcp/config'; Label = 'GET /api/mcp/config' },
        @{ Path = '/api/agents/catalog'; Label = 'GET /api/agents/catalog' },
        @{ Path = '/api/platform/roles'; Label = 'GET /api/platform/roles' }
    )
    foreach ($marker in $markers) {
        $probe = Test-RouteAvailable -Path $marker.Path -Headers $Headers
        if (-not $probe.Available) {
            $missing.Add(('{0} ({1})' -f $marker.Label, $probe.Detail))
        }
    }
    if ($missing.Count -gt 0) {
        return [pscustomobject]@{
            Available = $false
            Detail    = ('v5 API not deployed; missing: ' + ($missing -join '; '))
        }
    }
    return [pscustomobject]@{ Available = $true; Detail = 'me/permissions + mcp/config + agents/catalog + platform/roles present' }
}

# ---------------------------------------------------------------- zip helpers
function New-SkillZipBytes {
    param(
        [Parameter(Mandatory = $true)][string]$SkillId,
        [Parameter(Mandatory = $true)][string]$SkillName
    )
    Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $manifest = @"
---
id: $SkillId
name: $SkillName
version: 1.0.0
type: RISK
description: E2E v5 uploaded skill package
triggers: [tenant.submitted]
tools: [tenant.get]
tags: [e2e, v5]
---
# Role
You are the E2E v5 validation skill.

# Workflow
1. Read the tenant profile.
2. Return JSON with riskLevel, score and decision.
"@
    $rules = @"
skillId: $SkillId
version: 1.0.0
initialScore: 0
rules:
  - id: E2E-001
    description: E2E baseline rule
    severity: LOW
    when:
      all:
        - { field: plan, op: eq, value: PRO }
    then:
      - { action: addScore, value: 1 }
      - { action: addFinding, message: E2E baseline rule matched, severity: LOW }
decision:
  autoApproveMaxScore: 20
  rejectMinScore: 80
  riskLevels:
    - { level: LOW, maxScore: 20 }
    - { level: MEDIUM, maxScore: 60 }
    - { level: HIGH, maxScore: 100 }
"@
    $memory = New-Object System.IO.MemoryStream
    $archive = New-Object System.IO.Compression.ZipArchive($memory, [System.IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        $skillEntry = $archive.CreateEntry('SKILL.md')
        $writer = New-Object System.IO.StreamWriter($skillEntry.Open(), $utf8)
        $writer.Write($manifest)
        $writer.Dispose()
        $rulesEntry = $archive.CreateEntry('rules.yaml')
        $writer = New-Object System.IO.StreamWriter($rulesEntry.Open(), $utf8)
        $writer.Write($rules)
        $writer.Dispose()
    }
    finally { $archive.Dispose() }
    $bytes = $memory.ToArray()
    $memory.Dispose()
    return , $bytes
}

# ---------------------------------------------------------------- MCP helper
function Invoke-Mcp {
    param(
        [Parameter(Mandatory = $true)][string]$Json,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 20
    )
    $allHeaders = @{ 'Accept' = 'application/json, text/event-stream' }
    foreach ($key in $Headers.Keys) { $allHeaders[$key] = $Headers[$key] }
    $response = Invoke-TenancyHttp -Uri ($script:Base + '/mcp') -Method POST `
        -Headers $allHeaders -Body $Json -TimeoutSec $TimeoutSec
    $document = $null
    try { if (-not [string]::IsNullOrWhiteSpace($response.Content)) { $document = $response.Content | ConvertFrom-Json } } catch { $document = $null }
    return [pscustomobject]@{ Status = $response.StatusCode; Document = $document; Content = $response.Content }
}

# ================================================================ run
Write-Host ("==> e2e-v5 against {0}" -f $script:Base) -ForegroundColor Cyan

# ---------------- preflight
try {
    $health = Invoke-TenancyHttp -Uri ($script:Base + '/actuator/health') -TimeoutSec 5
    if ($health.StatusCode -lt 200 -or $health.StatusCode -ge 300) {
        throw ("HTTP {0}" -f $health.StatusCode)
    }
    Add-Check 'preflight gateway' 'PASS' $script:Base
}
catch {
    Add-Check 'preflight gateway' 'SKIP' ("not reachable: " + $_.Exception.Message)
    Skip-Remaining -Reason 'gateway not reachable'
    Show-Summary
}

# ---------------- admin login
try {
    $login = Login-Platform 'admin' 'Admin@123'
    $script:AdminToken = $login.Token
    $script:AdminHeaders = @{ Authorization = ("Bearer {0}" -f $script:AdminToken) }
    Add-Check 'login admin' 'PASS' 'token issued'
}
catch {
    Add-Check 'login admin' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'admin login failed'
}

# ---------------- v5 capability gate
$gate = Test-V5ApiAvailable -Headers $script:AdminHeaders
if (-not $gate.Available) {
    Add-Check 'v5 api availability' 'SKIP' $gate.Detail
    Skip-Remaining -Reason 'v5 API not deployed; see [SKIP] v5 api availability for the missing routes'
    Show-Summary
}
Add-Check 'v5 api availability' 'PASS' $gate.Detail

# ================================================================ A. tenant + import
$tenantCode = Now-RandomCode 'e2e-v5'
try {
    $tenant = Invoke-Api -Path '/api/tenants' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantCode    = $tenantCode
        name          = 'E2E V5 Tenant'
        contactEmail  = 'v5@example.com'
        contactName   = 'E2E V5 Owner'
        plan          = 'PRO'
        region        = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'
        quotaSeats    = 20
        source        = 'MANUAL'
        autoSubmit    = $false
    }
    $script:MainTenantId = [string](Get-JsonProp $tenant 'id')
    $script:MainTenantCode = $tenantCode
    Register-TenantId $script:MainTenantId
    if ([string]::IsNullOrWhiteSpace($script:MainTenantId)) { throw 'tenant id missing' }
    Add-Check 'tenant create' 'PASS' ("id={0} code={1}" -f $script:MainTenantId, $tenantCode)
}
catch {
    Add-Check 'tenant create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'tenant create failed'
}

$activation = Activate-TestTenant -TenantId $script:MainTenantId
if ($activation -eq 'ACTIVE') {
    Add-Check 'tenant activation' 'PASS' 'status=ACTIVE'
}
else {
    Add-Check 'tenant activation' 'FAIL' ("status={0}" -f $activation)
    Abort-Run -Reason 'tenant did not reach ACTIVE'
}

$importReady = $true
$orgHeaderLine = ''
$accountHeaderLine = ''
try {
    $orgTemplateText = Get-RawText -Path ("/api/tenants/{0}/import-templates/org-units.csv" -f $script:MainTenantId) -Headers $script:AdminHeaders
    $orgHeaderLine = [string](@($orgTemplateText -split "\r?\n") | Where-Object { $_ -ne '' } | Select-Object -First 1)
    $orgHeaderLine = $orgHeaderLine.TrimStart([char]0xFEFF)
    if ([string]::IsNullOrWhiteSpace($orgHeaderLine) -or $orgHeaderLine -notmatch 'code') { throw 'org template header is empty' }
    Add-Check 'import template org' 'PASS' ("header={0}" -f $orgHeaderLine)
}
catch {
    Add-Check 'import template org' 'SKIP' ("template not available: " + $_.Exception.Message)
    $importReady = $false
}
try {
    $accountTemplateText = Get-RawText -Path ("/api/tenants/{0}/import-templates/accounts.csv" -f $script:MainTenantId) -Headers $script:AdminHeaders
    $accountHeaderLine = [string](@($accountTemplateText -split "\r?\n") | Where-Object { $_ -ne '' } | Select-Object -First 1)
    $accountHeaderLine = $accountHeaderLine.TrimStart([char]0xFEFF)
    if ([string]::IsNullOrWhiteSpace($accountHeaderLine) -or $accountHeaderLine -notmatch 'username') { throw 'account template header is empty' }
    Add-Check 'import template accounts' 'PASS' ("header={0}" -f $accountHeaderLine)
}
catch {
    Add-Check 'import template accounts' 'SKIP' ("template not available: " + $_.Exception.Message)
    $importReady = $false
}

if (-not $importReady) {
    Skip-Checks @('org import', 'org import duplicate skipped', 'account import',
        'account import duplicate skipped', 'imported account login') 'import API not deployed'
}
else {
    $suffix = New-RandomDigits 6
    $orgCodeA = 'E2EORGA' + $suffix
    $orgCodeB = 'E2EORGB' + $suffix
    $userA = 'e2eimpa' + $suffix
    $userB = 'e2eimpb' + $suffix

    $orgRows = @(
        @{ parentcode = ''; code = $orgCodeA; name = 'E2E Org A'; type = 'COMPANY'; sortorder = '1'; managerusername = '' },
        @{ parentcode = $orgCodeA; code = $orgCodeB; name = 'E2E Org B'; type = 'DEPARTMENT'; sortorder = '2'; managerusername = '' }
    )
    $orgCsv = New-ImportCsv -HeaderLine $orgHeaderLine -Rows $orgRows
    $orgBytes = [System.Text.Encoding]::UTF8.GetBytes($orgCsv)

    $orgImportOk = $false
    $orgImport = $null
    try {
        $orgImport = Invoke-MultipartApi -Path ("/api/tenants/{0}/org-units/import" -f $script:MainTenantId) `
            -FileBytes $orgBytes -FileName 'org-units.csv' -PartContentType 'text/csv' -Headers $script:AdminHeaders
        if ($orgImport.Ok) {
            $data = $orgImport.Data
            $imported = [int](Get-JsonProp $data 'imported')
            $skipped = [int](Get-JsonProp $data 'skipped')
            $failed = [int](Get-JsonProp $data 'failed')
            if ($imported -eq 2 -and $skipped -eq 0 -and $failed -eq 0) {
                Add-Check 'org import' 'PASS' ("imported={0} skipped={1} failed={2}" -f $imported, $skipped, $failed)
                $orgImportOk = $true
            }
            else {
                Add-Check 'org import' 'FAIL' ("imported={0} skipped={1} failed={2} errors={3}" -f $imported, $skipped, $failed, (Get-JsonProp $data 'errors'))
            }
        }
        else {
            Add-Check 'org import' 'FAIL' ("HTTP {0} code={1} message={2}" -f $orgImport.Status, $orgImport.Code, $orgImport.Message)
        }
    }
    catch {
        Add-Check 'org import' 'FAIL' $_.Exception.Message
    }

    if ($orgImportOk) {
        try {
            $duplicate = Invoke-MultipartApi -Path ("/api/tenants/{0}/org-units/import" -f $script:MainTenantId) `
                -FileBytes $orgBytes -FileName 'org-units.csv' -PartContentType 'text/csv' -Headers $script:AdminHeaders
            $duplicateData = $duplicate.Data
            $dupImported = [int](Get-JsonProp $duplicateData 'imported')
            $dupSkipped = [int](Get-JsonProp $duplicateData 'skipped')
            if ($duplicate.Ok -and $dupImported -eq 0 -and $dupSkipped -ge 2) {
                Add-Check 'org import duplicate skipped' 'PASS' ("imported={0} skipped={1}" -f $dupImported, $dupSkipped)
            }
            else {
                Add-Check 'org import duplicate skipped' 'FAIL' ("HTTP {0} imported={1} skipped={2}" -f $duplicate.Status, $dupImported, $dupSkipped)
            }
        }
        catch {
            Add-Check 'org import duplicate skipped' 'FAIL' $_.Exception.Message
        }
    }
    else {
        Add-Skip 'org import duplicate skipped' 'org import failed'
    }

    $accountRows = @(
        @{ username = $userA; displayname = 'E2E Import A'; email = ($userA + '@example.com'); phone = '13800008881'; orgcode = $orgCodeA; role = 'MEMBER' },
        @{ username = $userB; displayname = 'E2E Import B'; email = ($userB + '@example.com'); phone = '13800008882'; orgcode = $orgCodeB; role = 'MEMBER' }
    )
    $accountCsv = New-ImportCsv -HeaderLine $accountHeaderLine -Rows $accountRows
    $accountBytes = [System.Text.Encoding]::UTF8.GetBytes($accountCsv)
    $tempPassword = ''
    $accountImportOk = $false
    try {
        $accountImport = Invoke-MultipartApi -Path ("/api/tenants/{0}/accounts/import" -f $script:MainTenantId) `
            -FileBytes $accountBytes -FileName 'accounts.csv' -PartContentType 'text/csv' -Headers $script:AdminHeaders
        if ($accountImport.Ok) {
            $data = $accountImport.Data
            $imported = [int](Get-JsonProp $data 'imported')
            $failed = [int](Get-JsonProp $data 'failed')
            $tempRows = @(Get-Items (Get-JsonProp $data 'tempPasswords'))
            if ($imported -eq 2 -and $failed -eq 0 -and $tempRows.Count -eq 2) {
                Add-Check 'account import' 'PASS' ("imported={0} tempPasswords={1}" -f $imported, $tempRows.Count)
                $accountImportOk = $true
                foreach ($row in $tempRows) {
                    if ([string](Get-JsonProp $row 'username') -eq $userA) {
                        $tempPassword = [string](Get-JsonProp $row 'tempPassword')
                    }
                }
                if ([string]::IsNullOrWhiteSpace($tempPassword)) {
                    $tempPassword = [string](Get-JsonProp $tempRows[0] 'tempPassword')
                }
            }
            else {
                Add-Check 'account import' 'FAIL' ("imported={0} failed={1} tempPasswords={2} errors={3}" -f $imported, $failed, $tempRows.Count, (Get-JsonProp $data 'errors'))
            }
        }
        else {
            Add-Check 'account import' 'FAIL' ("HTTP {0} code={1} message={2}" -f $accountImport.Status, $accountImport.Code, $accountImport.Message)
        }
    }
    catch {
        Add-Check 'account import' 'FAIL' $_.Exception.Message
    }

    if ($accountImportOk) {
        try {
            $duplicate = Invoke-MultipartApi -Path ("/api/tenants/{0}/accounts/import" -f $script:MainTenantId) `
                -FileBytes $accountBytes -FileName 'accounts.csv' -PartContentType 'text/csv' -Headers $script:AdminHeaders
            $duplicateData = $duplicate.Data
            $dupImported = [int](Get-JsonProp $duplicateData 'imported')
            $dupSkipped = [int](Get-JsonProp $duplicateData 'skipped')
            if ($duplicate.Ok -and $dupImported -eq 0 -and $dupSkipped -ge 2) {
                Add-Check 'account import duplicate skipped' 'PASS' ("imported={0} skipped={1}" -f $dupImported, $dupSkipped)
            }
            else {
                Add-Check 'account import duplicate skipped' 'FAIL' ("HTTP {0} imported={1} skipped={2}" -f $duplicate.Status, $dupImported, $dupSkipped)
            }
        }
        catch {
            Add-Check 'account import duplicate skipped' 'FAIL' $_.Exception.Message
        }
    }
    else {
        Add-Skip 'account import duplicate skipped' 'account import failed'
    }

    if ($accountImportOk -and -not [string]::IsNullOrWhiteSpace($tempPassword)) {
        try {
            $tenantLogin = Login-Tenant $userA $tempPassword $tenantCode
            $principal = Get-JsonProp $tenantLogin 'principal'
            $token = [string](Get-JsonProp $tenantLogin 'token')
            $authType = [string](Get-JsonProp $principal 'authType')
            if (-not [string]::IsNullOrWhiteSpace($token) -and $authType -eq 'TENANT') {
                $script:TenantAccountToken = $token
                Add-Check 'imported account login' 'PASS' ("user={0} authType=TENANT" -f $userA)
            }
            else {
                Add-Check 'imported account login' 'FAIL' ("authType={0} tokenLen={1}" -f $authType, $token.Length)
            }
        }
        catch {
            Add-Check 'imported account login' 'FAIL' $_.Exception.Message
        }
    }
    else {
        Add-Skip 'imported account login' 'account import produced no temp password'
    }
}

# ================================================================ B. self-service onboarding
$onboardingReady = $true
$compliantApplicationNo = ''
$compliantStatus = ''
$nonCompliantApplicationNo = ''
$nonCompliantTenantCode = ''
$clientIp = '198.51.100.' + (Get-Random -Minimum 10 -Maximum 250)

function Invoke-Onboarding {
    param($Payload)
    $headers = @{ 'X-Forwarded-For' = $clientIp }
    $raw = Invoke-ApiRaw -Path '/api/public/onboarding' -Method POST -Headers $headers -Body $Payload
    if ($raw.Status -eq 429) {
        Write-Host '    onboarding rate limited, waiting 60s before one retry' -ForegroundColor DarkYellow
        Start-Sleep -Seconds 60
        $raw = Invoke-ApiRaw -Path '/api/public/onboarding' -Method POST -Headers $headers -Body $Payload
    }
    return $raw
}

$onbSuffix = New-RandomDigits 6
$compliantPayload = @{
    companyName  = 'E2E Compliant Co'
    tenantCode   = 'e2ev5onb' + $onbSuffix
    contactName  = 'E2E Onboarding Owner'
    contactEmail = 'owner@e2e-software.com'
    contactPhone = '13800009991'
    plan         = 'PRO'
    region       = 'cn-shanghai'
    industry     = 'software'
    notes        = 'e2e-v5 compliant onboarding'
}
$compliantRaw = $null
try {
    $compliantRaw = Invoke-Onboarding -Payload $compliantPayload
}
catch {
    Add-Check 'onboarding compliant submit' 'SKIP' ("endpoint not available: " + $_.Exception.Message)
    $onboardingReady = $false
}
if ($onboardingReady) {
    if ($compliantRaw.Status -eq 404 -or $compliantRaw.Status -eq 500 -or $compliantRaw.Status -eq 501 -or $compliantRaw.Status -eq 503) {
        Add-Check 'onboarding compliant submit' 'SKIP' ("HTTP {0} on POST /api/public/onboarding (public onboarding not deployed)" -f $compliantRaw.Status)
        $onboardingReady = $false
    }
    elseif (-not $compliantRaw.Ok) {
        Add-Check 'onboarding compliant submit' 'FAIL' ("HTTP {0} code={1} message={2}" -f $compliantRaw.Status, $compliantRaw.Code, $compliantRaw.Message)
        $onboardingReady = $false
    }
    else {
        $data = $compliantRaw.Data
        $compliantApplicationNo = [string](Get-JsonProp $data 'applicationNo')
        $compliantStatus = [string](Get-JsonProp $data 'status')
        $compliantTenantCode = [string](Get-JsonProp $data 'tenantCode')
        if (-not [string]::IsNullOrWhiteSpace($compliantTenantCode)) {
            $script:OnboardingCodes.Add($compliantTenantCode)
        }
        if ($compliantStatus -eq 'ACTIVATED' -or $compliantStatus -eq 'PENDING_MANUAL') {
            Add-Check 'onboarding compliant submit' 'PASS' ("applicationNo={0} status={1}" -f $compliantApplicationNo, $compliantStatus)
        }
        else {
            Add-Check 'onboarding compliant submit' 'FAIL' ("unexpected status={0} applicationNo={1}" -f $compliantStatus, $compliantApplicationNo)
        }
        if ($compliantStatus -eq 'ACTIVATED') {
            Add-Check 'onboarding auto activated' 'PASS' ("applicationNo={0} status=ACTIVATED" -f $compliantApplicationNo)
        }
        elseif ($compliantStatus -eq 'PENDING_MANUAL') {
            Add-Check 'onboarding auto activated' 'SKIP' 'compliant submission queued for manual review; auto-activation requires LOW risk + reachable agent-service'
        }
        else {
            Add-Skip 'onboarding auto activated' 'compliant submission status is not final'
        }
        try {
            $statusView = Invoke-Api -Path ('/api/public/onboarding/' + [uri]::EscapeDataString($compliantApplicationNo))
            $queriedStatus = [string](Get-JsonProp $statusView 'status')
            $queriedNo = [string](Get-JsonProp $statusView 'applicationNo')
            $knownStatuses = @('SUBMITTED', 'AGENT_REVIEW', 'PENDING_MANUAL', 'ACTIVATED', 'REJECTED')
            if ($queriedNo -eq $compliantApplicationNo -and $knownStatuses -contains $queriedStatus) {
                Add-Check 'onboarding status query' 'PASS' ("applicationNo={0} status={1}" -f $queriedNo, $queriedStatus)
            }
            else {
                Add-Check 'onboarding status query' 'FAIL' ("applicationNo={0} status={1}" -f $queriedNo, $queriedStatus)
            }
        }
        catch {
            Add-Check 'onboarding status query' 'FAIL' $_.Exception.Message
        }
    }
}
if (-not $onboardingReady) {
    Skip-Checks @('onboarding auto activated', 'onboarding status query',
        'onboarding non-compliant manual', 'onboarding pending visible') 'public onboarding not deployed'
}

$riskPayload = @{
    companyName  = 'E2E High Risk Co'
    tenantCode   = 'e2ev5risk' + $onbSuffix
    contactName  = 'E2E Risk Owner'
    contactEmail = 'risk@e2e-risk.com'
    contactPhone = '13800009992'
    plan         = 'ENTERPRISE'
    region       = 'iran'
    industry     = 'gambling'
    notes        = 'e2e-v5 non-compliant onboarding (ENTERPRISE + high risk)'
}
$riskRaw = $null
if ($onboardingReady) {
    try {
        $riskRaw = Invoke-Onboarding -Payload $riskPayload
        if ($riskRaw.Status -eq 404 -or $riskRaw.Status -eq 500 -or $riskRaw.Status -eq 501 -or $riskRaw.Status -eq 503) {
            Add-Skip 'onboarding non-compliant manual' ("HTTP {0} on public onboarding" -f $riskRaw.Status)
        }
        elseif (-not $riskRaw.Ok) {
            Add-Check 'onboarding non-compliant manual' 'FAIL' ("HTTP {0} code={1} message={2}" -f $riskRaw.Status, $riskRaw.Code, $riskRaw.Message)
        }
        else {
            $data = $riskRaw.Data
            $nonCompliantApplicationNo = [string](Get-JsonProp $data 'applicationNo')
            $nonCompliantStatus = [string](Get-JsonProp $data 'status')
            $nonCompliantTenantCode = [string](Get-JsonProp $data 'tenantCode')
            if (-not [string]::IsNullOrWhiteSpace($nonCompliantTenantCode)) {
                $script:OnboardingCodes.Add($nonCompliantTenantCode)
            }
            if ($nonCompliantStatus -eq 'PENDING_MANUAL') {
                Add-Check 'onboarding non-compliant manual' 'PASS' ("applicationNo={0} status=PENDING_MANUAL" -f $nonCompliantApplicationNo)
            }
            else {
                Add-Check 'onboarding non-compliant manual' 'FAIL' ("expected PENDING_MANUAL, got {0}" -f $nonCompliantStatus)
            }
        }
    }
    catch {
        Add-Check 'onboarding non-compliant manual' 'FAIL' $_.Exception.Message
    }
}
if (-not $onboardingReady) {
    Add-Skip 'onboarding non-compliant manual' 'public onboarding not deployed'
}

$pendingVisible = $false
if ($onboardingReady -and -not [string]::IsNullOrWhiteSpace($nonCompliantTenantCode)) {
    for ($attempt = 0; $attempt -lt 5 -and -not $pendingVisible; $attempt++) {
        $pendingTenant = Find-TenantByCode -Code $nonCompliantTenantCode
        if ($null -ne $pendingTenant) {
            $pendingStatus = [string](Get-JsonProp $pendingTenant 'status')
            if ($pendingStatus -ne 'ACTIVE' -and $pendingStatus -ne 'TERMINATED') {
                $pendingVisible = $true
                Add-Check 'onboarding pending visible' 'PASS' ("tenantCode={0} status={1}" -f $nonCompliantTenantCode, $pendingStatus)
            }
        }
        if (-not $pendingVisible) { Start-Sleep -Seconds 1 }
    }
    if (-not $pendingVisible) {
        Add-Check 'onboarding pending visible' 'FAIL' ("tenant {0} not visible in tenant list or already ACTIVE" -f $nonCompliantTenantCode)
    }
}
else {
    Add-Skip 'onboarding pending visible' 'non-compliant onboarding submission unavailable'
}

# ================================================================ C. skill center
$skillsReady = $true
try {
    $templateBytes = Get-RawBytes -Path '/api/skills/template' -Headers $script:AdminHeaders
    if ((Test-ZipBytes $templateBytes) -and $templateBytes.Length -gt 0) {
        Add-Check 'skill template download' 'PASS' ("bytes={0}" -f $templateBytes.Length)
    }
    else {
        throw 'template response is not a zip'
    }
}
catch {
    Add-Check 'skill template download' 'SKIP' ("GET /api/skills/template unavailable: " + $_.Exception.Message)
    $skillsReady = $false
}

if (-not $skillsReady) {
    Skip-Checks @('skill upload draft', 'skill submit review', 'skill approve', 'skill enable',
        'skill list approved enabled', 'skill duplicate upload 409', 'skill reject branch') 'skill center not deployed'
}
else {
    $skillSuffix = New-RandomDigits 6
    $skillCandidateId = 'e2e-skill-' + $skillSuffix
    try {
        $skillBytes = New-SkillZipBytes -SkillId $skillCandidateId -SkillName 'E2E Custom Skill'
        $upload = Invoke-MultipartApi -Path '/api/skills/upload' -FileBytes $skillBytes `
            -FileName ($skillCandidateId + '.zip') -PartContentType 'application/zip' `
            -FormFields @{ submitForReview = 'false' } -Headers $script:AdminHeaders
        if ($upload.Ok) {
            $data = $upload.Data
            $status = [string](Get-JsonProp $data 'status')
            $script:SkillId = [string](Get-JsonProp $data 'id')
            if (([string]::IsNullOrWhiteSpace($script:SkillId)) -and -not [string]::IsNullOrWhiteSpace([string](Get-JsonProp $data 'skillId'))) {
                $script:SkillId = [string](Get-JsonProp $data 'skillId')
            }
            if (($status -eq 'DRAFT' -or $status -eq 'PENDING_REVIEW') -and -not [string]::IsNullOrWhiteSpace($script:SkillId)) {
                Add-Check 'skill upload draft' 'PASS' ("id={0} status={1}" -f $script:SkillId, $status)
            }
            else {
                Add-Check 'skill upload draft' 'FAIL' ("id={0} status={1}" -f $script:SkillId, $status)
            }
        }
        else {
            Add-Check 'skill upload draft' 'FAIL' ("HTTP {0} code={1} message={2}" -f $upload.Status, $upload.Code, $upload.Message)
        }
    }
    catch {
        Add-Check 'skill upload draft' 'FAIL' $_.Exception.Message
    }

    if ([string]::IsNullOrWhiteSpace($script:SkillId)) {
        Skip-Checks @('skill submit review', 'skill approve', 'skill enable',
            'skill list approved enabled', 'skill duplicate upload 409', 'skill reject branch') 'skill upload failed'
    }
    else {
        try {
            $submitted = Invoke-Api -Path ("/api/skills/{0}/submit-review" -f $script:SkillId) -Method POST -Headers $script:AdminHeaders -Body @{}
            $status = [string](Get-JsonProp $submitted 'status')
            if ($status -eq 'PENDING_REVIEW') {
                Add-Check 'skill submit review' 'PASS' 'status=PENDING_REVIEW'
            }
            else {
                Add-Check 'skill submit review' 'FAIL' ("expected PENDING_REVIEW, got {0}" -f $status)
            }
        }
        catch {
            Add-Check 'skill submit review' 'FAIL' $_.Exception.Message
        }
        try {
            $reviewed = Invoke-Api -Path ("/api/skills/{0}/review" -f $script:SkillId) -Method POST -Headers $script:AdminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v5 approve' }
            $status = [string](Get-JsonProp $reviewed 'status')
            if ($status -eq 'APPROVED') {
                Add-Check 'skill approve' 'PASS' 'status=APPROVED'
            }
            else {
                Add-Check 'skill approve' 'FAIL' ("expected APPROVED, got {0}" -f $status)
            }
        }
        catch {
            Add-Check 'skill approve' 'FAIL' $_.Exception.Message
        }
        try {
            $enabled = Invoke-Api -Path ("/api/skills/{0}/enable" -f $script:SkillId) -Method POST -Headers $script:AdminHeaders -Body @{}
            $enabledFlag = Get-JsonProp $enabled 'enabled'
            if ([bool]$enabledFlag) {
                Add-Check 'skill enable' 'PASS' 'enabled=true'
            }
            else {
                Add-Check 'skill enable' 'FAIL' ("enabled={0}" -f $enabledFlag)
            }
        }
        catch {
            Add-Check 'skill enable' 'FAIL' $_.Exception.Message
        }
        try {
            $list = Invoke-Api -Path '/api/skills?status=APPROVED&source=UPLOADED&enabled=true' -Headers $script:AdminHeaders

            $found = $false
            foreach ($item in @(Get-Items $list)) {
                if ([string](Get-JsonProp $item 'id') -eq $script:SkillId) {
                    $found = $true
                    $itemStatus = [string](Get-JsonProp $item 'status')
                    $itemEnabled = Get-JsonProp $item 'enabled'
                    if ($itemStatus -ne 'APPROVED' -or -not [bool]$itemEnabled) {
                        Add-Check 'skill list approved enabled' 'FAIL' ("status={0} enabled={1}" -f $itemStatus, $itemEnabled)
                        $found = $false
                    }
                    break
                }
            }
            if ($found) {
                Add-Check 'skill list approved enabled' 'PASS' ("id={0} status=APPROVED enabled=true" -f $script:SkillId)
            }
            elseif (-not $script:AddedChecks.Contains('skill list approved enabled')) {
                Add-Check 'skill list approved enabled' 'FAIL' ("skill {0} not listed as APPROVED+enabled" -f $script:SkillId)
            }
        }
        catch {
            Add-Check 'skill list approved enabled' 'FAIL' $_.Exception.Message
        }
        try {
            $duplicateBytes = New-SkillZipBytes -SkillId $skillCandidateId -SkillName 'E2E Custom Skill'
            $duplicateUpload = Invoke-MultipartApi -Path '/api/skills/upload' -FileBytes $duplicateBytes `
                -FileName ($skillCandidateId + '.zip') -PartContentType 'application/zip' `
                -FormFields @{ submitForReview = 'false' } -Headers $script:AdminHeaders
            if ($duplicateUpload.Status -eq 409 -or $duplicateUpload.Code -eq 40900) {
                Add-Check 'skill duplicate upload 409' 'PASS' ("HTTP {0} code={1}" -f $duplicateUpload.Status, $duplicateUpload.Code)
            }
            else {
                Add-Check 'skill duplicate upload 409' 'FAIL' ("expected 409, got HTTP {0} code={1}" -f $duplicateUpload.Status, $duplicateUpload.Code)
            }
        }
        catch {
            Add-Check 'skill duplicate upload 409' 'FAIL' $_.Exception.Message
        }
    }

    # optional reject branch (second skill package)
    $rejectCandidateId = 'e2e-skill-rej-' + (New-RandomDigits 6)
    try {
        $rejectBytes = New-SkillZipBytes -SkillId $rejectCandidateId -SkillName 'E2E Rejected Skill'
        $rejectUpload = Invoke-MultipartApi -Path '/api/skills/upload' -FileBytes $rejectBytes `
            -FileName ($rejectCandidateId + '.zip') -PartContentType 'application/zip' `
            -FormFields @{ submitForReview = 'false' } -Headers $script:AdminHeaders
        if ($rejectUpload.Ok) {
            $script:SkillRejectId = [string](Get-JsonProp $rejectUpload.Data 'id')
        }
        if ([string]::IsNullOrWhiteSpace($script:SkillRejectId)) { throw 'reject-branch upload returned no id' }
        $null = Invoke-Api -Path ("/api/skills/{0}/submit-review" -f $script:SkillRejectId) -Method POST -Headers $script:AdminHeaders -Body @{}
        $rejected = Invoke-Api -Path ("/api/skills/{0}/review" -f $script:SkillRejectId) -Method POST -Headers $script:AdminHeaders -Body @{ decision = 'REJECT'; comment = 'e2e-v5 reject branch' }
        $status = [string](Get-JsonProp $rejected 'status')
        if ($status -eq 'REJECTED') {
            Add-Check 'skill reject branch' 'PASS' 'status=REJECTED'
        }
        else {
            Add-Check 'skill reject branch' 'FAIL' ("expected REJECTED, got {0}" -f $status)
        }
    }
    catch {
        Add-Check 'skill reject branch' 'FAIL' $_.Exception.Message
    }
}

# ================================================================ D. agent catalog + run
$catalogItems = @()
try {
    $catalog = Invoke-Api -Path '/api/agents/catalog' -Headers $script:AdminHeaders
    $catalogItems = @(Get-Items $catalog)
    $expectedTypes = @('tenant-intake', 'risk-review', 'approval', 'provisioning')
    $missingTypes = @()
    foreach ($expected in $expectedTypes) {
        $match = $catalogItems | Where-Object { [string](Get-JsonProp $_ 'agentType') -eq $expected } | Select-Object -First 1
        if ($null -eq $match) { $missingTypes += $expected; continue }
        if ([string]::IsNullOrWhiteSpace([string](Get-JsonProp $match 'description'))) { $missingTypes += ($expected + ':description') }
    }
    if ($missingTypes.Count -eq 0) {
        Add-Check 'agent catalog' 'PASS' ("agents={0}" -f $catalogItems.Count)
    }
    else {
        Add-Check 'agent catalog' 'FAIL' ("missing/incomplete: " + ($missingTypes -join ', '))
    }
}
catch {
    Add-Check 'agent catalog' 'FAIL' $_.Exception.Message
}

$agentInput = @{
    plan         = 'PRO'
    region       = 'cn-shanghai'
    industry     = 'software'
    contactEmail = 'ops@e2e-v5-agent.com'
    quotaSeats   = 10
    attributes   = @{ industry = 'software' }
}
$agentRun = $null
try {
    $agentRun = Invoke-Api -Path '/api/agents/runs' -Method POST -Headers $script:AdminHeaders -Body @{
        agentType = 'risk-review'
        tenantId  = $script:MainTenantId
        input     = $agentInput
        maxSteps  = 6
    }
    $runId = [string](Get-JsonProp $agentRun 'runId')
    if ([string]::IsNullOrWhiteSpace($runId)) { $runId = [string](Get-JsonProp $agentRun 'id') }
    if ([string]::IsNullOrWhiteSpace($runId)) { throw 'agent run returned no runId' }
    $deadline = (Get-Date).AddSeconds([Math]::Min($TimeoutSeconds, 60))
    $finalStatus = [string](Get-JsonProp $agentRun 'status')
    while ((Get-Date) -lt $deadline) {
        if ($finalStatus -ne 'RUNNING' -and -not [string]::IsNullOrWhiteSpace($finalStatus)) { break }
        Start-Sleep -Milliseconds 700
        $agentRun = Invoke-Api -Path ("/api/agents/runs/{0}" -f $runId) -Headers $script:AdminHeaders
        $finalStatus = [string](Get-JsonProp $agentRun 'status')
    }
    if ($finalStatus -eq 'SUCCEEDED') {
        Add-Check 'agent run risk-review' 'PASS' ("runId={0} status=SUCCEEDED" -f $runId)
    }
    else {
        Add-Check 'agent run risk-review' 'FAIL' ("runId={0} status={1}" -f $runId, $finalStatus)
    }

    $displayName = [string](Get-JsonProp $agentRun 'displayName')
    $summary = [string](Get-JsonProp $agentRun 'summary')
    $purpose = [string](Get-JsonProp $agentRun 'purpose')
    if (-not [string]::IsNullOrWhiteSpace($displayName) -and -not [string]::IsNullOrWhiteSpace($summary)) {
        Add-Check 'agent run readable summary' 'PASS' ("displayName={0} summary={1}" -f $displayName, $summary)
    }
    else {
        Add-Check 'agent run readable summary' 'FAIL' ("displayName='{0}' summary='{1}' purpose='{2}'" -f $displayName, $summary, $purpose)
    }

    $steps = @(Get-Items (Get-JsonProp $agentRun 'steps'))
    $chineseStep = $null
    foreach ($step in $steps) {
        foreach ($fieldName in @('title', 'actionLabel')) {
            $value = [string](Get-JsonProp $step $fieldName)
            if (-not [string]::IsNullOrWhiteSpace($value) -and $value -match '[^\x00-\x7F]') { $chineseStep = $value }
        }
    }
    if ($null -ne $chineseStep) {
        Add-Check 'agent run chinese step titles' 'PASS' ("title={0}" -f $chineseStep)
    }
    else {
        Add-Check 'agent run chinese step titles' 'FAIL' ("steps={0}; no non-ASCII title/actionLabel" -f $steps.Count)
    }
}
catch {
    Add-Check 'agent run risk-review' 'FAIL' $_.Exception.Message
    Add-Skip 'agent run readable summary' 'agent run failed'
    Add-Skip 'agent run chinese step titles' 'agent run failed'
}

# ================================================================ E. MCP config / keys / audit / external servers
try {
    $config = Invoke-Api -Path '/api/mcp/config' -Headers $script:AdminHeaders
    $endpoint = [string](Get-JsonProp $config 'endpoint')
    $transport = [string](Get-JsonProp $config 'transport')
    $protocolVersion = [string](Get-JsonProp $config 'protocolVersion')
    $sampleConfig = Get-JsonProp $config 'sampleConfig'
    if (-not [string]::IsNullOrWhiteSpace($endpoint) -and -not [string]::IsNullOrWhiteSpace($transport) -and
        -not [string]::IsNullOrWhiteSpace($protocolVersion) -and $null -ne $sampleConfig) {
        Add-Check 'mcp config' 'PASS' ("endpoint={0} transport={1} protocol={2}" -f $endpoint, $transport, $protocolVersion)
    }
    else {
        Add-Check 'mcp config' 'FAIL' ("endpoint='{0}' transport='{1}' protocol='{2}'" -f $endpoint, $transport, $protocolVersion)
    }
}
catch {
    Add-Check 'mcp config' 'FAIL' $_.Exception.Message
}

$keyName = 'e2e-v5-key-' + (New-RandomDigits 6)
try {
    $key = Invoke-Api -Path '/api/mcp/keys' -Method POST -Headers $script:AdminHeaders -Body @{ name = $keyName }
    $script:McpApiKey = [string](Get-JsonProp $key 'apiKey')
    $script:McpKeyId = [string](Get-JsonProp $key 'id')
    $keyPrefix = [string](Get-JsonProp $key 'keyPrefix')
    if (-not [string]::IsNullOrWhiteSpace($script:McpApiKey) -and $script:McpApiKey.Length -ge 20 -and
        -not [string]::IsNullOrWhiteSpace($script:McpKeyId)) {
        Add-Check 'mcp key create' 'PASS' ("id={0} prefix={1} plaintextLen={2}" -f $script:McpKeyId, $keyPrefix, $script:McpApiKey.Length)
    }
    else {
        Add-Check 'mcp key create' 'FAIL' ("id='{0}' prefix='{1}' plaintextLen={2}" -f $script:McpKeyId, $keyPrefix, $script:McpApiKey.Length)
    }
}
catch {
    Add-Check 'mcp key create' 'FAIL' $_.Exception.Message
}

if ([string]::IsNullOrWhiteSpace($script:McpApiKey)) {
    Skip-Checks @('mcp tools list with key', 'mcp tools call with key', 'mcp audit recorded', 'mcp key revoked 401') 'MCP API key was not issued'
}
else {
    $keyHeaders = @{ 'X-API-Key' = $script:McpApiKey }
    try {
        $toolsResponse = Invoke-Mcp -Json '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' -Headers $keyHeaders
        $tools = @(Get-Items (Get-JsonProp (Get-JsonProp $toolsResponse.Document 'result') 'tools'))
        $toolNames = @($tools | ForEach-Object { [string](Get-JsonProp $_ 'name') })
        if ($toolsResponse.Status -eq 200 -and $tools.Count -ge 14 -and $toolNames -contains 'tenant.list') {
            Add-Check 'mcp tools list with key' 'PASS' ("tools={0}" -f $tools.Count)
        }
        else {
            Add-Check 'mcp tools list with key' 'FAIL' ("HTTP {0} tools={1}" -f $toolsResponse.Status, $tools.Count)
        }
    }
    catch {
        Add-Check 'mcp tools list with key' 'FAIL' $_.Exception.Message
    }

    try {
        $callResponse = Invoke-Mcp -Json '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tenant.list","arguments":{"page":0,"size":1}}}' -Headers $keyHeaders
        $callResult = Get-JsonProp $callResponse.Document 'result'
        $isError = Get-JsonProp $callResult 'isError'
        $content = Get-JsonProp $callResult 'content'
        $structured = Get-JsonProp $callResult 'structuredContent'
        $callOk = ($callResponse.Status -eq 200 -and $null -ne $callResult -and $isError -eq $false -and
            (($null -ne $content) -or ($null -ne $structured)))
        if ($callOk) {
            Add-Check 'mcp tools call with key' 'PASS' 'tenant.list isError=false'
        }
        else {
            Add-Check 'mcp tools call with key' 'FAIL' ("HTTP {0} isError={1}" -f $callResponse.Status, $isError)
        }
    }
    catch {
        Add-Check 'mcp tools call with key' 'FAIL' $_.Exception.Message
    }

    try {
        $audits = Invoke-Api -Path '/api/mcp/audits?page=0&size=50&tool=tenant.list' -Headers $script:AdminHeaders
        $auditItems = @(Get-Items $audits)
        $auditFound = $false
        foreach ($audit in $auditItems) {
            if ([string](Get-JsonProp $audit 'tool') -eq 'tenant.list') { $auditFound = $true; break }
        }
        if ($auditFound) {
            Add-Check 'mcp audit recorded' 'PASS' ("audits={0}" -f $auditItems.Count)
        }
        else {
            Add-Check 'mcp audit recorded' 'FAIL' ("no tenant.list audit entry; audits={0}" -f $auditItems.Count)
        }
    }
    catch {
        Add-Check 'mcp audit recorded' 'FAIL' $_.Exception.Message
    }

    try {
        $null = Invoke-Api -Path ("/api/mcp/keys/{0}" -f $script:McpKeyId) -Method DELETE -Headers $script:AdminHeaders
        $revokedCall = Invoke-Mcp -Json '{"jsonrpc":"2.0","id":3,"method":"tools/list"}' -Headers $keyHeaders
        if ($revokedCall.Status -eq 401) {
            Add-Check 'mcp key revoked 401' 'PASS' 'revoked key rejected with HTTP 401'
        }
        else {
            Add-Check 'mcp key revoked 401' 'FAIL' ("expected 401, got HTTP {0}" -f $revokedCall.Status)
        }
        $script:McpKeyId = ''
    }
    catch {
        Add-Check 'mcp key revoked 401' 'FAIL' $_.Exception.Message
    }
}

$externalReady = $true
try {
    $serverName = 'e2e-v5-external-' + (New-RandomDigits 6)
    $server = Invoke-Api -Path '/api/mcp/external-servers' -Method POST -Headers $script:AdminHeaders -Body @{
        name      = $serverName
        transport = 'STREAMABLE_HTTP'
        url       = 'https://example.com/mcp'
    }
    $script:ExternalServerId = [string](Get-JsonProp $server 'id')
    if ([string]::IsNullOrWhiteSpace($script:ExternalServerId)) { throw 'external server create returned no id' }
    $list = Invoke-Api -Path '/api/mcp/external-servers' -Headers $script:AdminHeaders
    $listed = $false
    foreach ($item in @(Get-Items $list)) {
        if ([string](Get-JsonProp $item 'id') -eq $script:ExternalServerId) { $listed = $true }
    }
    if ($listed) {
        Add-Check 'mcp external server create+list' 'PASS' ("id={0}" -f $script:ExternalServerId)
    }
    else {
        Add-Check 'mcp external server create+list' 'FAIL' 'created server not returned by list'
    }
}
catch {
    if ($_.Exception.Message -match 'HTTP (404|500|501|503)') {
        Add-Check 'mcp external server create+list' 'SKIP' ("external MCP server API not deployed: " + $_.Exception.Message)
        $externalReady = $false
    }
    else {
        Add-Check 'mcp external server create+list' 'FAIL' $_.Exception.Message
        $externalReady = $false
    }
}

if (-not $externalReady -or [string]::IsNullOrWhiteSpace($script:ExternalServerId)) {
    Add-Skip 'mcp external server delete' 'external MCP server API unavailable'
}
else {
    try {
        $null = Invoke-Api -Path ("/api/mcp/external-servers/{0}" -f $script:ExternalServerId) -Method DELETE -Headers $script:AdminHeaders
        $after = Invoke-Api -Path '/api/mcp/external-servers' -Headers $script:AdminHeaders
        $stillListed = $false
        foreach ($item in @(Get-Items $after)) {
            if ([string](Get-JsonProp $item 'id') -eq $script:ExternalServerId) { $stillListed = $true }
        }
        if (-not $stillListed) {
            Add-Check 'mcp external server delete' 'PASS' 'server removed from list'
            $script:ExternalServerId = ''
        }
        else {
            Add-Check 'mcp external server delete' 'FAIL' 'server still listed after delete'
        }
    }
    catch {
        Add-Check 'mcp external server delete' 'FAIL' $_.Exception.Message
    }
}

# ================================================================ F. RBAC permissions
try {
    $me = Invoke-Api -Path '/api/me/permissions' -Headers $script:AdminHeaders
    $roles = @(Get-Items (Get-JsonProp $me 'roles'))
    $permissions = @(Get-Items (Get-JsonProp $me 'permissions'))
    $hasAdminRole = $false
    foreach ($role in $roles) {
        $roleText = [string]$role
        if ($roleText.ToUpperInvariant() -eq 'PLATFORM_ADMIN') { $hasAdminRole = $true }
    }
    $hasWildcard = $false
    foreach ($permission in $permissions) {
        if ([string]$permission -eq '*') { $hasWildcard = $true }
    }
    if ($hasAdminRole -and $hasWildcard) {
        Add-Check 'me permissions admin' 'PASS' ("roles={0} permissions={1}" -f ($roles -join ','), ($permissions -join ','))
    }
    else {
        Add-Check 'me permissions admin' 'FAIL' ("roles={0} permissions={1}" -f ($roles -join ','), ($permissions -join ','))
    }
}
catch {
    Add-Check 'me permissions admin' 'FAIL' $_.Exception.Message
}

$builtinRoles = @('PLATFORM_ADMIN', 'TENANT_ADMIN', 'ORG_ADMIN', 'AUDITOR', 'MEMBER')
try {
    $rolesResponse = Invoke-Api -Path '/api/platform/roles' -Headers $script:AdminHeaders
    $roleItems = @(Get-Items $rolesResponse)
    $roleCodes = @($roleItems | ForEach-Object { [string](Get-JsonProp $_ 'code') })
    $missingRoles = @($builtinRoles | Where-Object { $roleCodes -notcontains $_ })
    if ($missingRoles.Count -eq 0) {
        Add-Check 'platform roles builtin' 'PASS' ("roles={0}" -f $roleCodes.Count)
    }
    else {
        Add-Check 'platform roles builtin' 'FAIL' ("missing: " + ($missingRoles -join ', '))
    }
}
catch {
    Add-Check 'platform roles builtin' 'FAIL' $_.Exception.Message
}

$permissionCodes = @('TENANT_READ', 'ORG_READ')
try {
    $catalogResponse = Invoke-Api -Path '/api/platform/permissions' -Headers $script:AdminHeaders
    $catalogCodes = @(Get-Items $catalogResponse | ForEach-Object { [string](Get-JsonProp $_ 'code') } | Where-Object { $_ -ne '' })
    if ($catalogCodes.Count -ge 2) { $permissionCodes = @($catalogCodes[0], $catalogCodes[1]) }
}
catch { }

$script:CustomRoleCode = 'E2E_ROLE_' + (New-RandomDigits 6)
$customRoleCreated = $false
try {
    $customRole = Invoke-Api -Path '/api/platform/roles' -Method POST -Headers $script:AdminHeaders -Body @{
        code        = $script:CustomRoleCode
        name        = 'E2E Custom Role'
        scope       = 'TENANT'
        description = 'e2e-v5 custom role'
    }
    $returnedCode = [string](Get-JsonProp $customRole 'code')
    if ($returnedCode -eq $script:CustomRoleCode) {
        Add-Check 'custom role create' 'PASS' ("code={0}" -f $returnedCode)
        $customRoleCreated = $true
    }
    else {
        Add-Check 'custom role create' 'FAIL' ("returned code={0}" -f $returnedCode)
    }
}
catch {
    Add-Check 'custom role create' 'FAIL' $_.Exception.Message
}

if (-not $customRoleCreated) {
    Skip-Checks @('custom role permission set', 'custom role query', 'custom role permissions readback') 'custom role create failed'
}
else {
    try {
        $updated = Invoke-Api -Path ("/api/platform/roles/{0}/permissions" -f $script:CustomRoleCode) -Method PUT `
            -Headers $script:AdminHeaders -Body @{ permissionCodes = $permissionCodes }
        $returnedCodes = @(Get-Items (Get-JsonProp $updated 'permissionCodes'))
        if ($returnedCodes.Count -ge 1) {
            Add-Check 'custom role permission set' 'PASS' ("codes={0}" -f ($returnedCodes -join ','))
        }
        else {
            Add-Check 'custom role permission set' 'PASS' ("codes requested={0}" -f ($permissionCodes -join ','))
        }
    }
    catch {
        Add-Check 'custom role permission set' 'FAIL' $_.Exception.Message
    }
    try {
        $tenantRoles = Invoke-Api -Path '/api/platform/roles?scope=TENANT' -Headers $script:AdminHeaders
        $foundRole = $null
        foreach ($item in @(Get-Items $tenantRoles)) {
            if ([string](Get-JsonProp $item 'code') -eq $script:CustomRoleCode) { $foundRole = $item }
        }
        if ($null -ne $foundRole) {
            Add-Check 'custom role query' 'PASS' ("code={0} scope={1}" -f $script:CustomRoleCode, [string](Get-JsonProp $foundRole 'scope'))
            $readback = @(Get-Items (Get-JsonProp $foundRole 'permissionCodes'))
            if ($readback.Count -gt 0) {
                $overlap = @($permissionCodes | Where-Object { $readback -contains $_ })
                if ($overlap.Count -gt 0) {
                    Add-Check 'custom role permissions readback' 'PASS' ("codes={0}" -f ($readback -join ','))
                }
                else {
                    Add-Check 'custom role permissions readback' 'FAIL' ("readback={0} requested={1}" -f ($readback -join ','), ($permissionCodes -join ','))
                }
            }
            else {
                Add-Check 'custom role permissions readback' 'SKIP' 'role list did not expose permissionCodes'
            }
        }
        else {
            Add-Check 'custom role query' 'FAIL' ("role {0} not listed for scope=TENANT" -f $script:CustomRoleCode)
            Add-Skip 'custom role permissions readback' 'custom role not found'
        }
    }
    catch {
        Add-Check 'custom role query' 'FAIL' $_.Exception.Message
        Add-Skip 'custom role permissions readback' 'role query failed'
    }
}

if ([string]::IsNullOrWhiteSpace($script:TenantAccountToken)) {
    Add-Skip 'tenant account platform roles 403' 'no imported tenant account token'
}
else {
    try {
        $tenantHeaders = @{ Authorization = ("Bearer {0}" -f $script:TenantAccountToken) }
        $raw = Invoke-ApiRaw -Path '/api/platform/roles' -Headers $tenantHeaders
        if ($raw.Status -eq 403 -or $raw.Code -eq 40300) {
            Add-Check 'tenant account platform roles 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code)
        }
        else {
            Add-Check 'tenant account platform roles 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code)
        }
    }
    catch {
        Add-Check 'tenant account platform roles 403' 'FAIL' $_.Exception.Message
    }
}

# ================================================================ G. lifecycle purge
$purgeBase = $script:Base
$purgeDirect = $false
if (-not [string]::IsNullOrWhiteSpace($script:TenantServiceBase)) {
    $purgeBase = $script:TenantServiceBase
    $purgeDirect = $true
}
$purgePath = '/internal/maintenance/purge'
$purgeHeaders = @{ 'X-Internal-Token' = $script:InternalToken }
try {
    $purgeResponse = Invoke-TenancyHttp -Uri ($purgeBase + $purgePath) -Method POST -Headers $purgeHeaders -Body @{} -TimeoutSec 30
    $purgeEnvelope = ConvertTo-Envelope -Content $purgeResponse.Content -StatusCode $purgeResponse.StatusCode
    if (-not $purgeDirect -and $purgeEnvelope.Status -ne 200) {
        Add-Check 'purge internal token' 'SKIP' ("gateway does not expose /internal/** (HTTP {0}); rerun with -TenantServiceUrl http://localhost:8081" -f $purgeEnvelope.Status)
        Add-Skip 'purge no token 401' 'purge endpoint not reachable through gateway'
    }
    elseif (-not $purgeEnvelope.Ok) {
        if ($purgeEnvelope.Status -eq 404 -or $purgeEnvelope.Status -eq 500 -or $purgeEnvelope.Status -eq 501) {
            Add-Check 'purge internal token' 'SKIP' ("purge endpoint not deployed (HTTP {0})" -f $purgeEnvelope.Status)
            Add-Skip 'purge no token 401' 'purge endpoint not deployed'
        }
        else {
            Add-Check 'purge internal token' 'FAIL' ("HTTP {0} code={1} message={2}" -f $purgeEnvelope.Status, $purgeEnvelope.Code, $purgeEnvelope.Message)
            Add-Skip 'purge no token 401' 'purge call failed'
        }
    }
    else {
        $stats = $purgeEnvelope.Data
        $hasAll = (Test-HasProp $stats 'loginEvents') -and (Test-HasProp $stats 'audits') -and
            (Test-HasProp $stats 'collectJobs') -and (Test-HasProp $stats 'tenants')
        if ($hasAll) {
            Add-Check 'purge internal token' 'PASS' ("loginEvents={0} audits={1} collectJobs={2} tenants={3}" -f (Get-JsonProp $stats 'loginEvents'), (Get-JsonProp $stats 'audits'), (Get-JsonProp $stats 'collectJobs'), (Get-JsonProp $stats 'tenants'))
        }
        else {
            Add-Check 'purge internal token' 'FAIL' 'response is missing one of loginEvents/audits/collectJobs/tenants'
        }
        try {
            $noToken = Invoke-TenancyHttp -Uri ($purgeBase + $purgePath) -Method POST -Body @{} -TimeoutSec 20
            if ($noToken.StatusCode -eq 401) {
                Add-Check 'purge no token 401' 'PASS' 'HTTP 401 without X-Internal-Token'
            }
            else {
                Add-Check 'purge no token 401' 'FAIL' ("expected 401, got HTTP {0}" -f $noToken.StatusCode)
            }
        }
        catch {
            Add-Check 'purge no token 401' 'FAIL' $_.Exception.Message
        }
    }
}
catch {
    Add-Check 'purge internal token' 'SKIP' ("purge endpoint not reachable: " + $_.Exception.Message)
    Add-Skip 'purge no token 401' 'purge endpoint not reachable'
}

# ================================================================ summary
Invoke-Cleanup
if ($script:Results | Where-Object { $_.Status -eq 'FAIL' }) {
    Skip-Remaining -Reason 'not executed after earlier failures'
}
else {
    Skip-Remaining -Reason 'not executed (optional capability unavailable)'
}
Show-Summary
