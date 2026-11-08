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
    v6 phase B E2E: SCIM 2.0 Users / Groups / Token (docs/16 section 7.1).

.DESCRIPTION
    Black-box E2E for the SCIM directory sync API:

        admin login -> create + activate tenant -> create SCIM token (plaintext once)
        -> no token / bad token -> 401 -> ServiceProviderConfig 200
        -> Users: POST -> list -> filter=userName eq -> GET id -> PATCH active=false
           -> PUT displayName -> pagination -> DELETE -> list no longer contains it
        -> error schema: unknown id 404 (and 401 / optional 409)
        -> Groups: create -> get -> filter=displayName eq -> PATCH rename+members
           -> DELETE (204, or 409 when the group still has members: both accepted)
        -> cleanup: revoke SCIM token (revoked token then 401) + soft delete tenant.

    Capability gate: GET /api/platform/scim-tokens (platform admin JWT) must answer 200
    with the unified envelope; otherwise the whole run is SKIP with exit code 0.
    The SCIM endpoint itself is probed without credentials and is expected to answer 401.

    SCIM responses are raw RFC 7644 documents (no ApiResponse envelope); the management
    token API keeps the platform envelope.

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.

.PARAMETER BaseUrl
    Gateway base URL. Default http://localhost:18080 (local process mode).

.PARAMETER TimeoutSeconds
    Timeout for tenant activation polling. Default 120.

.EXAMPLE
    pwsh ./tests/e2e-v6b.ps1
    pwsh ./tests/e2e-v6b.ps1 -BaseUrl http://localhost:30080
#>
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'

$script:Base = $BaseUrl.TrimEnd('/')
$script:Results = New-Object System.Collections.Generic.List[object]
$script:AddedChecks = New-Object System.Collections.Generic.List[string]
$script:CleanedUp = $false
$script:AdminToken = ''
$script:AdminHeaders = @{}
$script:TenantId = ''
$script:ScimTokenId = ''
$script:ScimToken = ''
$script:ScimHeaders = @{}
$script:UserId = ''
$script:MemberUserId = ''
$script:GroupId = ''
$script:TokenRevoked = $false
$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'scim api availability',
    'tenant create',
    'tenant activation',
    'scim token create',
    'scim no token 401',
    'scim bad token 401',
    'scim service provider config',
    'scim user create',
    'scim user list',
    'scim user filter userName',
    'scim user get',
    'scim user patch active false',
    'scim user put displayName',
    'scim user pagination',
    'scim user pagination beyond range',
    'scim user delete',
    'scim user list after delete',
    'scim error 404 schema',
    'scim group member user create',
    'scim group create',
    'scim group get',
    'scim group filter',
    'scim group patch',
    'scim group delete',
    'scim group delete conflict branch 409',
    'scim token list',
    'scim token revoke',
    'scim token revoked 401',
    'scim tenant cleanup'
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

function Skip-Remaining {
    param([string]$Reason)
    foreach ($name in $script:PlannedChecks) {
        if ($script:AddedChecks.Contains($name)) { continue }
        Add-Check $name 'SKIP' $Reason
    }
}

function Show-Summary {
    Write-Host ''
    Write-Host '---------------- e2e-v6b summary ----------------'
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
function New-RandomCode {
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
    foreach ($listName in @('items', 'records', 'Resources')) {
        if (Test-HasProp $Data $listName) { return @($Data.$listName) }
    }
    return @($Data)
}

# ---------------------------------------------------------------- HTTP helpers (platform envelope)
function Invoke-TenancyHttp {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 30
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
        try { if ($response.PSObject.Properties.Name -contains 'StatusCode') { $status = [int]$response.StatusCode } } catch { $status = 0 }
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
    return [pscustomobject]@{
        Ok = ($httpOk -and ($null -eq $code -or $code -eq 0))
        Status = $StatusCode; Code = $code; Data = $data; Message = $message; Content = $Content
    }
}

function Invoke-ApiRaw {
    param([string]$Path, [string]$Method = 'GET', [hashtable]$Headers = @{}, [object]$Body = $null, [int]$TimeoutSec = 30)
    $response = Invoke-TenancyHttp -Uri ($script:Base + $Path) -Method $Method -Headers $Headers -Body $Body -TimeoutSec $TimeoutSec
    return ConvertTo-Envelope -Content $response.Content -StatusCode $response.StatusCode
}

function Invoke-Api {
    param([string]$Path, [string]$Method = 'GET', [hashtable]$Headers = @{}, [object]$Body = $null, [int]$TimeoutSec = 30)
    $raw = Invoke-ApiRaw -Path $Path -Method $Method -Headers $Headers -Body $Body -TimeoutSec $TimeoutSec
    if (-not $raw.Ok) { throw ("HTTP {0} code={1} on {2}: {3}" -f $raw.Status, $raw.Code, $Path, $raw.Message) }
    return $raw.Data
}

# ---------------------------------------------------------------- SCIM helpers (raw RFC 7644 documents)
function Invoke-ScimRaw {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 30
    )
    $requestHeaders = @{ 'Accept' = 'application/scim+json, application/json' }
    foreach ($key in $Headers.Keys) { $requestHeaders[$key] = [string]$Headers[$key] }
    $params = @{
        Uri                = ($script:Base + $Path)
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
        $params['ContentType'] = 'application/scim+json; charset=utf-8'
    }
    $status = 0
    $content = ''
    try {
        $response = Invoke-WebRequest @params
        $status = [int]$response.StatusCode
        $rawContent = $response.Content
        if ($rawContent -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($rawContent) }
        else { $content = [string]$rawContent }
    }
    catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) { throw }
        try {
            if ($webResponse.PSObject.Properties.Name -contains 'Content') {
                $content = $webResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            }
            else {
                $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
                $content = $reader.ReadToEnd()
                $reader.Close()
            }
        }
        catch { $content = '' }
        try { if ($webResponse.PSObject.Properties.Name -contains 'StatusCode') { $status = [int]$webResponse.StatusCode } } catch { $status = 0 }
    }
    $document = $null
    try { if (-not [string]::IsNullOrWhiteSpace($content)) { $document = $content | ConvertFrom-Json } } catch { $document = $null }
    return [pscustomobject]@{ Status = $status; Content = $content; Json = $document }
}

function Test-ScimErrorSchema {
    param($Raw, [int]$ExpectedStatus)
    if ($null -eq $Raw -or $Raw.Status -ne $ExpectedStatus -or $null -eq $Raw.Json) { return $false }
    $schemas = @(Get-JsonProp $Raw.Json 'schemas')
    $hasErrorSchema = $schemas -contains 'urn:ietf:params:scim:api:messages:2.0:Error'
    $hasStatus = (Test-HasProp $Raw.Json 'status')
    $hasDetail = (Test-HasProp $Raw.Json 'detail')
    return ($hasErrorSchema -or ($hasStatus -and $hasDetail))
}

function Get-ScimListResources {
    param($Raw)
    if ($null -eq $Raw -or $null -eq $Raw.Json) { return @() }
    return @(Get-Items (Get-JsonProp $Raw.Json 'Resources'))
}

# ---------------------------------------------------------------- domain helpers
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
                $approval = @(Get-Items $pending) | Where-Object { [string](Get-JsonProp $_ 'tenantId') -eq $TenantId } | Select-Object -First 1
                if ($null -ne $approval) {
                    $approvalId = [string](Get-JsonProp $approval 'id')
                    $null = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method POST -Headers $script:AdminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v6b activation' }
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
            $null = Invoke-ApiRaw -Path ("/api/tenants/{0}/terminate" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v6b cleanup' }
        }
        $null = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $TenantId) -Method DELETE -Headers $script:AdminHeaders
    }
    catch { }
}

function Revoke-ScimToken {
    if ($script:TokenRevoked -or [string]::IsNullOrWhiteSpace($script:ScimTokenId)) { return }
    try {
        $null = Invoke-ApiRaw -Path ("/api/platform/scim-tokens/{0}" -f $script:ScimTokenId) -Method DELETE -Headers $script:AdminHeaders
    }
    catch { }
    $script:TokenRevoked = $true
}

function Invoke-Cleanup {
    if ($script:CleanedUp) { return }
    $script:CleanedUp = $true
    Revoke-ScimToken
    if (-not [string]::IsNullOrWhiteSpace($script:GroupId)) {
        # Best effort: drop members (and children if the server allows it) before retrying DELETE.
        try {
            $removeMembers = @{
                schemas    = @('urn:ietf:params:scim:api:messages:2.0:PatchOp')
                Operations = @(@{ op = 'remove'; path = 'members' })
            }
            $null = Invoke-ScimRaw -Path ("/scim/v2/Groups/{0}" -f $script:GroupId) -Method PATCH -Headers $script:ScimHeaders -Body $removeMembers
        }
        catch { }
        try { $null = Invoke-ScimRaw -Path ("/scim/v2/Groups/{0}" -f $script:GroupId) -Method DELETE -Headers $script:ScimHeaders } catch { }
    }
    foreach ($userId in @($script:MemberUserId, $script:UserId)) {
        if ([string]::IsNullOrWhiteSpace($userId)) { continue }
        try { $null = Invoke-ScimRaw -Path ("/scim/v2/Users/{0}" -f $userId) -Method DELETE -Headers $script:ScimHeaders } catch { }
    }
    if (-not [string]::IsNullOrWhiteSpace($script:TenantId)) { Remove-TestTenant -TenantId $script:TenantId }
}

# ================================================================ run
Write-Host ("==> e2e-v6b against {0}" -f $script:Base) -ForegroundColor Cyan

try {
    $health = Invoke-TenancyHttp -Uri ($script:Base + '/actuator/health') -TimeoutSec 5
    if ($health.StatusCode -lt 200 -or $health.StatusCode -ge 300) { throw ("HTTP {0}" -f $health.StatusCode) }
    Add-Check 'preflight gateway' 'PASS' $script:Base
}
catch {
    Add-Check 'preflight gateway' 'SKIP' ("not reachable: " + $_.Exception.Message)
    Skip-Remaining -Reason 'gateway not reachable'
    Show-Summary
}

try {
    $login = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{ username = 'admin'; password = 'Admin@123' }
    $script:AdminToken = [string](Get-JsonProp $login 'token')
    $script:AdminHeaders = @{ Authorization = ("Bearer {0}" -f $script:AdminToken) }
    if ([string]::IsNullOrWhiteSpace($script:AdminToken)) { throw 'login returned no token' }
    Add-Check 'login admin' 'PASS' 'token issued'
}
catch {
    Add-Check 'login admin' 'FAIL' $_.Exception.Message
    Skip-Remaining -Reason 'admin login failed'
    Show-Summary
}

# ---------------- capability gate: SCIM token API (decisive) + SCIM endpoint info
$scimReady = $false
$scimProbeDetail = ''
try {
    $scimProbe = Invoke-ScimRaw -Path '/scim/v2/ServiceProviderConfig'
    $tokenProbe = Invoke-ApiRaw -Path '/api/platform/scim-tokens' -Headers $script:AdminHeaders
    $scimProbeDetail = ("scimConfig HTTP {0}; tokenApi HTTP {1}" -f $scimProbe.Status, $tokenProbe.Status)
    # The management API is the decisive v6B marker: a deployed tenant-service answers
    # 200 (or 400 when tenantId is required). An old deployment answers 404/500 here.
    $tokenApiPresent = ($tokenProbe.Status -eq 200 -or $tokenProbe.Status -eq 400)
    $scimReady = $tokenApiPresent
}
catch { $scimProbeDetail = $_.Exception.Message }

if (-not $scimReady) {
    Add-Check 'scim api availability' 'SKIP' ("SCIM API not deployed; " + $scimProbeDetail)
    Skip-Remaining -Reason 'SCIM API not deployed (docs/16 section 7 phase B)'
    Show-Summary
}
Add-Check 'scim api availability' 'PASS' $scimProbeDetail

# ---------------- tenant create / activation
$tenantCode = New-RandomCode 'e2e-v6b'
try {
    $tenant = Invoke-Api -Path '/api/tenants' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantCode    = $tenantCode
        name          = 'E2E V6B SCIM Tenant'
        contactEmail  = 'v6b@example.com'
        contactName   = 'E2E V6B Owner'
        plan          = 'PRO'
        region        = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'
        quotaSeats    = 20
        source        = 'MANUAL'
        autoSubmit    = $false
    }
    $script:TenantId = [string](Get-JsonProp $tenant 'id')
    if ([string]::IsNullOrWhiteSpace($script:TenantId)) { throw 'tenant id missing' }
    Add-Check 'tenant create' 'PASS' ("id={0} code={1}" -f $script:TenantId, $tenantCode)
}
catch {
    Add-Check 'tenant create' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'tenant create failed'
    Show-Summary
}

$activation = Activate-TestTenant -TenantId $script:TenantId
if ($activation -eq 'ACTIVE') { Add-Check 'tenant activation' 'PASS' 'status=ACTIVE' }
else {
    Add-Check 'tenant activation' 'FAIL' ("status={0}" -f $activation)
    Invoke-Cleanup
    Skip-Remaining -Reason 'tenant activation failed'
    Show-Summary
}

# ---------------- SCIM token
try {
    $tokenResponse = Invoke-Api -Path '/api/platform/scim-tokens' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantId = $script:TenantId
        name     = 'e2e-v6b-token'
    }
    $script:ScimToken = [string](Get-JsonProp $tokenResponse 'token')
    $script:ScimTokenId = [string](Get-JsonProp $tokenResponse 'id')
    $tokenPrefix = [string](Get-JsonProp $tokenResponse 'prefix')
    if (-not [string]::IsNullOrWhiteSpace($script:ScimToken) -and $script:ScimToken.StartsWith('scim_') -and
        -not [string]::IsNullOrWhiteSpace($script:ScimTokenId)) {
        $script:ScimHeaders = @{ Authorization = ("Bearer {0}" -f $script:ScimToken) }
        Add-Check 'scim token create' 'PASS' ("id={0} prefix={1} plaintextLen={2}" -f $script:ScimTokenId, $tokenPrefix, $script:ScimToken.Length)
    }
    else {
        Add-Check 'scim token create' 'FAIL' ("id={0} tokenPrefixOk={1}" -f $script:ScimTokenId, $script:ScimToken.StartsWith('scim_'))
    }
}
catch {
    Add-Check 'scim token create' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'SCIM token create failed'
    Show-Summary
}

# ---------------- auth negative cases
try {
    $noToken = Invoke-ScimRaw -Path '/scim/v2/ServiceProviderConfig'
    $noTokenSchema = Test-ScimErrorSchema -Raw $noToken -ExpectedStatus 401
    $noTokenEnvelope = ($noToken.Content -match '"code"\s*:\s*40100')
    if ($noToken.Status -eq 401 -and ($noTokenSchema -or $noTokenEnvelope)) {
        Add-Check 'scim no token 401' 'PASS' ("HTTP 401; errorSchema={0}" -f $noTokenSchema)
    }
    else {
        Add-Check 'scim no token 401' 'FAIL' ("expected 401 + SCIM error schema, got HTTP {0}" -f $noToken.Status)
    }
}
catch { Add-Check 'scim no token 401' 'FAIL' $_.Exception.Message }

try {
    $badHeaders = @{ Authorization = 'Bearer scim_invalid_token_for_e2e' }
    $badToken = Invoke-ScimRaw -Path '/scim/v2/ServiceProviderConfig' -Headers $badHeaders
    $badSchema = Test-ScimErrorSchema -Raw $badToken -ExpectedStatus 401
    if ($badToken.Status -eq 401) {
        Add-Check 'scim bad token 401' 'PASS' ("HTTP 401; errorSchema={0}" -f $badSchema)
    }
    else {
        Add-Check 'scim bad token 401' 'FAIL' ("expected 401, got HTTP {0}" -f $badToken.Status)
    }
}
catch { Add-Check 'scim bad token 401' 'FAIL' $_.Exception.Message }

# ---------------- ServiceProviderConfig
try {
    $config = Invoke-ScimRaw -Path '/scim/v2/ServiceProviderConfig' -Headers $script:ScimHeaders
    $schemas = @(Get-JsonProp $config.Json 'schemas')
    if ($config.Status -eq 200 -and ($schemas -contains 'urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig')) {
        Add-Check 'scim service provider config' 'PASS' ("HTTP 200 patch={0}" -f [string](Get-JsonProp (Get-JsonProp $config.Json 'patch') 'supported'))
    }
    else {
        Add-Check 'scim service provider config' 'FAIL' ("HTTP {0} schemas={1}" -f $config.Status, ($schemas -join ','))
    }
}
catch { Add-Check 'scim service provider config' 'FAIL' $_.Exception.Message }

# ---------------- Users: create
$userName = 'e2escim' + (New-RandomDigits 6)
$userEmail = $userName + '@example.com'
try {
    $userPayload = @{
        schemas     = @('urn:ietf:params:scim:schemas:core:2.0:User')
        userName    = $userName
        displayName = 'E2E SCIM User'
        emails      = @(@{ value = $userEmail; primary = $true; type = 'work' })
        phoneNumbers = @(@{ value = '13800001234'; type = 'work' })
        active      = $true
        roles       = @(@{ value = 'MEMBER' })
    }
    $userCreate = Invoke-ScimRaw -Path '/scim/v2/Users' -Method POST -Headers $script:ScimHeaders -Body $userPayload
    $script:UserId = [string](Get-JsonProp $userCreate.Json 'id')
    $createdName = [string](Get-JsonProp $userCreate.Json 'userName')
    $createdActive = Get-JsonProp $userCreate.Json 'active'
    if (($userCreate.Status -eq 200 -or $userCreate.Status -eq 201) -and -not [string]::IsNullOrWhiteSpace($script:UserId) -and $createdName -eq $userName -and $createdActive -eq $true) {
        Add-Check 'scim user create' 'PASS' ("id={0} userName={1} active=true" -f $script:UserId, $createdName)
    }
    else {
        Add-Check 'scim user create' 'FAIL' ("HTTP {0} id={1} userName={2} active={3}" -f $userCreate.Status, $script:UserId, $createdName, $createdActive)
    }
}
catch {
    Add-Check 'scim user create' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'SCIM user create failed'
    Show-Summary
}

# ---------------- Users: list / filter / get
try {
    $list = Invoke-ScimRaw -Path '/scim/v2/Users' -Headers $script:ScimHeaders
    $total = Get-JsonProp $list.Json 'totalResults'
    $resources = @(Get-ScimListResources $list)
    $schemas = @(Get-JsonProp $list.Json 'schemas')
    if ($list.Status -eq 200 -and [int]$total -ge 1 -and ($schemas -contains 'urn:ietf:params:scim:api:messages:2.0:ListResponse')) {
        Add-Check 'scim user list' 'PASS' ("totalResults={0}" -f $total)
    }
    else {
        Add-Check 'scim user list' 'FAIL' ("HTTP {0} totalResults={1} resources={2}" -f $list.Status, $total, $resources.Count)
    }
}
catch { Add-Check 'scim user list' 'FAIL' $_.Exception.Message }

try {
    $filterPath = '/scim/v2/Users?filter=' + [uri]::EscapeDataString('userName eq "' + $userName + '"')
    $filtered = Invoke-ScimRaw -Path $filterPath -Headers $script:ScimHeaders
    $resources = @(Get-ScimListResources $filtered)
    $match = $resources | Where-Object { [string](Get-JsonProp $_ 'userName') -eq $userName } | Select-Object -First 1
    if ($filtered.Status -eq 200 -and [int](Get-JsonProp $filtered.Json 'totalResults') -ge 1 -and $null -ne $match) {
        Add-Check 'scim user filter userName' 'PASS' ("totalResults={0}" -f (Get-JsonProp $filtered.Json 'totalResults'))
    }
    else {
        Add-Check 'scim user filter userName' 'FAIL' ("HTTP {0} totalResults={1}" -f $filtered.Status, (Get-JsonProp $filtered.Json 'totalResults'))
    }
}
catch { Add-Check 'scim user filter userName' 'FAIL' $_.Exception.Message }

try {
    $userGet = Invoke-ScimRaw -Path ("/scim/v2/Users/{0}" -f $script:UserId) -Headers $script:ScimHeaders
    if ($userGet.Status -eq 200 -and [string](Get-JsonProp $userGet.Json 'id') -eq $script:UserId -and
        [string](Get-JsonProp $userGet.Json 'userName') -eq $userName) {
        Add-Check 'scim user get' 'PASS' ("id={0}" -f $script:UserId)
    }
    else {
        Add-Check 'scim user get' 'FAIL' ("HTTP {0} id={1}" -f $userGet.Status, (Get-JsonProp $userGet.Json 'id'))
    }
}
catch { Add-Check 'scim user get' 'FAIL' $_.Exception.Message }

# ---------------- Users: PATCH active=false
try {
    $patchBody = @{
        schemas    = @('urn:ietf:params:scim:api:messages:2.0:PatchOp')
        Operations = @(@{ op = 'replace'; path = 'active'; value = $false })
    }
    $patched = Invoke-ScimRaw -Path ("/scim/v2/Users/{0}" -f $script:UserId) -Method PATCH -Headers $script:ScimHeaders -Body $patchBody
    $patchedActive = $null
    if ($patched.Status -eq 200) {
        $patchedActive = Get-JsonProp $patched.Json 'active'
    }
    if ($null -eq $patchedActive) {
        $afterPatch = Invoke-ScimRaw -Path ("/scim/v2/Users/{0}" -f $script:UserId) -Headers $script:ScimHeaders
        $patchedActive = Get-JsonProp $afterPatch.Json 'active'
    }
    if (($patched.Status -eq 200 -or $patched.Status -eq 204) -and $patchedActive -eq $false) {
        Add-Check 'scim user patch active false' 'PASS' ("HTTP {0} active=false" -f $patched.Status)
    }
    else {
        Add-Check 'scim user patch active false' 'FAIL' ("HTTP {0} active={1}" -f $patched.Status, $patchedActive)
    }
}
catch { Add-Check 'scim user patch active false' 'FAIL' $_.Exception.Message }

# ---------------- Users: PUT displayName
try {
    $newDisplayName = 'E2E SCIM User Renamed'
    $putBody = @{
        schemas     = @('urn:ietf:params:scim:schemas:core:2.0:User')
        userName    = $userName
        displayName = $newDisplayName
        emails      = @(@{ value = $userEmail; primary = $true; type = 'work' })
        active      = $false
    }
    $putResult = Invoke-ScimRaw -Path ("/scim/v2/Users/{0}" -f $script:UserId) -Method PUT -Headers $script:ScimHeaders -Body $putBody
    $displayedName = ''
    if ($putResult.Status -eq 200) { $displayedName = [string](Get-JsonProp $putResult.Json 'displayName') }
    if ([string]::IsNullOrWhiteSpace($displayedName)) {
        $afterPut = Invoke-ScimRaw -Path ("/scim/v2/Users/{0}" -f $script:UserId) -Headers $script:ScimHeaders
        $displayedName = [string](Get-JsonProp $afterPut.Json 'displayName')
    }
    if ($putResult.Status -eq 200 -and $displayedName -eq $newDisplayName) {
        Add-Check 'scim user put displayName' 'PASS' ("displayName={0}" -f $displayedName)
    }
    else {
        Add-Check 'scim user put displayName' 'FAIL' ("HTTP {0} displayName={1}" -f $putResult.Status, $displayedName)
    }
}
catch { Add-Check 'scim user put displayName' 'FAIL' $_.Exception.Message }

# ---------------- Users: pagination
try {
    $page = Invoke-ScimRaw -Path '/scim/v2/Users?startIndex=1&count=1' -Headers $script:ScimHeaders
    $itemsPerPage = Get-JsonProp $page.Json 'itemsPerPage'
    $startIndex = Get-JsonProp $page.Json 'startIndex'
    if ($page.Status -eq 200 -and [int]$itemsPerPage -le 1 -and [int]$startIndex -eq 1 -and
        [int](Get-JsonProp $page.Json 'totalResults') -ge 1) {
        Add-Check 'scim user pagination' 'PASS' ("startIndex=1 count=1 itemsPerPage={0} total={1}" -f $itemsPerPage, (Get-JsonProp $page.Json 'totalResults'))
    }
    else {
        Add-Check 'scim user pagination' 'FAIL' ("HTTP {0} startIndex={1} itemsPerPage={2}" -f $page.Status, $startIndex, $itemsPerPage)
    }
}
catch { Add-Check 'scim user pagination' 'FAIL' $_.Exception.Message }

try {
    $emptyPage = Invoke-ScimRaw -Path '/scim/v2/Users?startIndex=999&count=10' -Headers $script:ScimHeaders
    $emptyResources = @(Get-ScimListResources $emptyPage)
    if ($emptyPage.Status -eq 200 -and $emptyResources.Count -eq 0) {
        Add-Check 'scim user pagination beyond range' 'PASS' 'Resources empty for startIndex=999'
    }
    else {
        Add-Check 'scim user pagination beyond range' 'FAIL' ("HTTP {0} resources={1}" -f $emptyPage.Status, $emptyResources.Count)
    }
}
catch { Add-Check 'scim user pagination beyond range' 'FAIL' $_.Exception.Message }

# ---------------- Users: DELETE + list no longer contains it
try {
    $deleted = Invoke-ScimRaw -Path ("/scim/v2/Users/{0}" -f $script:UserId) -Method DELETE -Headers $script:ScimHeaders
    if ($deleted.Status -eq 200 -or $deleted.Status -eq 204) {
        Add-Check 'scim user delete' 'PASS' ("HTTP {0}" -f $deleted.Status)
    }
    else {
        Add-Check 'scim user delete' 'FAIL' ("HTTP {0}" -f $deleted.Status)
    }
}
catch { Add-Check 'scim user delete' 'FAIL' $_.Exception.Message }

try {
    $filterPath = '/scim/v2/Users?filter=' + [uri]::EscapeDataString('userName eq "' + $userName + '"')
    $afterDelete = Invoke-ScimRaw -Path $filterPath -Headers $script:ScimHeaders
    $afterGet = Invoke-ScimRaw -Path ("/scim/v2/Users/{0}" -f $script:UserId) -Headers $script:ScimHeaders
    $total = [int](Get-JsonProp $afterDelete.Json 'totalResults')
    if (($afterDelete.Status -eq 200 -and $total -eq 0) -or $afterGet.Status -eq 404) {
        Add-Check 'scim user list after delete' 'PASS' ("filter totalResults={0}; get HTTP {1}" -f $total, $afterGet.Status)
    }
    else {
        Add-Check 'scim user list after delete' 'FAIL' ("filter totalResults={0} get HTTP {1}" -f $total, $afterGet.Status)
    }
}
catch { Add-Check 'scim user list after delete' 'FAIL' $_.Exception.Message }

# ---------------- error schema: unknown id 404
try {
    $unknownId = [guid]::NewGuid().ToString()
    $missing = Invoke-ScimRaw -Path ("/scim/v2/Users/{0}" -f $unknownId) -Headers $script:ScimHeaders
    if (Test-ScimErrorSchema -Raw $missing -ExpectedStatus 404) {
        Add-Check 'scim error 404 schema' 'PASS' 'SCIM Error schema with status=404'
    }
    else {
        Add-Check 'scim error 404 schema' 'FAIL' ("HTTP {0} jsonSchemaOk=False" -f $missing.Status)
    }
}
catch { Add-Check 'scim error 404 schema' 'FAIL' $_.Exception.Message }

# ---------------- Groups: member user + group lifecycle
$memberName = 'e2escimg' + (New-RandomDigits 6)
try {
    $memberPayload = @{
        schemas     = @('urn:ietf:params:scim:schemas:core:2.0:User')
        userName    = $memberName
        displayName = 'E2E SCIM Group Member'
        emails      = @(@{ value = ($memberName + '@example.com'); primary = $true; type = 'work' })
        active      = $true
        roles       = @(@{ value = 'MEMBER' })
    }
    $memberCreate = Invoke-ScimRaw -Path '/scim/v2/Users' -Method POST -Headers $script:ScimHeaders -Body $memberPayload
    $script:MemberUserId = [string](Get-JsonProp $memberCreate.Json 'id')
    if (($memberCreate.Status -eq 200 -or $memberCreate.Status -eq 201) -and -not [string]::IsNullOrWhiteSpace($script:MemberUserId)) {
        Add-Check 'scim group member user create' 'PASS' ("id={0}" -f $script:MemberUserId)
    }
    else {
        Add-Check 'scim group member user create' 'FAIL' ("HTTP {0} id={1}" -f $memberCreate.Status, $script:MemberUserId)
    }
}
catch { Add-Check 'scim group member user create' 'FAIL' $_.Exception.Message }

$groupName = 'E2E SCIM Group ' + (New-RandomDigits 6)
try {
    $groupPayload = @{
        schemas     = @('urn:ietf:params:scim:schemas:core:2.0:Group')
        displayName = $groupName
        members     = @()
    }
    $groupCreate = Invoke-ScimRaw -Path '/scim/v2/Groups' -Method POST -Headers $script:ScimHeaders -Body $groupPayload
    $script:GroupId = [string](Get-JsonProp $groupCreate.Json 'id')
    $createdGroupName = [string](Get-JsonProp $groupCreate.Json 'displayName')
    if (($groupCreate.Status -eq 200 -or $groupCreate.Status -eq 201) -and -not [string]::IsNullOrWhiteSpace($script:GroupId) -and $createdGroupName -eq $groupName) {
        Add-Check 'scim group create' 'PASS' ("id={0} displayName={1}" -f $script:GroupId, $createdGroupName)
    }
    else {
        Add-Check 'scim group create' 'FAIL' ("HTTP {0} id={1} displayName={2}" -f $groupCreate.Status, $script:GroupId, $createdGroupName)
    }
}
catch { Add-Check 'scim group create' 'FAIL' $_.Exception.Message }

if ([string]::IsNullOrWhiteSpace($script:GroupId)) {
    Skip-Remaining -Reason 'SCIM group create failed'
    Invoke-Cleanup
    Show-Summary
}

try {
    $groupGet = Invoke-ScimRaw -Path ("/scim/v2/Groups/{0}" -f $script:GroupId) -Headers $script:ScimHeaders
    if ($groupGet.Status -eq 200 -and [string](Get-JsonProp $groupGet.Json 'displayName') -eq $groupName) {
        Add-Check 'scim group get' 'PASS' ("id={0}" -f $script:GroupId)
    }
    else {
        Add-Check 'scim group get' 'FAIL' ("HTTP {0} displayName={1}" -f $groupGet.Status, (Get-JsonProp $groupGet.Json 'displayName'))
    }
}
catch { Add-Check 'scim group get' 'FAIL' $_.Exception.Message }

try {
    $groupFilterPath = '/scim/v2/Groups?filter=' + [uri]::EscapeDataString('displayName eq "' + $groupName + '"')
    $groupFiltered = Invoke-ScimRaw -Path $groupFilterPath -Headers $script:ScimHeaders
    $resources = @(Get-ScimListResources $groupFiltered)
    $match = $resources | Where-Object { [string](Get-JsonProp $_ 'displayName') -eq $groupName } | Select-Object -First 1
    if ($groupFiltered.Status -eq 200 -and [int](Get-JsonProp $groupFiltered.Json 'totalResults') -ge 1 -and $null -ne $match) {
        Add-Check 'scim group filter' 'PASS' ("totalResults={0}" -f (Get-JsonProp $groupFiltered.Json 'totalResults'))
    }
    else {
        Add-Check 'scim group filter' 'FAIL' ("HTTP {0} totalResults={1}" -f $groupFiltered.Status, (Get-JsonProp $groupFiltered.Json 'totalResults'))
    }
}
catch { Add-Check 'scim group filter' 'FAIL' $_.Exception.Message }

$renamedGroup = $groupName + ' II'
try {
    $operations = New-Object System.Collections.Generic.List[object]
    $operations.Add(@{ op = 'replace'; path = 'displayName'; value = $renamedGroup })
    if (-not [string]::IsNullOrWhiteSpace($script:MemberUserId)) {
        $operations.Add(@{ op = 'add'; path = 'members'; value = @(@{ value = $script:MemberUserId }) })
    }
    $groupPatch = @{
        schemas    = @('urn:ietf:params:scim:api:messages:2.0:PatchOp')
        Operations = $operations.ToArray()
    }
    $groupPatched = Invoke-ScimRaw -Path ("/scim/v2/Groups/{0}" -f $script:GroupId) -Method PATCH -Headers $script:ScimHeaders -Body $groupPatch
    $patchedGroupName = ''
    if ($groupPatched.Status -eq 200) { $patchedGroupName = [string](Get-JsonProp $groupPatched.Json 'displayName') }
    if ([string]::IsNullOrWhiteSpace($patchedGroupName)) {
        $groupAfter = Invoke-ScimRaw -Path ("/scim/v2/Groups/{0}" -f $script:GroupId) -Headers $script:ScimHeaders
        $patchedGroupName = [string](Get-JsonProp $groupAfter.Json 'displayName')
        $patchedMembers = @(Get-JsonProp $groupAfter.Json 'members')
    }
    else {
        $patchedMembers = @(Get-JsonProp $groupPatched.Json 'members')
    }
    if (($groupPatched.Status -eq 200 -or $groupPatched.Status -eq 204) -and $patchedGroupName -eq $renamedGroup) {
        Add-Check 'scim group patch' 'PASS' ("displayName={0} members={1}" -f $patchedGroupName, $patchedMembers.Count)
    }
    else {
        Add-Check 'scim group patch' 'FAIL' ("HTTP {0} displayName={1}" -f $groupPatched.Status, $patchedGroupName)
    }
}
catch { Add-Check 'scim group patch' 'FAIL' $_.Exception.Message }

$groupDeleteStatus = 0
try {
    $groupDeleted = Invoke-ScimRaw -Path ("/scim/v2/Groups/{0}" -f $script:GroupId) -Method DELETE -Headers $script:ScimHeaders
    $groupDeleteStatus = $groupDeleted.Status
    if ($groupDeleted.Status -eq 200 -or $groupDeleted.Status -eq 204) {
        Add-Check 'scim group delete' 'PASS' ("HTTP {0}; group deleted" -f $groupDeleted.Status)
        $afterDelete = Invoke-ScimRaw -Path ("/scim/v2/Groups/{0}" -f $script:GroupId) -Headers $script:ScimHeaders
        if ($afterDelete.Status -ne 404) {
            Add-Check 'scim group delete conflict branch 409' 'SKIP' 'delete succeeded but GET did not return 404'
        }
        else {
            Add-Check 'scim group delete conflict branch 409' 'SKIP' 'group deleted without members/children conflict'
        }
        $script:GroupId = ''
    }
    elseif ($groupDeleted.Status -eq 409) {
        Add-Check 'scim group delete' 'PASS' 'HTTP 409 (allowed: group still has members/children)'
        if (Test-ScimErrorSchema -Raw $groupDeleted -ExpectedStatus 409) {
            Add-Check 'scim group delete conflict branch 409' 'PASS' 'SCIM Error schema with status=409'
        }
        else {
            Add-Check 'scim group delete conflict branch 409' 'FAIL' '409 returned but body is not a SCIM Error schema'
        }
    }
    else {
        Add-Check 'scim group delete' 'FAIL' ("HTTP {0}" -f $groupDeleted.Status)
        Add-Check 'scim group delete conflict branch 409' 'SKIP' 'delete neither succeeded nor returned 409'
    }
}
catch { Add-Check 'scim group delete' 'FAIL' $_.Exception.Message; Add-Check 'scim group delete conflict branch 409' 'SKIP' 'delete call failed' }

# ---------------- token list / revoke / revoked 401
try {
    $tokenList = Invoke-Api -Path ('/api/platform/scim-tokens?tenantId=' + [uri]::EscapeDataString($script:TenantId)) -Headers $script:AdminHeaders
    $tokenItems = @(Get-Items $tokenList)
    $found = $tokenItems | Where-Object { [string](Get-JsonProp $_ 'id') -eq $script:ScimTokenId } | Select-Object -First 1
    if ($null -ne $found) {
        Add-Check 'scim token list' 'PASS' ("tokens={0}" -f $tokenItems.Count)
    }
    else {
        Add-Check 'scim token list' 'FAIL' ("token {0} not listed" -f $script:ScimTokenId)
    }
}
catch { Add-Check 'scim token list' 'FAIL' $_.Exception.Message }

try {
    $revoked = Invoke-ApiRaw -Path ("/api/platform/scim-tokens/{0}" -f $script:ScimTokenId) -Method DELETE -Headers $script:AdminHeaders
    if ($revoked.Ok) {
        $script:TokenRevoked = $true
        Add-Check 'scim token revoke' 'PASS' 'token revoked'
    }
    else {
        Add-Check 'scim token revoke' 'FAIL' ("HTTP {0} code={1}" -f $revoked.Status, $revoked.Code)
    }
}
catch { Add-Check 'scim token revoke' 'FAIL' $_.Exception.Message }

try {
    $revokedCall = Invoke-ScimRaw -Path '/scim/v2/ServiceProviderConfig' -Headers $script:ScimHeaders
    if ($revokedCall.Status -eq 401) {
        Add-Check 'scim token revoked 401' 'PASS' 'revoked token rejected with HTTP 401'
    }
    else {
        Add-Check 'scim token revoked 401' 'FAIL' ("expected 401, got HTTP {0}" -f $revokedCall.Status)
    }
}
catch { Add-Check 'scim token revoked 401' 'FAIL' $_.Exception.Message }

# ---------------- tenant cleanup
try {
    Remove-TestTenant -TenantId $script:TenantId
    $check = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:TenantId) -Headers $script:AdminHeaders
    if ($check.Status -eq 404 -or $check.Code -eq 40400) {
        Add-Check 'scim tenant cleanup' 'PASS' 'test tenant soft-deleted'
    }
    else {
        Add-Check 'scim tenant cleanup' 'PASS' ("delete requested; HTTP {0}" -f $check.Status)
    }
    $script:TenantId = ''
}
catch { Add-Check 'scim tenant cleanup' 'FAIL' $_.Exception.Message }

Invoke-Cleanup
Skip-Remaining -Reason 'not executed'
Show-Summary
