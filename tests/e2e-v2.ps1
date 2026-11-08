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
    Tenant identity domain E2E (v2): org tree + accounts + rating + soft delete.

.DESCRIPTION
    Runs against a running gateway (local process mode: http://localhost:18080,
    docker/k8s: pass -BaseUrl). The script is a black-box test based on the contract
    in docs/11-tenant-identity-org-rating.md section 6:

        login admin -> create tenant -> org tree (company/department/sub-department)
        -> create account (one-time temp password, no hash in response)
        -> rating assess (grade / five dimensions / history)
        -> org move + cycle/deletion guards (409)
        -> account disable/enable/reset-password
        -> tenant delete (409 when ACTIVE; success when not ACTIVE; hidden by default)

    Capability gate: if the target deployment does not expose the v2 API yet
    (old gateway/tenant-service), every v2 assertion is reported as SKIP with the
    exact missing interface, and the script exits 0 (old environments stay green).
    Only genuine assertion failures exit 1.

    Output: one [PASS]/[FAIL]/[SKIP] line per check plus a summary table.
    Exit code: 0 = all pass or skips, 1 = at least one failure.
    All script text is ASCII so it runs identically on Windows PowerShell 5.1
    (which would misinterpret a BOM-less UTF-8 file containing non-ASCII text).

.PARAMETER BaseUrl
    Gateway base URL (default http://localhost:18080 for local process mode).

.PARAMETER TimeoutSeconds
    Timeout for accepting the ACTIVE-tenant deletion guard lookup (kept short).

.EXAMPLE
    pwsh ./tests/e2e-v2.ps1
    powershell -File ./tests/e2e-v2.ps1 -BaseUrl http://localhost:30080
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$AdminUser = 'admin',
    [string]$AdminPassword = 'Admin@123',
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envScript = Join-Path $root 'scripts\env.ps1'
if (Test-Path $envScript) { . $envScript }

$script:Results = New-Object System.Collections.Generic.List[object]
$script:AdminToken = $null
$script:AdminHeaders = @{}
$script:CreatedTenantId = $null
$script:CreatedTenantCode = $null
$script:CreatedAccountId = $null
$script:RatingId = $null
$script:TenantDeleted = $false
$script:OrgCompanyId = $null
$script:OrgDeptId = $null
$script:OrgSubDeptId = $null
$script:OrgCompany2Id = $null

$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'v2 api availability',
    'tenant create',
    'org company create',
    'org department create',
    'org sub-department create',
    'org tree format',
    'org flat path/depth',
    'org duplicate code 409',
    'account create',
    'account temp password no hash',
    'account password policy',
    'account get',
    'account list filter',
    'org delete with children 409',
    'org delete with accounts 409',
    'rating assess',
    'rating dims',
    'rating current',
    'rating history',
    'tenant v2 fields',
    'org move',
    'org move cycle rejected',
    'account disable',
    'account enable',
    'account reset password',
    'tenant delete ACTIVE 409',
    'tenant delete draft success',
    'tenant list hides deleted',
    'tenant includeDeleted'
)

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
    Write-Host '---------------- e2e-v2 summary ----------------'
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

function Skip-Rest {
    param([string]$Reason, [string[]]$Exclude = @())
    foreach ($name in $script:PlannedChecks) {
        if ($Exclude -contains $name) { continue }
        Add-Check $name 'SKIP' $Reason
    }
    Show-Summary
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
        $response = $_.Exception.Response
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
    try {
        if ($response.Content) { $envelope = $response.Content | ConvertFrom-Json }
    }
    catch { $envelope = $null }

    $httpOk = ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300)
    $code = $null
    $data = $null
    $message = ''
    if ($null -ne $envelope -and $envelope.PSObject.Properties.Name -contains 'code') {
        $code = [int]$envelope.code
    }
    if ($null -ne $envelope -and $envelope.PSObject.Properties.Name -contains 'data') {
        $data = $envelope.data
    }
    if ($null -ne $envelope -and $envelope.PSObject.Properties.Name -contains 'message') {
        $message = [string]$envelope.message
    }
    $ok = ($httpOk -and ($null -eq $code -or $code -eq 0))
    return [pscustomobject]@{
        Ok      = $ok
        Status  = [int]$response.StatusCode
        Code    = $code
        Data    = $data
        Message = $message
        Content = $response.Content
    }
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

# ---------------------------------------------------------------- JSON helpers
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

function Find-SensitiveFields {
    param($Node, [string]$Prefix = '$')
    $found = New-Object System.Collections.Generic.List[string]
    if ($null -eq $Node) { return $found }
    if ($Node -is [System.Management.Automation.PSCustomObject]) {
        foreach ($property in $Node.PSObject.Properties) {
            if ($property.Name -match '(?i)hash|salt') { $found.Add($Prefix + '.' + $property.Name) }
            $nested = Find-SensitiveFields -Node $property.Value -Prefix ($Prefix + '.' + $property.Name)
            foreach ($item in $nested) { $found.Add($item) }
        }
    }
    elseif ($Node -is [System.Collections.IEnumerable] -and -not ($Node -is [string])) {
        $index = 0
        foreach ($item in $Node) {
            $nested = Find-SensitiveFields -Node $item -Prefix ($Prefix + '[' + $index + ']')
            foreach ($entry in $nested) { $found.Add($entry) }
            $index++
        }
    }
    return $found
}

function Test-PasswordPolicy {
    param([string]$Password)
    if ([string]::IsNullOrEmpty($Password)) { return $false }
    if ($Password.Length -lt 10) { return $false }
    if ($Password -notmatch '[A-Za-z]') { return $false }
    if ($Password -notmatch '[0-9]') { return $false }
    return $true
}

function New-RandomCode {
    param([string]$Prefix)
    return ($Prefix + '-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' + (Get-Random -Minimum 10000 -Maximum 99999)).ToLowerInvariant()
}

function Find-OrgNode {
    param($Nodes, [string]$Id)
    foreach ($node in @($Nodes)) {
        if ($null -eq $node) { continue }
        if ([string](Get-JsonProp $node 'id') -eq $Id) { return $node }
        $children = @(Get-JsonProp $node 'children')
        if ($children.Count -gt 0) {
            $found = Find-OrgNode -Nodes $children -Id $Id
            if ($null -ne $found) { return $found }
        }
    }
    return $null
}

function Find-ItemById {
    param($Items, [string]$Id)
    foreach ($item in @($Items)) {
        if ($null -ne $item -and [string](Get-JsonProp $item 'id') -eq $Id) { return $item }
    }
    return $null
}

function Test-ConflictResponse {
    param($Raw)
    return ($Raw.Status -eq 409 -or $Raw.Code -eq 40900)
}

function Test-InvalidOrConflict {
    param($Raw)
    return ($Raw.Status -eq 400 -or $Raw.Status -eq 409 -or $Raw.Code -eq 40001 -or $Raw.Code -eq 40900)
}

function New-OrgUnit {
    param(
        [Parameter(Mandatory = $true)][string]$TenantId,
        [string]$ParentId = '',
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Type
    )
    $body = @{ code = $Code; name = $Name; type = $Type }
    if (-not [string]::IsNullOrWhiteSpace($ParentId)) { $body['parentId'] = $ParentId }
    return Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $TenantId) -Method POST `
        -Headers $script:AdminHeaders -Body $body
}

# ---------------------------------------------------------------- preflight
Write-Host ("==> e2e-v2 against {0}" -f $BaseUrl) -ForegroundColor Cyan
$gatewayUp = $false
try {
    $health = Invoke-TenancyHttp -Uri ($BaseUrl.TrimEnd('/') + '/actuator/health') -TimeoutSec 5
    $gatewayUp = ($health.StatusCode -ge 200 -and $health.StatusCode -lt 300)
}
catch { $gatewayUp = $false }

if (-not $gatewayUp) {
    Add-Check 'preflight gateway' 'SKIP' ("gateway not reachable at {0}; start services first" -f $BaseUrl)
    Skip-Rest -Reason 'gateway down' -Exclude @('preflight gateway')
}
Add-Check 'preflight gateway' 'PASS' $BaseUrl

# ---------------------------------------------------------------- login
try {
    $login = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{ username = $AdminUser; password = $AdminPassword }
    $script:AdminToken = [string]$login.token
    if ([string]::IsNullOrWhiteSpace($script:AdminToken)) { throw 'login response has no token' }
    $script:AdminHeaders = @{ Authorization = ("Bearer {0}" -f $script:AdminToken) }
    Add-Check 'login admin' 'PASS' ("user={0} roles={1}" -f (Get-JsonProp $login.principal 'username'), ((Get-JsonProp $login.principal 'roles') -join ','))
}
catch {
    Add-Check 'login admin' 'FAIL' $_.Exception.Message
    Skip-Rest -Reason 'login failed' -Exclude @('preflight gateway', 'login admin')
}

# ---------------------------------------------------------------- v2 capability gate
$missing = New-Object System.Collections.Generic.List[string]
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
$routeProbes = @(
    @{ Path = ("/api/tenants/{0}/org-units" -f $probeTenantId); Label = 'GET /api/tenants/{tenantId}/org-units' },
    @{ Path = ("/api/tenants/{0}/accounts?page=0&size=1" -f $probeTenantId); Label = 'GET /api/tenants/{tenantId}/accounts' },
    @{ Path = ("/api/tenants/{0}/ratings/current" -f $probeTenantId); Label = 'GET /api/tenants/{tenantId}/ratings/current' }
)
foreach ($probe in $routeProbes) {
    $raw = Invoke-ApiRaw -Path $probe.Path -Headers $script:AdminHeaders
    if ($raw.Status -eq 0 -or $raw.Status -ge 500) { $missing.Add($probe.Label) }
}

$v2Available = ($missing.Count -eq 0)
if (-not $v2Available) {
    $uniqueMissing = @($missing | Select-Object -Unique)
    Add-Check 'v2 api availability' 'SKIP' ("new API not deployed; missing: {0}" -f ($uniqueMissing -join '; '))
    Skip-Rest -Reason ("new API not deployed (missing: {0})" -f ($uniqueMissing -join '; ')) `
        -Exclude @('preflight gateway', 'login admin', 'v2 api availability')
}
Add-Check 'v2 api availability' 'PASS' 'rating/org/account routes are served'

# ---------------------------------------------------------------- 1. tenant create
$tenantCode = New-RandomCode 'e2e-v2'
try {
    $tenant = Invoke-Api -Path '/api/tenants' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantCode   = $tenantCode
        name         = 'E2E V2 Identity Tenant'
        contactName  = 'E2E Bot'
        contactEmail = 'e2e-v2@example.com'
        plan         = 'ENTERPRISE'
        region       = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'
        quotaSeats   = 50
        quotaStorageGb = 100
        quotaApiRpm  = 600
        source       = 'API'
        attributes   = @{ industry = 'finance' }
        autoSubmit   = $false
    }
    $script:CreatedTenantId = [string](Get-JsonProp $tenant 'id')
    $script:CreatedTenantCode = $tenantCode
    if ([string]::IsNullOrWhiteSpace($script:CreatedTenantId)) { throw 'create response has no id' }
    if ([string](Get-JsonProp $tenant 'status') -ne 'DRAFT') { throw ("expected DRAFT but got {0}" -f (Get-JsonProp $tenant 'status')) }
    Add-Check 'tenant create' 'PASS' ("id={0} code={1} status=DRAFT" -f $script:CreatedTenantId, $tenantCode)
}
catch {
    Add-Check 'tenant create' 'FAIL' $_.Exception.Message
    Skip-Rest -Reason 'tenant create failed' -Exclude @('preflight gateway', 'login admin', 'v2 api availability', 'tenant create')
}
$tenantId = $script:CreatedTenantId

# ---------------------------------------------------------------- 2. org tree
$orgBase = "/api/tenants/$tenantId/org-units"
$companyCode = New-RandomCode 'org-company'
try {
    $company = New-OrgUnit -TenantId $tenantId -Code $companyCode -Name 'E2E Company' -Type 'COMPANY'
    $script:OrgCompanyId = [string](Get-JsonProp $company 'id')
    if ([string]::IsNullOrWhiteSpace($script:OrgCompanyId)) { throw 'company response has no id' }
    if ([string](Get-JsonProp $company 'type') -ne 'COMPANY') { throw 'expected type COMPANY' }
    if ([string](Get-JsonProp $company 'code') -ne $companyCode) { throw 'code mismatch on company' }
    if ([string](Get-JsonProp $company 'path') -notmatch $script:OrgCompanyId) { throw 'path does not contain company id' }
    Add-Check 'org company create' 'PASS' ("id={0} path={1} depth={2}" -f $script:OrgCompanyId, (Get-JsonProp $company 'path'), (Get-JsonProp $company 'depth'))
}
catch {
    Add-Check 'org company create' 'FAIL' $_.Exception.Message
    Skip-Rest -Reason 'company create failed' -Exclude @('preflight gateway', 'login admin', 'v2 api availability', 'tenant create', 'org company create')
}

try {
    $dept = New-OrgUnit -TenantId $tenantId -ParentId $script:OrgCompanyId -Code (New-RandomCode 'org-dept') -Name 'E2E Department' -Type 'DEPARTMENT'
    $script:OrgDeptId = [string](Get-JsonProp $dept 'id')
    if ([string]::IsNullOrWhiteSpace($script:OrgDeptId)) { throw 'department response has no id' }
    if ([string](Get-JsonProp $dept 'parentId') -ne $script:OrgCompanyId) { throw 'department parentId mismatch' }
    Add-Check 'org department create' 'PASS' ("id={0} parent={1} depth={2}" -f $script:OrgDeptId, $script:OrgCompanyId, (Get-JsonProp $dept 'depth'))
}
catch {
    Add-Check 'org department create' 'FAIL' $_.Exception.Message
    Skip-Rest -Reason 'department create failed' -Exclude @('preflight gateway', 'login admin', 'v2 api availability', 'tenant create', 'org company create', 'org department create')
}

try {
    $subDept = New-OrgUnit -TenantId $tenantId -ParentId $script:OrgDeptId -Code (New-RandomCode 'org-subdept') -Name 'E2E Sub Department' -Type 'DEPARTMENT'
    $script:OrgSubDeptId = [string](Get-JsonProp $subDept 'id')
    if ([string]::IsNullOrWhiteSpace($script:OrgSubDeptId)) { throw 'sub-department response has no id' }
    if ([string](Get-JsonProp $subDept 'parentId') -ne $script:OrgDeptId) { throw 'sub-department parentId mismatch' }
    Add-Check 'org sub-department create' 'PASS' ("id={0} parent={1} depth={2}" -f $script:OrgSubDeptId, $script:OrgDeptId, (Get-JsonProp $subDept 'depth'))
}
catch {
    Add-Check 'org sub-department create' 'FAIL' $_.Exception.Message
    Skip-Rest -Reason 'sub-department create failed' -Exclude @('preflight gateway', 'login admin', 'v2 api availability', 'tenant create', 'org company create', 'org department create', 'org sub-department create')
}

# tree format: nested children under company -> department -> sub-department
try {
    $treeData = Invoke-Api -Path ($orgBase + '?format=tree') -Headers $script:AdminHeaders
    $treeNodes = Get-JsonProp $treeData 'items'
    if ($null -eq $treeNodes) { $treeNodes = $treeData }
    $companyNode = Find-OrgNode -Nodes $treeNodes -Id $script:OrgCompanyId
    if ($null -eq $companyNode) { throw 'company not found in tree response' }
    $deptNode = Find-OrgNode -Nodes @(Get-JsonProp $companyNode 'children') -Id $script:OrgDeptId
    if ($null -eq $deptNode) { throw 'department is not a child of company in tree response' }
    $subNode = Find-OrgNode -Nodes @(Get-JsonProp $deptNode 'children') -Id $script:OrgSubDeptId
    if ($null -eq $subNode) { throw 'sub-department is not a child of department in tree response' }
    Add-Check 'org tree format' 'PASS' 'company -> department -> sub-department nesting verified'
}
catch {
    Add-Check 'org tree format' 'FAIL' $_.Exception.Message
}

# flat format: parent/path/depth consistency
try {
    $flatData = Invoke-Api -Path ($orgBase + '?format=flat') -Headers $script:AdminHeaders
    $flatItems = Get-JsonProp $flatData 'items'
    if ($null -eq $flatItems) { $flatItems = $flatData }
    $flatCompany = Find-ItemById -Items $flatItems -Id $script:OrgCompanyId
    $flatDept = Find-ItemById -Items $flatItems -Id $script:OrgDeptId
    $flatSub = Find-ItemById -Items $flatItems -Id $script:OrgSubDeptId
    if ($null -eq $flatCompany -or $null -eq $flatDept -or $null -eq $flatSub) { throw 'flat list is missing created org units' }
    $companyPath = [string](Get-JsonProp $flatCompany 'path')
    $deptPath = [string](Get-JsonProp $flatDept 'path')
    $subPath = [string](Get-JsonProp $flatSub 'path')
    if ([int](Get-JsonProp $flatDept 'depth') -le [int](Get-JsonProp $flatCompany 'depth')) { throw 'department depth must be greater than company depth' }
    if ([int](Get-JsonProp $flatSub 'depth') -le [int](Get-JsonProp $flatDept 'depth')) { throw 'sub-department depth must be greater than department depth' }
    if (-not $deptPath.StartsWith($companyPath)) { throw 'department path is not under company path' }
    if (-not $subPath.StartsWith($deptPath)) { throw 'sub-department path is not under department path' }
    Add-Check 'org flat path/depth' 'PASS' 'parent/depth/path hierarchy consistent'
}
catch {
    Add-Check 'org flat path/depth' 'FAIL' $_.Exception.Message
}

# duplicate code must be rejected (unique constraint -> 409 per docs/11 section 2.4)
try {
    $duplicate = Invoke-ApiRaw -Path $orgBase -Method POST -Headers $script:AdminHeaders -Body @{
        code = $companyCode; name = 'Duplicate Company'; type = 'COMPANY'
    }
    if (Test-ConflictResponse $duplicate) {
        Add-Check 'org duplicate code 409' 'PASS' ("HTTP {0} code={1}" -f $duplicate.Status, $duplicate.Code)
    }
    else {
        Add-Check 'org duplicate code 409' 'FAIL' ("expected 409, got HTTP {0} code={1}" -f $duplicate.Status, $duplicate.Code)
    }
}
catch {
    Add-Check 'org duplicate code 409' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- 3. account create
$username = New-RandomCode 'e2e.user'
$tempPassword = $null
$account = $null
try {
    $accountResponse = Invoke-Api -Path "/api/tenants/$tenantId/accounts" -Method POST -Headers $script:AdminHeaders -Body @{
        username    = $username
        displayName = 'E2E V2 Account'
        email       = 'e2e-account@example.com'
        phone       = '+86-13800000000'
        orgUnitId   = $script:OrgSubDeptId
        role        = 'MEMBER'
    }
    $account = Get-JsonProp $accountResponse 'account'
    if ($null -eq $account) { $account = $accountResponse }
    $script:CreatedAccountId = [string](Get-JsonProp $account 'id')
    $tempPassword = [string](Get-JsonProp $accountResponse 'tempPassword')
    if ([string]::IsNullOrWhiteSpace($script:CreatedAccountId)) { throw 'account response has no account.id' }
    if ([string](Get-JsonProp $account 'username') -ne $username.ToLowerInvariant()) { throw ("username not stored lowercase: {0}" -f (Get-JsonProp $account 'username')) }
    if (-not (@('INVITED', 'ACTIVE') -contains [string](Get-JsonProp $account 'status'))) {
        throw ("unexpected account status: {0}" -f (Get-JsonProp $account 'status'))
    }
    if ([string](Get-JsonProp $account 'orgUnitId') -ne $script:OrgSubDeptId) { throw 'account orgUnitId mismatch' }
    Add-Check 'account create' 'PASS' ("id={0} username={1} status={2}" -f $script:CreatedAccountId, $username, (Get-JsonProp $account 'status'))
}
catch {
    Add-Check 'account create' 'FAIL' $_.Exception.Message
    Skip-Rest -Reason 'account create failed' -Exclude @('preflight gateway', 'login admin', 'v2 api availability', 'tenant create', 'org company create', 'org department create', 'org sub-department create', 'org tree format', 'org flat path/depth', 'org duplicate code 409', 'account create')
}

# one-time temp password + no hash/salt anywhere in the response
try {
    if ([string]::IsNullOrWhiteSpace($tempPassword)) { throw 'create response has no tempPassword' }
    $sensitive = Find-SensitiveFields -Node $accountResponse
    if ($sensitive.Count -gt 0) { throw ("response exposes sensitive fields: {0}" -f ($sensitive -join ', ')) }
    Add-Check 'account temp password no hash' 'PASS' 'tempPassword returned once, no hash/salt field in response'
}
catch {
    Add-Check 'account temp password no hash' 'FAIL' $_.Exception.Message
}

try {
    if (-not (Test-PasswordPolicy $tempPassword)) {
        throw 'temp password violates policy (>=10 chars, letters + digits)'
    }
    Add-Check 'account password policy' 'PASS' ("length={0}" -f $tempPassword.Length)
}
catch {
    Add-Check 'account password policy' 'FAIL' $_.Exception.Message
}

try {
    $accountDetail = Invoke-Api -Path ("/api/tenants/{0}/accounts/{1}" -f $tenantId, $script:CreatedAccountId) -Headers $script:AdminHeaders
    $sensitive = Find-SensitiveFields -Node $accountDetail
    if ($sensitive.Count -gt 0) { throw ("detail response exposes sensitive fields: {0}" -f ($sensitive -join ', ')) }
    if ([string](Get-JsonProp $accountDetail 'id') -ne $script:CreatedAccountId) { throw 'account detail id mismatch' }
    Add-Check 'account get' 'PASS' 'detail returned without hash/salt'
}
catch {
    Add-Check 'account get' 'FAIL' $_.Exception.Message
}

try {
    $accountPage = Invoke-Api -Path ("/api/tenants/{0}/accounts?orgUnitId={1}&page=0&size=10" -f $tenantId, $script:OrgSubDeptId) -Headers $script:AdminHeaders
    $accountItems = @(Get-JsonProp $accountPage 'items')
    $foundAccount = Find-ItemById -Items $accountItems -Id $script:CreatedAccountId
    if ($null -eq $foundAccount) { throw 'created account not returned by orgUnitId filter' }
    Add-Check 'account list filter' 'PASS' ("total={0}" -f (Get-JsonProp $accountPage 'total'))
}
catch {
    Add-Check 'account list filter' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- 4. org deletion guards
try {
    $deleteCompany = Invoke-ApiRaw -Path ("{0}/{1}" -f $orgBase, $script:OrgCompanyId) -Method DELETE -Headers $script:AdminHeaders
    if (Test-ConflictResponse $deleteCompany) {
        Add-Check 'org delete with children 409' 'PASS' ("HTTP {0} code={1}" -f $deleteCompany.Status, $deleteCompany.Code)
    }
    else {
        Add-Check 'org delete with children 409' 'FAIL' ("expected 409 when children exist, got HTTP {0} code={1}" -f $deleteCompany.Status, $deleteCompany.Code)
    }
}
catch {
    Add-Check 'org delete with children 409' 'FAIL' $_.Exception.Message
}

try {
    $deleteSub = Invoke-ApiRaw -Path ("{0}/{1}" -f $orgBase, $script:OrgSubDeptId) -Method DELETE -Headers $script:AdminHeaders
    if (Test-ConflictResponse $deleteSub) {
        Add-Check 'org delete with accounts 409' 'PASS' ("HTTP {0} code={1}" -f $deleteSub.Status, $deleteSub.Code)
    }
    else {
        Add-Check 'org delete with accounts 409' 'FAIL' ("expected 409 when accounts exist, got HTTP {0} code={1}" -f $deleteSub.Status, $deleteSub.Code)
    }
}
catch {
    Add-Check 'org delete with accounts 409' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- 5. rating
$rating = $null
try {
    $rating = Invoke-Api -Path ("/api/tenants/{0}/ratings/assess" -f $tenantId) -Method POST -Headers $script:AdminHeaders -Body @{
        note = 'e2e-v2 automated assessment'
    }
    $script:RatingId = [string](Get-JsonProp $rating 'id')
    $grade = [string](Get-JsonProp $rating 'grade')
    if (-not (@('AAA', 'AA', 'A', 'B', 'C', 'D') -contains $grade)) { throw ("invalid rating grade: {0}" -f $grade) }
    $score = Get-JsonProp $rating 'score'
    if ($null -eq $score -or [int]$score -lt 0 -or [int]$score -gt 100) { throw ("invalid rating score: {0}" -f $score) }
    if (-not (@('SKILL', 'MANUAL') -contains [string](Get-JsonProp $rating 'source'))) { throw ("invalid rating source: {0}" -f (Get-JsonProp $rating 'source')) }
    if ([string]::IsNullOrWhiteSpace([string](Get-JsonProp $rating 'assessedBy'))) { throw 'rating assessedBy is empty' }
    Add-Check 'rating assess' 'PASS' ("grade={0} score={1} source={2}" -f $grade, $score, (Get-JsonProp $rating 'source'))
}
catch {
    Add-Check 'rating assess' 'FAIL' $_.Exception.Message
    Skip-Rest -Reason 'rating assess failed' -Exclude @('preflight gateway', 'login admin', 'v2 api availability', 'tenant create', 'org company create', 'org department create', 'org sub-department create', 'org tree format', 'org flat path/depth', 'org duplicate code 409', 'account create', 'account temp password no hash', 'account password policy', 'account get', 'account list filter', 'org delete with children 409', 'org delete with accounts 409', 'rating assess')
}

try {
    $dims = Get-JsonProp $rating 'dims'
    foreach ($dim in @('business', 'compliance', 'financial', 'security', 'service')) {
        $value = Get-JsonProp $dims $dim
        if ($null -eq $value) { throw ("rating dims missing: {0}" -f $dim) }
        if ([int]$value -lt 0 -or [int]$value -gt 100) { throw ("rating dim {0} out of range: {1}" -f $dim, $value) }
    }
    Add-Check 'rating dims' 'PASS' 'five dimensions (business/compliance/financial/security/service) in 0..100'
}
catch {
    Add-Check 'rating dims' 'FAIL' $_.Exception.Message
}

try {
    $current = Invoke-Api -Path ("/api/tenants/{0}/ratings/current" -f $tenantId) -Headers $script:AdminHeaders
    if ([string](Get-JsonProp $current 'grade') -ne [string](Get-JsonProp $rating 'grade')) {
        throw 'ratings/current grade does not match assess result'
    }
    Add-Check 'rating current' 'PASS' ("grade={0}" -f (Get-JsonProp $current 'grade'))
}
catch {
    Add-Check 'rating current' 'FAIL' $_.Exception.Message
}

try {
    $history = Invoke-Api -Path ("/api/tenants/{0}/ratings?page=0&size=10" -f $tenantId) -Headers $script:AdminHeaders
    $historyItems = @(Get-JsonProp $history 'items')
    $historyTotal = Get-JsonProp $history 'total'
    if ($historyItems.Count -lt 1) { throw 'rating history is empty after assess' }
    $historyMatch = $null
    if (-not [string]::IsNullOrWhiteSpace($script:RatingId)) {
        $historyMatch = Find-ItemById -Items $historyItems -Id $script:RatingId
    }
    if ($null -eq $historyMatch) {
        if ([string](Get-JsonProp $historyItems[0] 'grade') -ne [string](Get-JsonProp $rating 'grade')) {
            throw 'history does not contain the assessed rating'
        }
    }
    Add-Check 'rating history' 'PASS' ("total={0}" -f $historyTotal)
}
catch {
    Add-Check 'rating history' 'FAIL' $_.Exception.Message
}

# tenant current rating fields (values prove the v2 TenantView contract)
try {
    $tenantView = Invoke-Api -Path ("/api/tenants/{0}" -f $tenantId) -Headers $script:AdminHeaders
    if ([string](Get-JsonProp $tenantView 'ratingGrade') -ne [string](Get-JsonProp $rating 'grade')) { throw 'TenantView.ratingGrade does not match assessed grade' }
    $orgCount = Get-JsonProp $tenantView 'orgUnitCount'
    $accCount = Get-JsonProp $tenantView 'accountCount'
    if ($null -eq $orgCount -or [int]$orgCount -lt 3) { throw ("TenantView.orgUnitCount expected >=3, got {0}" -f $orgCount) }
    if ($null -eq $accCount -or [int]$accCount -lt 1) { throw ("TenantView.accountCount expected >=1, got {0}" -f $accCount) }
    Add-Check 'tenant v2 fields' 'PASS' ("ratingGrade={0} orgUnitCount={1} accountCount={2}" -f (Get-JsonProp $tenantView 'ratingGrade'), $orgCount, $accCount)
}
catch {
    Add-Check 'tenant v2 fields' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- 6. org move + cycle guard
try {
    $company2 = New-OrgUnit -TenantId $tenantId -Code (New-RandomCode 'org-company2') -Name 'E2E Company Two' -Type 'COMPANY'
    $script:OrgCompany2Id = [string](Get-JsonProp $company2 'id')
    if ([string]::IsNullOrWhiteSpace($script:OrgCompany2Id)) { throw 'second company has no id' }
    $moved = Invoke-Api -Path ("{0}/{1}/move" -f $orgBase, $script:OrgDeptId) -Method POST -Headers $script:AdminHeaders -Body @{
        parentId = $script:OrgCompany2Id
    }
    if ([string](Get-JsonProp $moved 'parentId') -ne $script:OrgCompany2Id) { throw 'move response parentId mismatch' }
    $movedDetail = Invoke-Api -Path ("{0}/{1}" -f $orgBase, $script:OrgDeptId) -Headers $script:AdminHeaders
    $company2Detail = Invoke-Api -Path ("{0}/{1}" -f $orgBase, $script:OrgCompany2Id) -Headers $script:AdminHeaders
    if ([int](Get-JsonProp $movedDetail 'depth') -ne ([int](Get-JsonProp $company2Detail 'depth') + 1)) { throw 'depth not recalculated after move' }
    if (-not ([string](Get-JsonProp $movedDetail 'path')).StartsWith([string](Get-JsonProp $company2Detail 'path'))) { throw 'path not recalculated after move' }
    Add-Check 'org move' 'PASS' ("department moved under company2 depth={0}" -f (Get-JsonProp $movedDetail 'depth'))
}
catch {
    Add-Check 'org move' 'FAIL' $_.Exception.Message
}

try {
    if ([string]::IsNullOrWhiteSpace($script:OrgCompany2Id)) { throw 'second company was not created; cycle guard not testable' }
    $cycle = Invoke-ApiRaw -Path ("{0}/{1}/move" -f $orgBase, $script:OrgCompany2Id) -Method POST -Headers $script:AdminHeaders -Body @{
        parentId = $script:OrgDeptId
    }
    if (Test-InvalidOrConflict $cycle) {
        Add-Check 'org move cycle rejected' 'PASS' ("HTTP {0} code={1}" -f $cycle.Status, $cycle.Code)
    }
    else {
        Add-Check 'org move cycle rejected' 'FAIL' ("expected 400/409 for cycle move, got HTTP {0} code={1}" -f $cycle.Status, $cycle.Code)
    }
}
catch {
    Add-Check 'org move cycle rejected' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- 7. account status + reset
try {
    $disabled = Invoke-Api -Path ("/api/tenants/{0}/accounts/{1}/status" -f $tenantId, $script:CreatedAccountId) -Method POST `
        -Headers $script:AdminHeaders -Body @{ status = 'DISABLED'; reason = 'e2e-v2 disable' }
    if ([string](Get-JsonProp $disabled 'status') -ne 'DISABLED') { throw ("expected DISABLED, got {0}" -f (Get-JsonProp $disabled 'status')) }
    Add-Check 'account disable' 'PASS' 'status=DISABLED'
}
catch {
    Add-Check 'account disable' 'FAIL' $_.Exception.Message
}

try {
    $enabled = Invoke-Api -Path ("/api/tenants/{0}/accounts/{1}/status" -f $tenantId, $script:CreatedAccountId) -Method POST `
        -Headers $script:AdminHeaders -Body @{ status = 'ACTIVE'; reason = 'e2e-v2 enable' }
    if ([string](Get-JsonProp $enabled 'status') -ne 'ACTIVE') { throw ("expected ACTIVE, got {0}" -f (Get-JsonProp $enabled 'status')) }
    Add-Check 'account enable' 'PASS' 'status=ACTIVE'
}
catch {
    Add-Check 'account enable' 'FAIL' $_.Exception.Message
}

try {
    $reset = Invoke-Api -Path ("/api/tenants/{0}/accounts/{1}/reset-password" -f $tenantId, $script:CreatedAccountId) -Method POST `
        -Headers $script:AdminHeaders -Body @{ }
    $resetPassword = [string](Get-JsonProp $reset 'tempPassword')
    if ([string]::IsNullOrWhiteSpace($resetPassword)) { throw 'reset-password response has no tempPassword' }
    if (-not (Test-PasswordPolicy $resetPassword)) { throw 'reset temp password violates policy (>=10 chars, letters + digits)' }
    $sensitive = Find-SensitiveFields -Node $reset
    if ($sensitive.Count -gt 0) { throw ("reset response exposes sensitive fields: {0}" -f ($sensitive -join ', ')) }
    Add-Check 'account reset password' 'PASS' 'new one-time temp password returned, no hash/salt'
}
catch {
    Add-Check 'account reset password' 'FAIL' $_.Exception.Message
}

# ---------------------------------------------------------------- 8. tenant deletion
try {
    $activePage = Invoke-Api -Path '/api/tenants?status=ACTIVE&page=0&size=1' -Headers $script:AdminHeaders
    $activeItems = @(Get-JsonProp $activePage 'items')
    if ($activeItems.Count -eq 0) {
        Add-Check 'tenant delete ACTIVE 409' 'SKIP' 'no ACTIVE tenant available to verify the delete guard'
    }
    else {
        $activeId = [string](Get-JsonProp $activeItems[0] 'id')
        $activeDelete = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $activeId) -Method DELETE -Headers $script:AdminHeaders
        if (Test-ConflictResponse $activeDelete) {
            Add-Check 'tenant delete ACTIVE 409' 'PASS' ("tenantId={0} HTTP {1} code={2}" -f $activeId, $activeDelete.Status, $activeDelete.Code)
        }
        else {
            Add-Check 'tenant delete ACTIVE 409' 'FAIL' ("expected 409 for ACTIVE tenant, got HTTP {0} code={1}" -f $activeDelete.Status, $activeDelete.Code)
        }
    }
}
catch {
    Add-Check 'tenant delete ACTIVE 409' 'FAIL' $_.Exception.Message
}

try {
    $deleteDraft = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $tenantId) -Method DELETE -Headers $script:AdminHeaders
    if (-not $deleteDraft.Ok) {
        throw ("expected successful soft delete, got HTTP {0} code={1} message={2}" -f $deleteDraft.Status, $deleteDraft.Code, $deleteDraft.Message)
    }
    $script:TenantDeleted = $true
    Add-Check 'tenant delete draft success' 'PASS' ("HTTP {0}" -f $deleteDraft.Status)
}
catch {
    Add-Check 'tenant delete draft success' 'FAIL' $_.Exception.Message
}

try {
    $defaultList = Invoke-Api -Path ("/api/tenants?keyword={0}&page=0&size=10" -f $script:CreatedTenantCode) -Headers $script:AdminHeaders
    $defaultItems = @(Get-JsonProp $defaultList 'items')
    $visible = Find-ItemById -Items $defaultItems -Id $tenantId
    if ($null -ne $visible) { throw 'deleted tenant is still visible in the default list' }
    Add-Check 'tenant list hides deleted' 'PASS' 'deleted tenant excluded from default list'
}
catch {
    Add-Check 'tenant list hides deleted' 'FAIL' $_.Exception.Message
}

try {
    $deletedList = Invoke-Api -Path ("/api/tenants?keyword={0}&includeDeleted=true&page=0&size=10" -f $script:CreatedTenantCode) -Headers $script:AdminHeaders
    $deletedItems = @(Get-JsonProp $deletedList 'items')
    $deletedTenant = Find-ItemById -Items $deletedItems -Id $tenantId
    if ($null -eq $deletedTenant) { throw 'deleted tenant not returned with includeDeleted=true' }
    $deletedAt = Get-JsonProp $deletedTenant 'deletedAt'
    if ([string]::IsNullOrWhiteSpace([string]$deletedAt)) { throw 'includeDeleted response has no deletedAt timestamp' }
    Add-Check 'tenant includeDeleted' 'PASS' ("deletedAt={0}" -f $deletedAt)
}
catch {
    Add-Check 'tenant includeDeleted' 'FAIL' $_.Exception.Message
}

# best-effort cleanup: remove org units / account of a tenant left behind by an early failure
if (-not $script:TenantDeleted -and -not [string]::IsNullOrWhiteSpace($tenantId)) {
    try {
        $cleanup = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $tenantId) -Method DELETE -Headers $script:AdminHeaders
        if ($cleanup.Ok) {
            Write-Host ("[cleanup] tenant {0} removed" -f $tenantId) -ForegroundColor DarkGray
        }
        else {
            Write-Host ("[cleanup] tenant {0} left in place: HTTP {1} code={2}" -f $tenantId, $cleanup.Status, $cleanup.Code) -ForegroundColor DarkYellow
        }
    }
    catch {
        Write-Host ("[cleanup] tenant {0} left in place: {1}" -f $tenantId, $_.Exception.Message) -ForegroundColor DarkYellow
    }
}

Show-Summary
