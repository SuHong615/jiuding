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
    v3 E2E: tenant-account login + ORG_ADMIN / MEMBER / AUDITOR role matrix.

.DESCRIPTION
    Black-box test based on docs/13-v3-account-login-rbac.md sections 2/3/8:

        admin login -> create tenant + org tree (company / department / member department
        / sibling department) -> create ORG_ADMIN + MEMBER + lock-test accounts
        -> tenant-account login (tenantCode + username + password)
        -> ORG_ADMIN: own-subtree writes OK, outside-subtree 403, TENANT_ADMIN grant rejected,
           cross-tenant 403, tenant list scoped to own tenant
        -> MEMBER: account list only self, org read OK, all writes 403, rating read 403,
           tenant detail 403
        -> platform auditor: cross-tenant read OK, writes/delete 403
        -> wrong password 401 (uniform message); optional lockout after 5 failures

    Capability gate (old deployments stay green):
      1. gateway v3 marker: admin login principal contains authType (docs/13 section 2.3);
      2. v2 API present (ratingGrade + org/account routes) - needed to set up the tree;
      3. tenant login channel: after account setup, login with tenantCode must succeed.
    If a prerequisite is missing the remaining checks are SKIP with the missing capability.

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.
    Exit code: 0 = all pass/skip, 1 = at least one failure.

.EXAMPLE
    pwsh ./tests/e2e-v3.ps1
    powershell -File ./tests/e2e-v3.ps1 -BaseUrl http://localhost:30080
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$AdminUser = 'admin',
    [string]$AdminPassword = 'Admin@123',
    [string]$AuditorUser = 'auditor',
    [string]$AuditorPassword = 'Auditor@123',
    [string]$ApproverUser = 'approver',
    [string]$ApproverPassword = 'Approver@123',
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envScript = Join-Path $root 'scripts\env.ps1'
if (Test-Path $envScript) { . $envScript }

$script:Results = New-Object System.Collections.Generic.List[object]
$script:AddedChecks = New-Object System.Collections.Generic.List[string]
$script:AdminToken = $null
$script:AdminHeaders = @{}
$script:MainTenantId = $null
$script:MainTenantCode = $null
$script:OtherTenantId = $null
$script:CompanyId = $null
$script:DeptId = $null
$script:MemberDeptId = $null
$script:SiblingId = $null
$script:OrgAdminId = $null
$script:MemberAccountId = $null
$script:LockAccountId = $null
$script:CleanedUp = $false

$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'v3 api availability',
    'tenant create',
    'tenant submit',
    'tenant activate',
    'second tenant create',
    'org company create',
    'org department create',
    'member department create',
    'org sibling department create',
    'account ORG_ADMIN create',
    'account MEMBER create',
    'account locktest create',
    'tenant login ORG_ADMIN',
    'ORG_ADMIN principal fields',
    'JWT tid/oid claims',
    'ORG_ADMIN tenant list scoped',
    'ORG_ADMIN create sub-department',
    'ORG_ADMIN create account in subtree',
    'ORG_ADMIN create org outside subtree 403',
    'ORG_ADMIN create account outside subtree 403',
    'ORG_ADMIN grant TENANT_ADMIN rejected',
    'ORG_ADMIN cross-tenant 403',
    'member login',
    'MEMBER principal fields',
    'MEMBER account list only self',
    'MEMBER org read',
    'MEMBER org write 403',
    'MEMBER account create 403',
    'MEMBER rating read 403',
    'MEMBER tenant detail 403',
    'auditor login',
    'auditor cross-tenant tenant list',
    'auditor tenant detail',
    'auditor org read',
    'auditor org write 403',
    'auditor tenant delete 403',
    'wrong password tenant login 401',
    'wrong tenant/username unified 401',
    'lockout after 5 failures (optional)',
    'tenant cleanup'
)

function Add-Check {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$Detail = ''
    )
    $script:Results.Add([pscustomobject]@{ Check = $Name; Status = $Status; Detail = $Detail })
    $script:AddedChecks.Add($Name)
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } 'SKIP' { 'Yellow' } default { 'Gray' } }
    Write-Host ("[{0}] {1}{2}" -f $Status, $Name, $(if ($Detail) { " - $Detail" } else { '' })) -ForegroundColor $color
}

function Show-Summary {
    Write-Host ''
    Write-Host '---------------- e2e-v3 summary ----------------'
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

function Skip-Remaining {
    param([string]$Reason)
    foreach ($name in $script:PlannedChecks) {
        if ($script:AddedChecks -contains $name) { continue }
        Add-Check $name 'SKIP' $Reason
    }
}

# ---------------------------------------------------------------- HTTP helpers
function Invoke-TenancyHttp {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 20
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

function Invoke-ApiRaw {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 20
    )
    $response = Invoke-TenancyHttp -Uri ($BaseUrl.TrimEnd('/') + $Path) -Method $Method `
        -Headers $Headers -Body $Body -TimeoutSec $TimeoutSec
    $envelope = $null
    try { if ($response.Content) { $envelope = $response.Content | ConvertFrom-Json } } catch { $envelope = $null }
    $httpOk = ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300)
    $code = $null
    $data = $null
    $message = ''
    if ($null -ne $envelope -and $envelope.PSObject.Properties.Name -contains 'code') { $code = [int]$envelope.code }
    if ($null -ne $envelope -and $envelope.PSObject.Properties.Name -contains 'data') { $data = $envelope.data }
    if ($null -ne $envelope -and $envelope.PSObject.Properties.Name -contains 'message') { $message = [string]$envelope.message }
    $ok = ($httpOk -and ($null -eq $code -or $code -eq 0))
    return [pscustomobject]@{ Ok = $ok; Status = [int]$response.StatusCode; Code = $code; Data = $data; Message = $message; Content = $response.Content }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 20
    )
    $raw = Invoke-ApiRaw -Path $Path -Method $Method -Headers $Headers -Body $Body -TimeoutSec $TimeoutSec
    if (-not $raw.Ok) {
        throw ("HTTP {0} code={1} on {2}: {3}" -f $raw.Status, $raw.Code, $Path, $raw.Message)
    }
    return $raw.Data
}

# ---------------------------------------------------------------- JSON / misc helpers
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

function Decode-JwtPayload {
    param([string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) { throw 'empty token' }
    $parts = $Token.Split('.')
    if ($parts.Length -lt 2) { throw 'malformed JWT' }
    $payload = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) {
        2 { $payload += '==' }
        3 { $payload += '=' }
    }
    $bytes = [Convert]::FromBase64String($payload)
    return ([System.Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json)
}

function New-RandomCode {
    param([string]$Prefix)
    return ($Prefix + '-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' + (Get-Random -Minimum 10000 -Maximum 99999)).ToLowerInvariant()
}

function Test-Forbidden {
    param($Raw)
    return ($Raw.Status -eq 403 -or $Raw.Code -eq 40300)
}

function Test-ForbiddenOrInvalid {
    param($Raw)
    return ($Raw.Status -eq 403 -or $Raw.Status -eq 400 -or $Raw.Code -eq 40300 -or $Raw.Code -eq 40001)
}

function Find-ItemById {
    param($Items, [string]$Id)
    foreach ($item in @($Items)) {
        if ($null -ne $item -and [string](Get-JsonProp $item 'id') -eq $Id) { return $item }
    }
    return $null
}

function Login-Platform {
    param([string]$Username, [string]$Password)
    $data = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{ username = $Username; password = $Password }
    return [pscustomobject]@{ Token = [string](Get-JsonProp $data 'token'); Principal = (Get-JsonProp $data 'principal') }
}

function Login-Tenant {
    param([string]$Username, [string]$Password, [string]$TenantCode)
    return Invoke-Api -Path '/api/auth/login' -Method POST -Body @{
        username = $Username; password = $Password; tenantCode = $TenantCode
    }
}

function Get-LockoutStatus {
    param([string]$TenantId, [string]$AccountId)
    try {
        $account = Invoke-Api -Path ("/api/tenants/{0}/accounts/{1}" -f $TenantId, $AccountId) -Headers $script:AdminHeaders
        return [string](Get-JsonProp $account 'status')
    }
    catch { return '' }
}

function Set-AccountStatus {
    param([string]$TenantId, [string]$AccountId, [string]$Status)
    try {
        return Invoke-ApiRaw -Path ("/api/tenants/{0}/accounts/{1}/status" -f $TenantId, $AccountId) `
            -Method POST -Headers $script:AdminHeaders -Body @{ status = $Status; reason = 'e2e-v3 cleanup' }
    }
    catch { return $null }
}

function Remove-TestTenant {
    param([string]$TenantId)
    if ([string]::IsNullOrWhiteSpace($TenantId)) { return $null }
    try {
        $tenant = Invoke-Api -Path ("/api/tenants/{0}" -f $TenantId) -Headers $script:AdminHeaders
        $status = [string](Get-JsonProp $tenant 'status')
        if ($status -eq 'ACTIVE' -or $status -eq 'SUSPENDED') {
            Invoke-ApiRaw -Path ("/api/tenants/{0}/terminate" -f $TenantId) -Method POST `
                -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v3 cleanup' } | Out-Null
        }
        return Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $TenantId) -Method DELETE -Headers $script:AdminHeaders
    }
    catch { return $null }
}

function Invoke-Cleanup {
    if ($script:CleanedUp) { return }
    $script:CleanedUp = $true
    if (-not [string]::IsNullOrWhiteSpace($script:MainTenantId) -and -not [string]::IsNullOrWhiteSpace($script:AdminToken)) {
        if ($script:LockAccountId -and (Get-LockoutStatus -TenantId $script:MainTenantId -AccountId $script:LockAccountId) -eq 'LOCKED') {
            Set-AccountStatus -TenantId $script:MainTenantId -AccountId $script:LockAccountId -Status 'ACTIVE' | Out-Null
        }
        foreach ($tenantId in @($script:OtherTenantId, $script:MainTenantId)) {
            if ([string]::IsNullOrWhiteSpace($tenantId)) { continue }
            try {
                $result = Remove-TestTenant -TenantId $tenantId
                if ($null -ne $result -and $result.Ok) { Write-Host ("[cleanup] tenant {0} deleted" -f $tenantId) -ForegroundColor DarkGray }
                elseif ($null -ne $result -and $result.Status -eq 404) { Write-Host ("[cleanup] tenant {0} already deleted" -f $tenantId) -ForegroundColor DarkGray }
                else { Write-Host ("[cleanup] tenant {0} left in place" -f $tenantId) -ForegroundColor DarkYellow }
            }
            catch {
                Write-Host ("[cleanup] tenant {0} left in place: {1}" -f $tenantId, $_.Exception.Message) -ForegroundColor DarkYellow
            }
        }
    }
}

function Abort-Run {
    param([string]$Reason)
    Invoke-Cleanup
    Skip-Remaining -Reason $Reason
    Show-Summary
}

# ---------------------------------------------------------------- preflight + admin login
Write-Host ("==> e2e-v3 against {0}" -f $BaseUrl) -ForegroundColor Cyan
$gatewayUp = $false
try {
    $health = Invoke-TenancyHttp -Uri ($BaseUrl.TrimEnd('/') + '/actuator/health') -TimeoutSec 5
    $gatewayUp = ($health.StatusCode -ge 200 -and $health.StatusCode -lt 300)
}
catch { $gatewayUp = $false }
if (-not $gatewayUp) {
    Add-Check 'preflight gateway' 'SKIP' ("gateway not reachable at {0}; start services first" -f $BaseUrl)
    Skip-Remaining -Reason 'gateway down'
    Show-Summary
}
Add-Check 'preflight gateway' 'PASS' $BaseUrl

try {
    $adminLogin = Login-Platform -Username $AdminUser -Password $AdminPassword
    $script:AdminToken = $adminLogin.Token
    if ([string]::IsNullOrWhiteSpace($script:AdminToken)) { throw 'login response has no token' }
    $script:AdminHeaders = @{ Authorization = ("Bearer {0}" -f $script:AdminToken) }
    Add-Check 'login admin' 'PASS' ("user={0} roles={1}" -f (Get-JsonProp $adminLogin.Principal 'username'), ((Get-JsonProp $adminLogin.Principal 'roles') -join ','))
}
catch {
    Add-Check 'login admin' 'FAIL' $_.Exception.Message
    Skip-Remaining -Reason 'login failed'
    Show-Summary
}

# ---------------------------------------------------------------- v3 capability gate
$missing = New-Object System.Collections.Generic.List[string]
if (-not (Test-HasProp $adminLogin.Principal 'authType')) {
    $missing.Add('POST /api/auth/login principal.authType (gateway v3, docs/13 section 2.3)')
}
$listProbe = Invoke-ApiRaw -Path '/api/tenants?page=0&size=1' -Headers $script:AdminHeaders
$existingTenant = $null
if (-not $listProbe.Ok) {
    $missing.Add('GET /api/tenants')
}
else {
    $existingItems = @(Get-JsonProp $listProbe.Data 'items')
    if ($existingItems.Count -gt 0) { $existingTenant = $existingItems[0] }
}
$probeTenantId = [guid]::NewGuid().ToString()
if ($null -ne $existingTenant) {
    $existingId = [string](Get-JsonProp $existingTenant 'id')
    if (-not [string]::IsNullOrWhiteSpace($existingId)) { $probeTenantId = $existingId }
}
foreach ($probe in @(
        @{ Path = ("/api/tenants/{0}/org-units" -f $probeTenantId); Label = 'GET /api/tenants/{tenantId}/org-units' },
        @{ Path = ("/api/tenants/{0}/accounts?page=0&size=1" -f $probeTenantId); Label = 'GET /api/tenants/{tenantId}/accounts' },
        @{ Path = ("/api/tenants/{0}/ratings?page=0&size=1" -f $probeTenantId); Label = 'GET /api/tenants/{tenantId}/ratings' })) {
    $raw = Invoke-ApiRaw -Path $probe.Path -Headers $script:AdminHeaders
    if ($raw.Status -eq 0 -or $raw.Status -ge 500) { $missing.Add($probe.Label) }
}
if ($missing.Count -gt 0) {
    $uniqueMissing = @($missing | Select-Object -Unique)
    Add-Check 'v3 api availability' 'SKIP' ("v3 prerequisites not deployed; missing: {0}" -f ($uniqueMissing -join '; '))
    Abort-Run -Reason ("v3 prerequisites not deployed (missing: {0})" -f ($uniqueMissing -join '; '))
}
Add-Check 'v3 api availability' 'PASS' 'gateway v3 login marker + org/account/rating routes served'

# ---------------------------------------------------------------- tenant + org tree setup
$mainCode = New-RandomCode 'e2e-v3'
try {
    $tenant = Invoke-Api -Path '/api/tenants' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantCode = $mainCode; name = 'E2E V3 RBAC Tenant'; contactName = 'E2E Bot'
        contactEmail = 'e2e-v3@example.com'; plan = 'PRO'; region = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'; quotaSeats = 30; source = 'API'; autoSubmit = $false
    }
    $script:MainTenantId = [string](Get-JsonProp $tenant 'id')
    $script:MainTenantCode = $mainCode
    if ([string]::IsNullOrWhiteSpace($script:MainTenantId)) { throw 'create response has no id' }
    Add-Check 'tenant create' 'PASS' ("id={0} code={1}" -f $script:MainTenantId, $mainCode)
}
catch {
    Add-Check 'tenant create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'tenant create failed'
}
$tenantId = $script:MainTenantId

# Tenant-account authentication requires the tenant to be ACTIVE (docs/13 section 2.2).
# autoSubmit=false in the request -> submit explicitly; if the workflow creates a human
# approval, approve it with the preset approver and poll until ACTIVE.
try {
    $submitted = Invoke-Api -Path ("/api/tenants/{0}/submit" -f $tenantId) -Method POST -Headers $script:AdminHeaders -Body @{}
    $submitStatus = [string](Get-JsonProp $submitted 'status')
    if (@('SUBMITTED', 'RISK_REVIEW', 'PENDING_APPROVAL') -contains $submitStatus) {
        $approvalId = $null
        for ($attempt = 0; $attempt -lt 15 -and -not $approvalId; $attempt++) {
            $pending = Invoke-Api -Path '/api/approvals/pending' -Headers $script:AdminHeaders
            foreach ($item in @($pending)) {
                if ([string](Get-JsonProp $item 'tenantId') -eq $tenantId) {
                    $approvalId = [string](Get-JsonProp $item 'id')
                    break
                }
            }
            if (-not $approvalId) { Start-Sleep -Seconds 1 }
        }
        if (-not $approvalId) { throw 'no pending approval appeared for the tenant' }
        $approverLogin = Login-Platform -Username $ApproverUser -Password $ApproverPassword
        $approverHeaders = @{ Authorization = ("Bearer {0}" -f $approverLogin.Token) }
        $decision = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method POST -Headers $approverHeaders -Body @{
            decision = 'APPROVE'; comment = 'e2e-v3 automated approval'
        }
        $submitStatus = [string](Get-JsonProp $decision 'state')
    }
    Add-Check 'tenant submit' 'PASS' ("status={0}" -f $submitStatus)
}
catch {
    Add-Check 'tenant submit' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'tenant submit failed'
}

try {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastStatus = ''
    $active = $false
    while ((Get-Date) -lt $deadline) {
        $current = Invoke-Api -Path ("/api/tenants/{0}" -f $tenantId) -Headers $script:AdminHeaders
        $lastStatus = [string](Get-JsonProp $current 'status')
        if ($lastStatus -eq 'ACTIVE') { $active = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $active) { throw ("tenant is {0} after {1}s; tenant-account login requires ACTIVE" -f $lastStatus, $TimeoutSeconds) }
    Add-Check 'tenant activate' 'PASS' 'status=ACTIVE'
}
catch {
    Add-Check 'tenant activate' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'tenant did not reach ACTIVE'
}

try {
    $other = Invoke-Api -Path '/api/tenants' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantCode = (New-RandomCode 'e2e-v3-other'); name = 'E2E V3 Other Tenant'
        contactEmail = 'e2e-v3-other@example.com'; plan = 'PRO'; region = 'cn-shanghai'
        source = 'API'; autoSubmit = $false
    }
    $script:OtherTenantId = [string](Get-JsonProp $other 'id')
    if ([string]::IsNullOrWhiteSpace($script:OtherTenantId)) { throw 'second tenant response has no id' }
    Add-Check 'second tenant create' 'PASS' ("id={0}" -f $script:OtherTenantId)
}
catch {
    Add-Check 'second tenant create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'second tenant create failed'
}

$orgBase = "/api/tenants/$tenantId/org-units"
function New-OrgUnit {
    param([string]$ParentId, [string]$Code, [string]$Name, [string]$Type)
    $body = @{ code = $Code; name = $Name; type = $Type }
    if (-not [string]::IsNullOrWhiteSpace($ParentId)) { $body['parentId'] = $ParentId }
    return Invoke-Api -Path $orgBase -Method POST -Headers $script:AdminHeaders -Body $body
}

try {
    $company = New-OrgUnit -Code (New-RandomCode 'v3-company') -Name 'E2E V3 Company' -Type 'COMPANY'
    $script:CompanyId = [string](Get-JsonProp $company 'id')
    if ([string]::IsNullOrWhiteSpace($script:CompanyId)) { throw 'company response has no id' }
    Add-Check 'org company create' 'PASS' ("id={0}" -f $script:CompanyId)
}
catch {
    Add-Check 'org company create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'org setup failed'
}

try {
    $dept = New-OrgUnit -ParentId $script:CompanyId -Code (New-RandomCode 'v3-dept') -Name 'E2E V3 Department' -Type 'DEPARTMENT'
    $script:DeptId = [string](Get-JsonProp $dept 'id')
    if ([string]::IsNullOrWhiteSpace($script:DeptId)) { throw 'department response has no id' }
    Add-Check 'org department create' 'PASS' ("id={0}" -f $script:DeptId)
}
catch {
    Add-Check 'org department create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'org setup failed'
}

try {
    $memberDept = New-OrgUnit -ParentId $script:DeptId -Code (New-RandomCode 'v3-member-dept') -Name 'E2E V3 Member Department' -Type 'DEPARTMENT'
    $script:MemberDeptId = [string](Get-JsonProp $memberDept 'id')
    if ([string]::IsNullOrWhiteSpace($script:MemberDeptId)) { throw 'member department response has no id' }
    Add-Check 'member department create' 'PASS' ("id={0}" -f $script:MemberDeptId)
}
catch {
    Add-Check 'member department create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'org setup failed'
}

try {
    $sibling = New-OrgUnit -ParentId $script:CompanyId -Code (New-RandomCode 'v3-sibling') -Name 'E2E V3 Sibling Department' -Type 'DEPARTMENT'
    $script:SiblingId = [string](Get-JsonProp $sibling 'id')
    if ([string]::IsNullOrWhiteSpace($script:SiblingId)) { throw 'sibling department response has no id' }
    Add-Check 'org sibling department create' 'PASS' ("id={0}" -f $script:SiblingId)
}
catch {
    Add-Check 'org sibling department create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'org setup failed'
}

# ---------------------------------------------------------------- accounts
function New-Account {
    param([string]$Username, [string]$DisplayName, [string]$OrgUnitId, [string]$Role)
    $body = @{ username = $Username; displayName = $DisplayName; role = $Role }
    if (-not [string]::IsNullOrWhiteSpace($OrgUnitId)) { $body['orgUnitId'] = $OrgUnitId }
    return Invoke-Api -Path ("/api/tenants/{0}/accounts" -f $tenantId) -Method POST -Headers $script:AdminHeaders -Body $body
}

$orgAdminUsername = New-RandomCode 'v3.orgadmin'
$orgAdminPassword = $null
try {
    $result = New-Account -Username $orgAdminUsername -DisplayName 'E2E V3 Org Admin' -OrgUnitId $script:DeptId -Role 'ORG_ADMIN'
    $script:OrgAdminId = [string](Get-JsonProp (Get-JsonProp $result 'account') 'id')
    $orgAdminPassword = [string](Get-JsonProp $result 'tempPassword')
    if ([string]::IsNullOrWhiteSpace($script:OrgAdminId)) { throw 'ORG_ADMIN account id missing' }
    if ([string]::IsNullOrWhiteSpace($orgAdminPassword)) { throw 'ORG_ADMIN tempPassword missing' }
    Add-Check 'account ORG_ADMIN create' 'PASS' ("id={0}" -f $script:OrgAdminId)
}
catch {
    Add-Check 'account ORG_ADMIN create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'account setup failed'
}

$memberUsername = New-RandomCode 'v3.member'
$memberPassword = $null
try {
    $result = New-Account -Username $memberUsername -DisplayName 'E2E V3 Member' -OrgUnitId $script:MemberDeptId -Role 'MEMBER'
    $script:MemberAccountId = [string](Get-JsonProp (Get-JsonProp $result 'account') 'id')
    $memberPassword = [string](Get-JsonProp $result 'tempPassword')
    if ([string]::IsNullOrWhiteSpace($script:MemberAccountId)) { throw 'MEMBER account id missing' }
    if ([string]::IsNullOrWhiteSpace($memberPassword)) { throw 'MEMBER tempPassword missing' }
    Add-Check 'account MEMBER create' 'PASS' ("id={0}" -f $script:MemberAccountId)
}
catch {
    Add-Check 'account MEMBER create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'account setup failed'
}

$lockUsername = New-RandomCode 'v3.locktest'
try {
    $result = New-Account -Username $lockUsername -DisplayName 'E2E V3 Lock Test' -OrgUnitId $script:MemberDeptId -Role 'MEMBER'
    $script:LockAccountId = [string](Get-JsonProp (Get-JsonProp $result 'account') 'id')
    if ([string]::IsNullOrWhiteSpace($script:LockAccountId)) { throw 'lock-test account id missing' }
    Add-Check 'account locktest create' 'PASS' ("id={0}" -f $script:LockAccountId)
}
catch {
    Add-Check 'account locktest create' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'account setup failed'
}

# ---------------------------------------------------------------- tenant-account login (ORG_ADMIN)
try {
    $login = Login-Tenant -Username $orgAdminUsername -Password $orgAdminPassword -TenantCode $script:MainTenantCode
    $orgAdminToken = [string](Get-JsonProp $login 'token')
    $orgAdminPrincipal = Get-JsonProp $login 'principal'
    if ([string]::IsNullOrWhiteSpace($orgAdminToken)) { throw 'tenant login returned no token' }
    Add-Check 'tenant login ORG_ADMIN' 'PASS' ("user={0} token issued" -f (Get-JsonProp $orgAdminPrincipal 'username'))
}
catch {
    Add-Check 'tenant login ORG_ADMIN' 'SKIP' ("tenant login channel not deployed: {0}" -f $_.Exception.Message)
    Abort-Run -Reason 'tenant login channel not deployed (tenant-service /internal/auth/authenticate or gateway v3)'
}
$orgAdminHeaders = @{ Authorization = ("Bearer {0}" -f $orgAdminToken) }

try {
    $principal = $orgAdminPrincipal
    if ([string](Get-JsonProp $principal 'tenantId') -ne $script:MainTenantId) { throw 'principal.tenantId mismatch' }
    if ([string](Get-JsonProp $principal 'orgUnitId') -ne $script:DeptId) { throw 'principal.orgUnitId mismatch' }
    if (-not (@(Get-JsonProp $principal 'roles') -contains 'ORG_ADMIN')) { throw 'principal.roles missing ORG_ADMIN' }
    if ([string](Get-JsonProp $principal 'authType') -ne 'TENANT') { throw 'principal.authType should be TENANT' }
    if ((Get-JsonProp $principal 'mustChangePassword') -ne $true) { throw 'principal.mustChangePassword should be true' }
    Add-Check 'ORG_ADMIN principal fields' 'PASS' ("tenantId={0} orgUnitId={1} authType=TENANT" -f $script:MainTenantId, $script:DeptId)
}
catch {
    Add-Check 'ORG_ADMIN principal fields' 'FAIL' $_.Exception.Message
}

try {
    $claims = Decode-JwtPayload -Token $orgAdminToken
    if ([string](Get-JsonProp $claims 'tid') -ne $script:MainTenantId) { throw 'JWT tid claim mismatch' }
    if ([string](Get-JsonProp $claims 'oid') -ne $script:DeptId) { throw 'JWT oid claim mismatch' }
    Add-Check 'JWT tid/oid claims' 'PASS' ("tid={0} oid={1}" -f (Get-JsonProp $claims 'tid'), (Get-JsonProp $claims 'oid'))
}
catch {
    Add-Check 'JWT tid/oid claims' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- ORG_ADMIN scope matrix
try {
    $page = Invoke-Api -Path '/api/tenants?page=0&size=50' -Headers $orgAdminHeaders
    $items = @(Get-JsonProp $page 'items')
    if ($items.Count -lt 1) { throw 'ORG_ADMIN tenant list is empty (expected own tenant)' }
    $foreign = @($items | Where-Object { [string](Get-JsonProp $_ 'id') -ne $script:MainTenantId })
    if ($foreign.Count -gt 0) { throw 'ORG_ADMIN tenant list leaks other tenants' }
    Add-Check 'ORG_ADMIN tenant list scoped' 'PASS' ("items={0}" -f $items.Count)
}
catch {
    Add-Check 'ORG_ADMIN tenant list scoped' 'FAIL' $_.Exception.Message
}

$orgAdminSubDeptId = $null
try {
    $created = Invoke-Api -Path $orgBase -Method POST -Headers $orgAdminHeaders -Body @{
        parentId = $script:DeptId; code = (New-RandomCode 'v3-orgadmin-sub'); name = 'ORG_ADMIN Sub Department'; type = 'DEPARTMENT'
    }
    $orgAdminSubDeptId = [string](Get-JsonProp $created 'id')
    if ([string]::IsNullOrWhiteSpace($orgAdminSubDeptId)) { throw 'created sub-department has no id' }
    Add-Check 'ORG_ADMIN create sub-department' 'PASS' ("id={0}" -f $orgAdminSubDeptId)
}
catch {
    Add-Check 'ORG_ADMIN create sub-department' 'FAIL' $_.Exception.Message
}

try {
    $targetOrg = if ($orgAdminSubDeptId) { $orgAdminSubDeptId } else { $script:DeptId }
    $created = Invoke-Api -Path ("/api/tenants/{0}/accounts" -f $tenantId) -Method POST -Headers $orgAdminHeaders -Body @{
        username = (New-RandomCode 'v3.member2'); displayName = 'ORG_ADMIN Created Member'; role = 'MEMBER'; orgUnitId = $targetOrg
    }
    $createdId = [string](Get-JsonProp (Get-JsonProp $created 'account') 'id')
    if ([string]::IsNullOrWhiteSpace($createdId)) { throw 'account response has no id' }
    Add-Check 'ORG_ADMIN create account in subtree' 'PASS' ("id={0}" -f $createdId)
}
catch {
    Add-Check 'ORG_ADMIN create account in subtree' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path $orgBase -Method POST -Headers $orgAdminHeaders -Body @{
        parentId = $script:SiblingId; code = (New-RandomCode 'v3-outside'); name = 'Outside Subtree'; type = 'DEPARTMENT'
    }
    if (Test-Forbidden $raw) { Add-Check 'ORG_ADMIN create org outside subtree 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'ORG_ADMIN create org outside subtree 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'ORG_ADMIN create org outside subtree 403' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}/accounts" -f $tenantId) -Method POST -Headers $orgAdminHeaders -Body @{
        username = (New-RandomCode 'v3.outside'); displayName = 'Outside Subtree Account'; role = 'MEMBER'; orgUnitId = $script:SiblingId
    }
    if (Test-Forbidden $raw) { Add-Check 'ORG_ADMIN create account outside subtree 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'ORG_ADMIN create account outside subtree 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'ORG_ADMIN create account outside subtree 403' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}/accounts" -f $tenantId) -Method POST -Headers $orgAdminHeaders -Body @{
        username = (New-RandomCode 'v3.escalate'); displayName = 'Privilege Escalation Attempt'; role = 'TENANT_ADMIN'; orgUnitId = $script:DeptId
    }
    if (Test-ForbiddenOrInvalid $raw) { Add-Check 'ORG_ADMIN grant TENANT_ADMIN rejected' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'ORG_ADMIN grant TENANT_ADMIN rejected' 'FAIL' ("expected 403/400, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'ORG_ADMIN grant TENANT_ADMIN rejected' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}/org-units" -f $script:OtherTenantId) -Headers $orgAdminHeaders
    if (Test-Forbidden $raw) { Add-Check 'ORG_ADMIN cross-tenant 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'ORG_ADMIN cross-tenant 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'ORG_ADMIN cross-tenant 403' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- MEMBER matrix
try {
    $login = Login-Tenant -Username $memberUsername -Password $memberPassword -TenantCode $script:MainTenantCode
    $memberToken = [string](Get-JsonProp $login 'token')
    $memberPrincipal = Get-JsonProp $login 'principal'
    if ([string]::IsNullOrWhiteSpace($memberToken)) { throw 'tenant login returned no token' }
    Add-Check 'member login' 'PASS' ("user={0}" -f (Get-JsonProp $memberPrincipal 'username'))
}
catch {
    Add-Check 'member login' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'member login failed'
}
$memberHeaders = @{ Authorization = ("Bearer {0}" -f $memberToken) }

try {
    if ([string](Get-JsonProp $memberPrincipal 'tenantId') -ne $script:MainTenantId) { throw 'principal.tenantId mismatch' }
    if ([string](Get-JsonProp $memberPrincipal 'orgUnitId') -ne $script:MemberDeptId) { throw 'principal.orgUnitId mismatch' }
    if (-not (@(Get-JsonProp $memberPrincipal 'roles') -contains 'MEMBER')) { throw 'principal.roles missing MEMBER' }
    Add-Check 'MEMBER principal fields' 'PASS' ("orgUnitId={0} role=MEMBER" -f $script:MemberDeptId)
}
catch {
    Add-Check 'MEMBER principal fields' 'FAIL' $_.Exception.Message
}

try {
    $page = Invoke-Api -Path "/api/tenants/$tenantId/accounts?page=0&size=50" -Headers $memberHeaders
    $items = @(Get-JsonProp $page 'items')
    $total = Get-JsonProp $page 'total'
    if ([int]$total -ne 1) { throw ("expected total=1 (self only), got {0}" -f $total) }
    if ($items.Count -ne 1) { throw ("expected 1 item, got {0}" -f $items.Count) }
    if ([string](Get-JsonProp $items[0] 'id') -ne $script:MemberAccountId) { throw 'account list returned another account' }
    Add-Check 'MEMBER account list only self' 'PASS' ("total=1 username={0}" -f (Get-JsonProp $items[0] 'username'))
}
catch {
    Add-Check 'MEMBER account list only self' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ($orgBase + '?format=flat') -Headers $memberHeaders
    if (-not $raw.Ok) { throw ("expected 200, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    Add-Check 'MEMBER org read' 'PASS' 'read-only org tree accessible'
}
catch {
    Add-Check 'MEMBER org read' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path $orgBase -Method POST -Headers $memberHeaders -Body @{
        parentId = $script:MemberDeptId; code = (New-RandomCode 'v3-member-org'); name = 'Member Write Attempt'; type = 'DEPARTMENT'
    }
    if (Test-Forbidden $raw) { Add-Check 'MEMBER org write 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'MEMBER org write 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'MEMBER org write 403' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}/accounts" -f $tenantId) -Method POST -Headers $memberHeaders -Body @{
        username = (New-RandomCode 'v3.member-attempt'); displayName = 'Member Write Attempt'; role = 'MEMBER'; orgUnitId = $script:MemberDeptId
    }
    if (Test-Forbidden $raw) { Add-Check 'MEMBER account create 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'MEMBER account create 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'MEMBER account create 403' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}/ratings?page=0&size=1" -f $tenantId) -Headers $memberHeaders
    if (Test-Forbidden $raw) { Add-Check 'MEMBER rating read 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'MEMBER rating read 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'MEMBER rating read 403' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $tenantId) -Headers $memberHeaders
    if (Test-Forbidden $raw) { Add-Check 'MEMBER tenant detail 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'MEMBER tenant detail 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'MEMBER tenant detail 403' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- auditor (platform, cross-tenant read-only)
try {
    $auditorLogin = Login-Platform -Username $AuditorUser -Password $AuditorPassword
    $auditorToken = $auditorLogin.Token
    if ([string]::IsNullOrWhiteSpace($auditorToken)) { throw 'auditor login returned no token' }
    Add-Check 'auditor login' 'PASS' ("user={0} roles={1}" -f (Get-JsonProp $auditorLogin.Principal 'username'), ((Get-JsonProp $auditorLogin.Principal 'roles') -join ','))
}
catch {
    Add-Check 'auditor login' 'FAIL' $_.Exception.Message
    Abort-Run -Reason 'auditor login failed'
}
$auditorHeaders = @{ Authorization = ("Bearer {0}" -f $auditorToken) }

try {
    $page = Invoke-Api -Path '/api/tenants?page=0&size=50' -Headers $auditorHeaders
    $items = @(Get-JsonProp $page 'items')
    $ids = @($items | ForEach-Object { [string](Get-JsonProp $_ 'id') })
    if ($ids -notcontains $script:MainTenantId) { throw 'auditor cannot see the main tenant' }
    if ($ids -notcontains $script:OtherTenantId) { throw 'auditor cannot see the second tenant (cross-tenant read)' }
    Add-Check 'auditor cross-tenant tenant list' 'PASS' ("tenants={0}" -f $items.Count)
}
catch {
    Add-Check 'auditor cross-tenant tenant list' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:MainTenantId) -Headers $auditorHeaders
    if (-not $raw.Ok) { throw ("expected 200, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    Add-Check 'auditor tenant detail' 'PASS' 'cross-tenant detail readable'
}
catch {
    Add-Check 'auditor tenant detail' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ($orgBase + '?format=flat') -Headers $auditorHeaders
    if (-not $raw.Ok) { throw ("expected 200, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    Add-Check 'auditor org read' 'PASS' 'read-only org tree accessible'
}
catch {
    Add-Check 'auditor org read' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path $orgBase -Method POST -Headers $auditorHeaders -Body @{
        parentId = $script:CompanyId; code = (New-RandomCode 'v3-auditor-write'); name = 'Auditor Write Attempt'; type = 'DEPARTMENT'
    }
    if (Test-Forbidden $raw) { Add-Check 'auditor org write 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'auditor org write 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'auditor org write 403' 'FAIL' $_.Exception.Message
}

try {
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:MainTenantId) -Method DELETE -Headers $auditorHeaders
    if (Test-Forbidden $raw) { Add-Check 'auditor tenant delete 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
    else { Add-Check 'auditor tenant delete 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code) }
}
catch {
    Add-Check 'auditor tenant delete 403' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- wrong password / uniform 401 / optional lockout
try {
    $raw = Invoke-ApiRaw -Path '/api/auth/login' -Method POST -Body @{
        username = $orgAdminUsername; password = 'WrongPassword@123'; tenantCode = $script:MainTenantCode
    }
    if ($raw.Status -eq 401 -and $raw.Code -eq 40100) {
        Add-Check 'wrong password tenant login 401' 'PASS' ("HTTP 401 code=40100 message={0}" -f $raw.Message)
    }
    else {
        Add-Check 'wrong password tenant login 401' 'FAIL' ("expected 401/40100, got HTTP {0} code={1}" -f $raw.Status, $raw.Code)
    }
}
catch {
    Add-Check 'wrong password tenant login 401' 'FAIL' $_.Exception.Message
}

try {
    $a = Invoke-ApiRaw -Path '/api/auth/login' -Method POST -Body @{
        username = $orgAdminUsername; password = 'WrongPassword@123'; tenantCode = 'no-such-tenant-code'
    }
    $b = Invoke-ApiRaw -Path '/api/auth/login' -Method POST -Body @{
        username = 'no-such-user'; password = 'WrongPassword@123'; tenantCode = $script:MainTenantCode
    }
    if ($a.Status -eq 401 -and $b.Status -eq 401 -and $a.Message -eq $b.Message) {
        Add-Check 'wrong tenant/username unified 401' 'PASS' ("message={0}" -f $a.Message)
    }
    else {
        Add-Check 'wrong tenant/username unified 401' 'FAIL' ("a={0}/{1} b={2}/{3}" -f $a.Status, $a.Message, $b.Status, $b.Message)
    }
}
catch {
    Add-Check 'wrong tenant/username unified 401' 'FAIL' $_.Exception.Message
}

try {
    $lockPassword = 'WrongPassword@123'
    $statuses = @()
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $raw = Invoke-ApiRaw -Path '/api/auth/login' -Method POST -Body @{
            username = $lockUsername; password = $lockPassword; tenantCode = $script:MainTenantCode
        }
        $statuses += [int]$raw.Status
    }
    $lockStatus = Get-LockoutStatus -TenantId $tenantId -AccountId $script:LockAccountId
    $allRejected = -not (@($statuses | Where-Object { $_ -ne 401 }).Count -gt 0)
    if (-not $allRejected) {
        Add-Check 'lockout after 5 failures (optional)' 'FAIL' ("expected all 401, got {0}" -f ($statuses -join ','))
    }
    elseif ($lockStatus -eq 'LOCKED') {
        Add-Check 'lockout after 5 failures (optional)' 'PASS' 'account LOCKED after 5 failed attempts'
        Set-AccountStatus -TenantId $tenantId -AccountId $script:LockAccountId -Status 'ACTIVE' | Out-Null
    }
    else {
        Add-Check 'lockout after 5 failures (optional)' 'SKIP' ("lockout policy not enabled (final status='{0}'; feature is optional)" -f $lockStatus)
    }
}
catch {
    Add-Check 'lockout after 5 failures (optional)' 'SKIP' ("lockout probe failed: {0}" -f $_.Exception.Message)
}

# ---------------------------------------------------------------- cleanup
try {
    if ($script:LockAccountId -and (Get-LockoutStatus -TenantId $tenantId -AccountId $script:LockAccountId) -eq 'LOCKED') {
        Set-AccountStatus -TenantId $tenantId -AccountId $script:LockAccountId -Status 'ACTIVE' | Out-Null
    }
    $deleteMain = Remove-TestTenant -TenantId $script:MainTenantId
    $deleteOther = Remove-TestTenant -TenantId $script:OtherTenantId
    if ($null -eq $deleteMain -or (-not $deleteMain.Ok -and $deleteMain.Status -ne 404)) { throw 'main tenant cleanup failed' }
    if ($null -eq $deleteOther -or (-not $deleteOther.Ok -and $deleteOther.Status -ne 404)) { throw 'second tenant cleanup failed' }
    $script:CleanedUp = $true
    Add-Check 'tenant cleanup' 'PASS' 'test tenants soft-deleted'
}
catch {
    Add-Check 'tenant cleanup' 'SKIP' ("best-effort cleanup: {0}" -f $_.Exception.Message)
}

Show-Summary
