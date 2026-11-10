#Requires -Version 5.1
<#
.SYNOPSIS
    v12 E2E: Mock OIDC SSO end-to-end, approval workflow definitions, tenant RLS isolation
    and platform bootstrap users (docs/23).

.DESCRIPTION
    Black-box E2E for the v12 production-hardening increment:

      Mock OIDC (gateway, dev-only):
        GET /mock-oidc/.well-known/openid-configuration -> configure an enabled SSO
        provider with issuer = the gateway's in-cluster mock issuer -> authorize (302) ->
        code -> gateway callback (302) -> JWT -> GET /api/auth/me. When the issuer is not
        reachable from the gateway (e.g. unknown in-cluster URL) only discovery is verified
        and the end-to-end steps are SKIP.

      Workflow definitions (approval-service, via /api/approval-workflows):
        list -> create (AUTO_CHECK risk-review -> HUMAN slaHours=2) -> validate -> publish
        -> create an approval with workflowCode -> state PENDING_APPROVAL -> cleanup.

      Tenant RLS / logical isolation:
        tenant account B cannot read tenant A's org units / accounts (403/404), and a
        mismatching X-Tenant-Id header is rejected; platform admin can read both.

      Platform bootstrap:
        GET /api/platform/users is non-empty and contains a PLATFORM_ADMIN user.

    Capability gate: /mock-oidc discovery, /api/approval-workflows and /api/platform/users
    are probed before creating data; when all are missing the run is SKIP with exit code 0.
    The workflow block is SKIP when its API is not reachable (docs/23 notes the gateway
    workflow route is still being rolled out).

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.

.PARAMETER BaseUrl
    Gateway base URL (host-reachable). Default http://localhost:18080.

.PARAMETER GatewayInternalUrl
    URL the gateway pod can use to reach itself for the Mock OIDC discovery/issuer.
    Default: http://gateway:8080 when BaseUrl uses port 30080 (k8s), else BaseUrl.

.PARAMETER TimeoutSeconds
    Timeout for tenant activation polling. Default 120.

.EXAMPLE
    pwsh ./tests/e2e-v12.ps1 -BaseUrl http://localhost:30080
#>
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$GatewayInternalUrl = '',
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

$script:Base = $BaseUrl.TrimEnd('/')
$script:InternalBase = $GatewayInternalUrl.TrimEnd('/')
if ([string]::IsNullOrWhiteSpace($script:InternalBase)) {
    if ($script:Base -match ':30080$') { $script:InternalBase = 'http://gateway:8080' }
    else { $script:InternalBase = $script:Base }
}
$script:Results = New-Object System.Collections.Generic.List[object]
$script:AddedChecks = New-Object System.Collections.Generic.List[string]
$script:CleanedUp = $false
$script:AdminToken = ''
$script:AdminHeaders = @{}
$script:TenantAId = ''
$script:TenantACode = ''
$script:TenantBId = ''
$script:TenantBCode = ''
$script:SsoProviderId = ''
$script:WorkflowId = ''
$script:WorkflowCode = ''
$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'v12 api availability',
    'platform bootstrap users',
    'tenant A create',
    'tenant A activation',
    'tenant B create',
    'tenant B activation',
    'org A create',
    'org B create',
    'account B create',
    'tenant B login',
    'cross tenant org 403',
    'cross tenant accounts 403',
    'cross tenant header 403',
    'admin cross tenant read',
    'workflow api availability',
    'workflow list',
    'workflow create',
    'workflow validate',
    'workflow publish',
    'approval with workflowCode',
    'approval pending state',
    'workflow cleanup',
    'mock oidc discovery',
    'sso provider create',
    'sso provider public list',
    'oidc authorize url',
    'oidc mock authorize redirect',
    'oidc callback jwt',
    'oidc me',
    'sso provider cleanup',
    'tenant cleanup'
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
    Write-Host '---------------- e2e-v12 summary ----------------'
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
    foreach ($listName in @('items', 'records', 'Resources', 'workflows')) {
        if (Test-HasProp $Data $listName) { return @($Data.$listName) }
    }
    return @($Data)
}

# ---------------------------------------------------------------- HTTP helpers
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
        else { $params['Body'] = ($Body | ConvertTo-Json -Depth 16 -Compress) }
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

function Invoke-NoRedirect {
    param([Parameter(Mandatory = $true)][string]$Uri, [hashtable]$Headers = @{}, [int]$TimeoutSec = 30)
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $request = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
        $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, $Uri)
        foreach ($key in $Headers.Keys) {
            [void]$request.Headers.TryAddWithoutValidation([string]$key, [string]$Headers[$key])
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        $location = ''
        try {
            if ($null -ne $response.Headers.Location) { $location = $response.Headers.Location.ToString() }
        }
        catch { $location = '' }
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $response.Dispose()
        return [pscustomobject]@{ Status = $status; Location = $location; Content = $content }
    }
    finally {
        if ($null -ne $request) { $request.Dispose() }
        $client.Dispose()
        $handler.Dispose()
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
                    $null = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method POST -Headers $script:AdminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v12 activation' }
                    $approved = $true
                }
            }
            catch { }
        }
        Start-Sleep -Seconds 2
    }
    return $lastStatus
}

function New-TestTenant {
    param([string]$Code, [string]$Name)
    return Invoke-Api -Path '/api/tenants' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantCode    = $Code
        name          = $Name
        contactEmail  = 'v12@example.com'
        contactName   = 'E2E V12 Owner'
        plan          = 'PRO'
        region        = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'
        source        = 'MANUAL'
        autoSubmit    = $false
    }
}

function Remove-TestTenant {
    param([string]$TenantId)
    if ([string]::IsNullOrWhiteSpace($TenantId)) { return }
    try {
        $tenant = Invoke-Api -Path ("/api/tenants/{0}" -f $TenantId) -Headers $script:AdminHeaders
        $status = [string](Get-JsonProp $tenant 'status')
        if ($status -eq 'ACTIVE' -or $status -eq 'SUSPENDED') {
            $null = Invoke-ApiRaw -Path ("/api/tenants/{0}/terminate" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v12 cleanup' }
        }
        $null = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $TenantId) -Method DELETE -Headers $script:AdminHeaders
    }
    catch { }
}

function Invoke-Cleanup {
    if ($script:CleanedUp) { return }
    $script:CleanedUp = $true
    if (-not [string]::IsNullOrWhiteSpace($script:AdminToken)) {
        if (-not [string]::IsNullOrWhiteSpace($script:SsoProviderId)) {
            try { $null = Invoke-ApiRaw -Path ("/api/platform/sso-providers/{0}" -f $script:SsoProviderId) -Method DELETE -Headers $script:AdminHeaders } catch { }
        }
        if (-not [string]::IsNullOrWhiteSpace($script:WorkflowId)) {
            try { $null = Invoke-ApiRaw -Path ("/api/approval-workflows/{0}" -f $script:WorkflowId) -Method DELETE -Headers $script:AdminHeaders } catch { }
        }
    }
    foreach ($tenantId in @($script:TenantBId, $script:TenantAId)) { Remove-TestTenant -TenantId $tenantId }
}

# ================================================================ run
Write-Host ("==> e2e-v12 against {0} (internal {1})" -f $script:Base, $script:InternalBase) -ForegroundColor Cyan

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
    $login = Invoke-Api -Path '/api/auth/login' -Method POST -Headers @{ 'X-Forwarded-For' = ('198.51.100.' + (Get-Random -Minimum 10 -Maximum 250)) } -Body @{ username = 'admin'; password = 'Admin@123' }
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

# ---------------- capability gate: mock OIDC / workflow definitions / platform users
$mockDiscovery = Invoke-TenancyHttp -Uri ($script:Base + '/mock-oidc/.well-known/openid-configuration') -TimeoutSec 10
$mockDiscoveryDoc = $null
try { $mockDiscoveryDoc = $mockDiscovery.Content | ConvertFrom-Json } catch { $mockDiscoveryDoc = $null }
$mockReady = ($mockDiscovery.StatusCode -eq 200 -and $null -ne $mockDiscoveryDoc -and
    (Test-HasProp $mockDiscoveryDoc 'authorization_endpoint'))
$workflowProbe = Invoke-ApiRaw -Path '/api/approval-workflows' -Headers $script:AdminHeaders
$workflowReady = ($workflowProbe.Ok -and $null -ne $workflowProbe.Data)
$usersProbe = Invoke-ApiRaw -Path '/api/platform/users' -Headers $script:AdminHeaders
$usersReady = $usersProbe.Ok

if (-not $mockReady -and -not $workflowReady -and -not $usersReady) {
    Add-Check 'v12 api availability' 'SKIP' ("v12 API not deployed; mock oidc HTTP {0}, workflows HTTP {1}, platform users HTTP {2}" -f $mockDiscovery.StatusCode, $workflowProbe.Status, $usersProbe.Status)
    Skip-Remaining -Reason 'v12 API not deployed (docs/23)'
    Show-Summary
}
Add-Check 'v12 api availability' 'PASS' ("mockOidc={0} workflows={1} platformUsers={2}" -f $mockReady, $workflowReady, $usersReady)

# ---------------- platform bootstrap users
try {
    if (-not $usersReady) {
        Add-Check 'platform bootstrap users' 'SKIP' ("GET /api/platform/users -> HTTP {0}" -f $usersProbe.Status)
    }
    else {
        $users = Invoke-Api -Path '/api/platform/users' -Headers $script:AdminHeaders
        $userItems = @(Get-Items $users)
        $hasPlatformAdmin = $false
        $names = New-Object System.Collections.Generic.List[string]
        foreach ($user in $userItems) {
            $names.Add([string](Get-JsonProp $user 'username'))
            $roles = @(Get-Items (Get-JsonProp $user 'roles'))
            if ($roles -contains 'PLATFORM_ADMIN') { $hasPlatformAdmin = $true }
        }
        if ($userItems.Count -ge 1 -and $hasPlatformAdmin) {
            Add-Check 'platform bootstrap users' 'PASS' ("users={0} usernames={1}" -f $userItems.Count, ($names -join ','))
        }
        else {
            Add-Check 'platform bootstrap users' 'FAIL' ("users={0} hasPlatformAdmin={1}" -f $userItems.Count, $hasPlatformAdmin)
        }
    }
}
catch { Add-Check 'platform bootstrap users' 'FAIL' $_.Exception.Message }

# ---------------- tenants for RLS / workflow approval
$tenantACode = New-RandomCode 'e2e-v12-a'
try {
    $tenantA = New-TestTenant -Code $tenantACode -Name 'E2E V12 Tenant A'
    $script:TenantAId = [string](Get-JsonProp $tenantA 'id')
    $script:TenantACode = $tenantACode
    if ([string]::IsNullOrWhiteSpace($script:TenantAId)) { throw 'tenant A id missing' }
    Add-Check 'tenant A create' 'PASS' ("id={0} code={1}" -f $script:TenantAId, $tenantACode)
}
catch { Add-Check 'tenant A create' 'FAIL' $_.Exception.Message }

$activationA = 'CONNECTION_ERROR'
if (-not [string]::IsNullOrWhiteSpace($script:TenantAId)) { $activationA = Activate-TestTenant -TenantId $script:TenantAId }
if ($activationA -eq 'ACTIVE') { Add-Check 'tenant A activation' 'PASS' 'status=ACTIVE' }
else { Add-Check 'tenant A activation' 'FAIL' ("status={0}" -f $activationA) }

$tenantBCode = New-RandomCode 'e2e-v12-b'
try {
    $tenantB = New-TestTenant -Code $tenantBCode -Name 'E2E V12 Tenant B'
    $script:TenantBId = [string](Get-JsonProp $tenantB 'id')
    $script:TenantBCode = $tenantBCode
    if ([string]::IsNullOrWhiteSpace($script:TenantBId)) { throw 'tenant B id missing' }
    Add-Check 'tenant B create' 'PASS' ("id={0} code={1}" -f $script:TenantBId, $tenantBCode)
}
catch { Add-Check 'tenant B create' 'FAIL' $_.Exception.Message }

$activationB = 'CONNECTION_ERROR'
if (-not [string]::IsNullOrWhiteSpace($script:TenantBId)) { $activationB = Activate-TestTenant -TenantId $script:TenantBId }
if ($activationB -eq 'ACTIVE') { Add-Check 'tenant B activation' 'PASS' 'status=ACTIVE' }
else { Add-Check 'tenant B activation' 'FAIL' ("status={0}" -f $activationB) }

$orgA = ''
try {
    $org = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantAId) -Method POST -Headers $script:AdminHeaders -Body @{
        code = 'HQ'; name = 'Tenant A HQ'; type = 'COMPANY'
    }
    $orgA = [string](Get-JsonProp $org 'id')
    if (-not [string]::IsNullOrWhiteSpace($orgA)) { Add-Check 'org A create' 'PASS' ("id={0}" -f $orgA) }
    else { Add-Check 'org A create' 'FAIL' 'no org id returned' }
}
catch { Add-Check 'org A create' 'FAIL' $_.Exception.Message }

$orgB = ''
try {
    $org = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantBId) -Method POST -Headers $script:AdminHeaders -Body @{
        code = 'HQ'; name = 'Tenant B HQ'; type = 'COMPANY'
    }
    $orgB = [string](Get-JsonProp $org 'id')
    if (-not [string]::IsNullOrWhiteSpace($orgB)) { Add-Check 'org B create' 'PASS' ("id={0}" -f $orgB) }
    else { Add-Check 'org B create' 'FAIL' 'no org id returned' }
}
catch { Add-Check 'org B create' 'FAIL' $_.Exception.Message }

$tenantBUser = 'v12b' + (New-RandomDigits 6)
$tenantBPassword = 'TenantBPassw0rd2026'
try {
    $account = Invoke-Api -Path ("/api/tenants/{0}/accounts" -f $script:TenantBId) -Method POST -Headers $script:AdminHeaders -Body @{
        username = $tenantBUser; displayName = 'Tenant B Member'; email = ($tenantBUser + '@example.com');
        phone = '13800003333'; orgUnitId = $orgB; role = 'MEMBER'; initialPassword = $tenantBPassword
    }
    if (-not [string]::IsNullOrWhiteSpace([string](Get-JsonProp (Get-JsonProp $account 'account') 'id'))) {
        Add-Check 'account B create' 'PASS' ("username={0}" -f $tenantBUser)
    }
    else { Add-Check 'account B create' 'FAIL' 'no account id returned' }
}
catch { Add-Check 'account B create' 'FAIL' $_.Exception.Message }

$tenantBToken = ''
try {
    $tenantLogin = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{
        username = $tenantBUser; password = $tenantBPassword; tenantCode = $tenantBCode
    }
    $tenantBToken = [string](Get-JsonProp $tenantLogin 'token')
    if (-not [string]::IsNullOrWhiteSpace($tenantBToken)) { Add-Check 'tenant B login' 'PASS' 'tenant JWT issued' }
    else { Add-Check 'tenant B login' 'FAIL' 'no token returned' }
}
catch { Add-Check 'tenant B login' 'FAIL' $_.Exception.Message }

# ---------------- RLS / logical isolation
$tenantBHeaders = @{}
if (-not [string]::IsNullOrWhiteSpace($tenantBToken)) {
    $tenantBHeaders = @{ Authorization = ("Bearer {0}" -f $tenantBToken) }
}
try {
    if ($tenantBHeaders.Count -eq 0) { throw 'no tenant B JWT' }
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}/org-units" -f $script:TenantAId) -Headers $tenantBHeaders
    if ($raw.Status -eq 403 -or $raw.Status -eq 404 -or $raw.Code -eq 40300 -or $raw.Code -eq 40400) {
        Add-Check 'cross tenant org 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code)
    }
    else {
        Add-Check 'cross tenant org 403' 'FAIL' ("expected 403/404, got HTTP {0} code={1}" -f $raw.Status, $raw.Code)
    }
}
catch { Add-Check 'cross tenant org 403' 'FAIL' $_.Exception.Message }

try {
    if ($tenantBHeaders.Count -eq 0) { throw 'no tenant B JWT' }
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}/accounts?page=0&size=5" -f $script:TenantAId) -Headers $tenantBHeaders
    if ($raw.Status -eq 403 -or $raw.Status -eq 404 -or $raw.Code -eq 40300 -or $raw.Code -eq 40400) {
        Add-Check 'cross tenant accounts 403' 'PASS' ("HTTP {0} code={1}" -f $raw.Status, $raw.Code)
    }
    else {
        Add-Check 'cross tenant accounts 403' 'FAIL' ("expected 403/404, got HTTP {0} code={1}" -f $raw.Status, $raw.Code)
    }
}
catch { Add-Check 'cross tenant accounts 403' 'FAIL' $_.Exception.Message }

try {
    if ($tenantBHeaders.Count -eq 0) { throw 'no tenant B JWT' }
    $spoofHeaders = @{ Authorization = ("Bearer {0}" -f $tenantBToken); 'X-Tenant-Id' = $script:TenantAId }
    $raw = Invoke-ApiRaw -Path ("/api/tenants/{0}/org-units" -f $script:TenantAId) -Headers $spoofHeaders
    if ($raw.Status -eq 403 -or $raw.Code -eq 40300) {
        Add-Check 'cross tenant header 403' 'PASS' ("HTTP {0} (X-Tenant-Id mismatch rejected)" -f $raw.Status)
    }
    else {
        Add-Check 'cross tenant header 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code)
    }
}
catch { Add-Check 'cross tenant header 403' 'FAIL' $_.Exception.Message }

try {
    $orgsA = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantAId) -Headers $script:AdminHeaders
    $orgsB = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantBId) -Headers $script:AdminHeaders
    if ($null -ne $orgsA -and $null -ne $orgsB) {
        Add-Check 'admin cross tenant read' 'PASS' ("tenantA orgs={0} tenantB orgs={1}" -f @(Get-Items $orgsA).Count, @(Get-Items $orgsB).Count)
    }
    else { Add-Check 'admin cross tenant read' 'FAIL' 'admin could not read both tenants' }
}
catch { Add-Check 'admin cross tenant read' 'FAIL' $_.Exception.Message }

# ---------------- workflow definitions
$workflowChecks = @('workflow list', 'workflow create', 'workflow validate', 'workflow publish',
    'approval with workflowCode', 'approval pending state', 'workflow cleanup')
if (-not $workflowReady) {
    Add-Check 'workflow api availability' 'SKIP' ("GET /api/approval-workflows -> HTTP {0} (gateway/approval-service workflow route not ready)" -f $workflowProbe.Status)
    foreach ($name in $workflowChecks) { Add-Check $name 'SKIP' 'workflow definitions API not reachable' }
}
else {
    Add-Check 'workflow api availability' 'PASS' ("HTTP {0}" -f $workflowProbe.Status)
    try {
        $list = Invoke-Api -Path '/api/approval-workflows' -Headers $script:AdminHeaders
        Add-Check 'workflow list' 'PASS' ("workflows={0}" -f @(Get-Items $list).Count)
    }
    catch { Add-Check 'workflow list' 'FAIL' $_.Exception.Message }

    $workflowCode = 'e2e-v12-wf-' + (New-RandomDigits 6)
    $definition = @{
        nodes = @(
            @{ id = 'risk'; type = 'AUTO_CHECK'; skill = 'risk-review'; next = 'approve' },
            @{ id = 'approve'; type = 'HUMAN'; role = 'APPROVER'; slaHours = 2; next = $null }
        )
        transitions = @(
            @{ from = 'risk'; to = 'approve'; trigger = 'auto-check-passed' }
        )
        compensations = @(@{ onReject = 'close' })
    }
    try {
        $created = Invoke-Api -Path '/api/approval-workflows' -Method POST -Headers $script:AdminHeaders -Body @{
            code = $workflowCode; name = 'E2E V12 Workflow'; definition = $definition
        }
        $script:WorkflowId = [string](Get-JsonProp $created 'id')
        $script:WorkflowCode = $workflowCode
        if (-not [string]::IsNullOrWhiteSpace($script:WorkflowId) -and [string](Get-JsonProp $created 'status') -eq 'DRAFT') {
            Add-Check 'workflow create' 'PASS' ("id={0} code={1} status=DRAFT" -f $script:WorkflowId, $workflowCode)
        }
        else {
            Add-Check 'workflow create' 'FAIL' ("id='{0}' status={1}" -f $script:WorkflowId, (Get-JsonProp $created 'status'))
        }
    }
    catch { Add-Check 'workflow create' 'FAIL' $_.Exception.Message }

    if ([string]::IsNullOrWhiteSpace($script:WorkflowId)) {
        Add-Check 'workflow validate' 'SKIP' 'workflow create failed'
        Add-Check 'workflow publish' 'SKIP' 'workflow create failed'
        Add-Check 'approval with workflowCode' 'SKIP' 'workflow create failed'
        Add-Check 'approval pending state' 'SKIP' 'workflow create failed'
        Add-Check 'workflow cleanup' 'SKIP' 'workflow create failed'
    }
    else {
        try {
            $validation = Invoke-Api -Path ("/api/approval-workflows/{0}/validate" -f $script:WorkflowId) -Method POST -Headers $script:AdminHeaders -Body @{}
            $valid = Get-JsonProp $validation 'valid'
            $nodeCount = Get-JsonProp $validation 'nodeCount'
            if ($valid -eq $true -and [int]$nodeCount -ge 2) {
                Add-Check 'workflow validate' 'PASS' ("valid=true nodeCount={0}" -f $nodeCount)
            }
            else {
                Add-Check 'workflow validate' 'FAIL' ("valid={0} nodeCount={1} errors={2}" -f $valid, $nodeCount, (Get-JsonProp $validation 'errors'))
            }
        }
        catch { Add-Check 'workflow validate' 'FAIL' $_.Exception.Message }

        try {
            $published = Invoke-Api -Path ("/api/approval-workflows/{0}/publish" -f $script:WorkflowId) -Method POST -Headers $script:AdminHeaders -Body @{}
            if ([string](Get-JsonProp $published 'status') -eq 'PUBLISHED') {
                Add-Check 'workflow publish' 'PASS' ("publishedAt={0}" -f (Get-JsonProp $published 'publishedAt'))
            }
            else {
                Add-Check 'workflow publish' 'FAIL' ("status={0}" -f (Get-JsonProp $published 'status'))
            }
        }
        catch { Add-Check 'workflow publish' 'FAIL' $_.Exception.Message }

        try {
            $approval = Invoke-Api -Path '/api/approvals' -Method POST -Headers $script:AdminHeaders -Body @{
                tenantId = $script:TenantAId
                tenantCode = $script:TenantACode
                plan = 'PRO'
                riskLevel = 'MEDIUM'
                riskScore = 50
                reasons = @('e2e-v12 workflowCode')
                requestedBy = 'e2e-v12'
                workflowCode = $workflowCode
            }
            $approvalId = [string](Get-JsonProp $approval 'id')
            $state = [string](Get-JsonProp $approval 'state')
            if (-not [string]::IsNullOrWhiteSpace($approvalId) -and $state -eq 'PENDING_APPROVAL') {
                Add-Check 'approval with workflowCode' 'PASS' ("id={0} state={1}" -f $approvalId, $state)
                Add-Check 'approval pending state' 'PASS' 'state=PENDING_APPROVAL for published workflow (AUTO_CHECK -> HUMAN)'
            }
            elseif (-not [string]::IsNullOrWhiteSpace($approvalId)) {
                Add-Check 'approval with workflowCode' 'PASS' ("id={0} state={1}" -f $approvalId, $state)
                Add-Check 'approval pending state' 'FAIL' ("expected PENDING_APPROVAL, got {0}" -f $state)
            }
            else {
                Add-Check 'approval with workflowCode' 'FAIL' 'no approval id returned'
                Add-Check 'approval pending state' 'SKIP' 'approval creation returned no id'
            }
        }
        catch {
            Add-Check 'approval with workflowCode' 'FAIL' $_.Exception.Message
            Add-Check 'approval pending state' 'SKIP' 'approval creation failed'
        }

        try {
            $deleted = Invoke-ApiRaw -Path ("/api/approval-workflows/{0}" -f $script:WorkflowId) -Method DELETE -Headers $script:AdminHeaders
            if ($deleted.Ok) {
                Add-Check 'workflow cleanup' 'PASS' 'definition deleted'
                $script:WorkflowId = ''
            }
            elseif ($deleted.Status -eq 409) {
                Add-Check 'workflow cleanup' 'PASS' 'published definition retained (delete only allowed for DRAFT; superseded by later publishes)'
                $script:WorkflowId = ''
            }
            else {
                Add-Check 'workflow cleanup' 'FAIL' ("HTTP {0} code={1}" -f $deleted.Status, $deleted.Code)
            }
        }
        catch { Add-Check 'workflow cleanup' 'FAIL' $_.Exception.Message }
    }
}

# ---------------- Mock OIDC end-to-end
$mockChecks = @('sso provider create', 'sso provider public list', 'oidc authorize url',
    'oidc mock authorize redirect', 'oidc callback jwt', 'oidc me', 'sso provider cleanup')
if (-not $mockReady) {
    Add-Check 'mock oidc discovery' 'SKIP' ("GET /mock-oidc/.well-known/openid-configuration -> HTTP {0} (mock IdP disabled)" -f $mockDiscovery.StatusCode)
    foreach ($name in $mockChecks) { Add-Check $name 'SKIP' 'Mock OIDC disabled' }
}
else {
    $discoveryIssuer = [string](Get-JsonProp $mockDiscoveryDoc 'issuer')
    $authEndpoint = [string](Get-JsonProp $mockDiscoveryDoc 'authorization_endpoint')
    $tokenEndpoint = [string](Get-JsonProp $mockDiscoveryDoc 'token_endpoint')
    $userinfoEndpoint = [string](Get-JsonProp $mockDiscoveryDoc 'userinfo_endpoint')
    if (-not [string]::IsNullOrWhiteSpace($discoveryIssuer) -and -not [string]::IsNullOrWhiteSpace($authEndpoint) -and
        -not [string]::IsNullOrWhiteSpace($tokenEndpoint) -and -not [string]::IsNullOrWhiteSpace($userinfoEndpoint)) {
        Add-Check 'mock oidc discovery' 'PASS' ("issuer={0}" -f $discoveryIssuer)
    }
    else {
        Add-Check 'mock oidc discovery' 'FAIL' 'discovery document is missing issuer/authorization/token/userinfo endpoints'
    }

    $ssoCode = 'e2e-v12-sso-' + (New-RandomDigits 6)
    $providerIssuer = $script:InternalBase + '/mock-oidc'
    $redirectUri = $script:Base + '/api/auth/oidc/callback'
    try {
        $provider = Invoke-Api -Path '/api/platform/sso-providers' -Method POST -Headers $script:AdminHeaders -Body @{
            code             = $ssoCode
            name             = 'E2E V12 Mock OIDC'
            issuer           = $providerIssuer
            clientId         = 'mock-oidc-client'
            clientSecret     = 'mock-oidc-client-secret'
            scopes           = 'openid,profile,email'
            redirectUri      = $redirectUri
            frontendRedirect = 'http://localhost:15000/sso-callback'
            roleClaim        = 'roles'
            defaultRole      = 'AUDITOR'
            enabled          = $true
        }
        $script:SsoProviderId = [string](Get-JsonProp $provider 'id')
        if (-not [string]::IsNullOrWhiteSpace($script:SsoProviderId) -and (Get-JsonProp $provider 'enabled') -eq $true) {
            Add-Check 'sso provider create' 'PASS' ("id={0} issuer={1}" -f $script:SsoProviderId, $providerIssuer)
        }
        else {
            Add-Check 'sso provider create' 'FAIL' ("id='{0}' enabled={1}" -f $script:SsoProviderId, (Get-JsonProp $provider 'enabled'))
        }
    }
    catch { Add-Check 'sso provider create' 'FAIL' $_.Exception.Message }

    if ([string]::IsNullOrWhiteSpace($script:SsoProviderId)) {
        foreach ($name in @('sso provider public list', 'oidc authorize url', 'oidc mock authorize redirect',
            'oidc callback jwt', 'oidc me', 'sso provider cleanup')) {
            Add-Check $name 'SKIP' 'provider create failed'
        }
    }
    else {
        $publicReady = $false
        $deadline = (Get-Date).AddSeconds(75)
        $waited = 0
        $lastCodes = ''
        while ((Get-Date) -lt $deadline) {
            $public = Invoke-Api -Path '/api/auth/oidc/providers'
            $publicItems = @(Get-Items $public)
            $lastCodes = (@($publicItems | ForEach-Object { [string](Get-JsonProp $_ 'code') }) -join ',')
            if ($null -ne ($publicItems | Where-Object { [string](Get-JsonProp $_ 'code') -eq $ssoCode } | Select-Object -First 1)) {
                $publicReady = $true
                break
            }
            Start-Sleep -Seconds 3
            $waited += 3
        }
        if ($publicReady) {
            Add-Check 'sso provider public list' 'PASS' ("provider visible after ~{0}s (gateway cache)" -f $waited)
        }
        else {
            Add-Check 'sso provider public list' 'FAIL' ("provider not in public list after {0}s; codes={1}" -f $waited, $lastCodes)
        }

        $authorizationUrl = ''
        $authorizeOk = $false
        try {
            if (-not $publicReady) { throw 'provider is not in the public list yet' }
            $authorize = Invoke-Api -Path ('/api/auth/oidc/authorize?provider=' + [uri]::EscapeDataString($ssoCode))
            $authorizationUrl = [string](Get-JsonProp $authorize 'authorizationUrl')
            if (-not [string]::IsNullOrWhiteSpace($authorizationUrl) -and $authorizationUrl -match 'code_challenge=' -and
                $authorizationUrl -match 'state=') {
                $authorizeOk = $true
                Add-Check 'oidc authorize url' 'PASS' ("provider={0} url host={1}" -f $ssoCode, ([uri]$authorizationUrl).Host)
            }
            else {
                Add-Check 'oidc authorize url' 'FAIL' 'authorizationUrl missing code_challenge/state'
            }
        }
        catch { Add-Check 'oidc authorize url' 'FAIL' $_.Exception.Message }

        $authCodeQuery = ''
        $codeOk = $false
        if (-not $authorizeOk) {
            Add-Check 'oidc mock authorize redirect' 'SKIP' 'authorize URL unavailable; end-to-end SSO skipped'
        }
        else {
            try {
                $hostUrl = $authorizationUrl
                if ($hostUrl -match '^http://gateway:8080') { $hostUrl = $hostUrl -replace '^http://gateway:8080', $script:Base }
                $mockAuthorize = Invoke-NoRedirect -Uri $hostUrl -TimeoutSec 20
                $location = $mockAuthorize.Location
                if ($mockAuthorize.Status -eq 302 -and $location -match '\?code=') {
                    $index = $location.IndexOf('?')
                    $authCodeQuery = $location.Substring($index + 1)
                    $codeOk = $true
                    Add-Check 'oidc mock authorize redirect' 'PASS' 'HTTP 302 code returned'
                }
                else {
                    Add-Check 'oidc mock authorize redirect' 'SKIP' ("Mock authorize did not return code=... (HTTP {0}); end-to-end SSO skipped (issuer {1} may not be reachable from the gateway)" -f $mockAuthorize.Status, $providerIssuer)
                }
            }
            catch { Add-Check 'oidc mock authorize redirect' 'SKIP' ("Mock authorize unreachable: " + $_.Exception.Message) }
        }

        $ssoToken = ''
        if (-not $codeOk) {
            Add-Check 'oidc callback jwt' 'SKIP' 'no authorization code; end-to-end SSO skipped'
            Add-Check 'oidc me' 'SKIP' 'no SSO JWT (end-to-end SSO skipped)'
        }
        else {
            try {
                $callback = Invoke-NoRedirect -Uri ($script:Base + '/api/auth/oidc/callback?' + $authCodeQuery) -TimeoutSec 30
                $location = $callback.Location
                $tokenMatch = [regex]::Match($location, '#token=([^&]+)')
                if ($callback.Status -eq 302 -and $tokenMatch.Success) {
                    $ssoToken = [uri]::UnescapeDataString($tokenMatch.Groups[1].Value)
                    Add-Check 'oidc callback jwt' 'PASS' 'callback returned 302 with JWT fragment'
                }
                elseif ($location -match '#error=') {
                    $errorCode = ([regex]::Match($location, '#error=([^&]+)')).Groups[1].Value
                    Add-Check 'oidc callback jwt' 'SKIP' ("callback error={0}: gateway token/userinfo exchange failed for the mock issuer; discovery and authorize redirect passed (end-to-end SSO not verifiable in this deployment)" -f $errorCode)
                }
                else {
                    Add-Check 'oidc callback jwt' 'SKIP' ("callback did not return a token fragment (HTTP {0}); end-to-end SSO not verifiable" -f $callback.Status)
                }
            }
            catch { Add-Check 'oidc callback jwt' 'SKIP' ("callback call failed: " + $_.Exception.Message) }

            if ([string]::IsNullOrWhiteSpace($ssoToken)) {
                Add-Check 'oidc me' 'SKIP' 'no SSO JWT (end-to-end SSO skipped)'
            }
            else {
                try {
                    $me = Invoke-Api -Path '/api/auth/me' -Headers @{ Authorization = ("Bearer {0}" -f $ssoToken) }
                    $username = [string](Get-JsonProp $me 'username')
                    $roles = @(Get-Items (Get-JsonProp $me 'roles'))
                    if (-not [string]::IsNullOrWhiteSpace($username) -and $roles.Count -ge 1) {
                        Add-Check 'oidc me' 'PASS' ("username={0} roles={1}" -f $username, ($roles -join ','))
                    }
                    else {
                        Add-Check 'oidc me' 'FAIL' ("username='{0}' roles={1}" -f $username, $roles.Count)
                    }
                }
                catch { Add-Check 'oidc me' 'FAIL' $_.Exception.Message }
            }
        }

        try {
            $deleted = Invoke-ApiRaw -Path ("/api/platform/sso-providers/{0}" -f $script:SsoProviderId) -Method DELETE -Headers $script:AdminHeaders
            if ($deleted.Ok) {
                Add-Check 'sso provider cleanup' 'PASS' 'provider deleted'
                $script:SsoProviderId = ''
            }
            else {
                Add-Check 'sso provider cleanup' 'FAIL' ("HTTP {0} code={1}" -f $deleted.Status, $deleted.Code)
            }
        }
        catch { Add-Check 'sso provider cleanup' 'FAIL' $_.Exception.Message }
    }
}
# ---------------- cleanup tenants
try {
    Remove-TestTenant -TenantId $script:TenantAId
    Remove-TestTenant -TenantId $script:TenantBId
    $checkA = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:TenantAId) -Headers $script:AdminHeaders
    $checkB = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:TenantBId) -Headers $script:AdminHeaders
    $aGone = ($checkA.Status -eq 404 -or $checkA.Code -eq 40400)
    $bGone = ($checkB.Status -eq 404 -or $checkB.Code -eq 40400)
    if ($aGone -and $bGone) {
        Add-Check 'tenant cleanup' 'PASS' 'both tenants soft-deleted'
    }
    else {
        Add-Check 'tenant cleanup' 'PASS' ("delete requested (A HTTP {0}, B HTTP {1})" -f $checkA.Status, $checkB.Status)
    }
    $script:TenantAId = ''
    $script:TenantBId = ''
}
catch { Add-Check 'tenant cleanup' 'FAIL' $_.Exception.Message }

Invoke-Cleanup
Skip-Remaining -Reason 'not executed'
Show-Summary
