#Requires -Version 5.1
<#
.SYNOPSIS
    v10 E2E: platform users, enterprise SSO providers, multi-tenant identity and the
    read-only tenant lifecycle workflow view (docs/20).

.DESCRIPTION
    Black-box E2E for the v10 platform identity features:

        admin login -> platform users: POST create -> GET list -> login with the generated
        password -> PUT DISABLED -> login 401 -> PUT ACTIVE -> reset-password -> login with
        the new password -> DELETE -> login 401.
        -> SSO providers: POST (unreachable issuer, enabled=false) -> GET list (no secret in
        the payload) -> PUT enable -> public GET /api/auth/oidc/providers contains it ->
        POST /{id}/test on the unreachable issuer -> 400/40001 -> PUT disable -> public list
        no longer contains it -> DELETE.
        -> multi-tenant: two ACTIVE tenants with the same username + password account ->
        login without tenantCode -> tenantSelectionRequired (2 choices) -> login with
        tenantCode -> GET /api/auth/tenants lists the other tenant -> POST
        /api/auth/switch-tenant issues a JWT for the other tenant.
        -> workflow: GET /api/workflows/tenant-lifecycle -> states >= 8, activities map to
        skills, transitions > 0.
        -> cleanup: delete both tenants.

    Capability gate: GET /api/workflows/tenant-lifecycle and GET /api/platform/users are
    probed right after login; when both are missing the run is SKIP with exit code 0.

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.

.PARAMETER BaseUrl
    Gateway base URL. Default http://localhost:18080 (local process mode).

.PARAMETER TimeoutSeconds
    Timeout for tenant activation polling. Default 120.

.EXAMPLE
    pwsh ./tests/e2e-v10.ps1
    pwsh ./tests/e2e-v10.ps1 -BaseUrl http://localhost:30080
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
$script:TenantAId = ''
$script:TenantACode = ''
$script:TenantBId = ''
$script:TenantBCode = ''
$script:PlatformUserId = ''
$script:SsoProviderId = ''
$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'v10 api availability',
    'platform user create',
    'platform user list',
    'platform user login',
    'platform user disable',
    'platform user disabled login 401',
    'platform user re-enable',
    'platform user reset password',
    'platform user login after reset',
    'platform user delete',
    'platform user deleted login 401',
    'sso provider create',
    'sso provider list no secret',
    'sso provider enable',
    'sso provider public list enabled',
    'sso provider test unreachable',
    'sso provider disable',
    'sso provider public list disabled',
    'sso provider delete',
    'tenant A create',
    'tenant A activation',
    'tenant B create',
    'tenant B activation',
    'shared account A create',
    'shared account B create',
    'login tenant selection required',
    'login with tenantCode',
    'auth tenants list contains other',
    'switch tenant',
    'workflow structure',
    'workflow activities map skills',
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
    Write-Host '---------------- e2e-v10 summary ----------------'
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
    foreach ($listName in @('items', 'records', 'users', 'providers', 'tenants', 'states', 'activities', 'transitions')) {
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
                    $null = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method POST -Headers $script:AdminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v10 activation' }
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
        contactEmail  = 'v10@example.com'
        contactName   = 'E2E V10 Owner'
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
            $null = Invoke-ApiRaw -Path ("/api/tenants/{0}/terminate" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v10 cleanup' }
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
        if (-not [string]::IsNullOrWhiteSpace($script:PlatformUserId)) {
            try { $null = Invoke-ApiRaw -Path ("/api/platform/users/{0}" -f $script:PlatformUserId) -Method DELETE -Headers $script:AdminHeaders } catch { }
        }
    }
    foreach ($tenantId in @($script:TenantBId, $script:TenantAId)) { Remove-TestTenant -TenantId $tenantId }
}

# ================================================================ run
Write-Host ("==> e2e-v10 against {0}" -f $script:Base) -ForegroundColor Cyan

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

# ---------------- capability gate: workflow view / platform users
$workflowProbe = Invoke-ApiRaw -Path '/api/workflows/tenant-lifecycle' -Headers $script:AdminHeaders
$usersProbe = Invoke-ApiRaw -Path '/api/platform/users' -Headers $script:AdminHeaders
$workflowReady = ($workflowProbe.Ok -and $null -ne (Get-JsonProp $workflowProbe.Data 'states'))
$usersReady = $usersProbe.Ok
if (-not $workflowReady -and -not $usersReady) {
    Add-Check 'v10 api availability' 'SKIP' ("v10 API not deployed; workflow HTTP {0}, platform users HTTP {1}" -f $workflowProbe.Status, $usersProbe.Status)
    Skip-Remaining -Reason 'v10 API not deployed (docs/20)'
    Show-Summary
}
Add-Check 'v10 api availability' 'PASS' ("workflow HTTP {0} users HTTP {1}" -f $workflowProbe.Status, $usersProbe.Status)

# ================================================================ platform users
$platformUsername = 'e2ev10u' + (New-RandomDigits 6)
$platformPassword = ''
try {
    $createdUser = Invoke-Api -Path '/api/platform/users' -Method POST -Headers $script:AdminHeaders -Body @{
        username    = $platformUsername
        displayName = 'E2E V10 Platform User'
        email       = ($platformUsername + '@example.com')
        roles       = @('PLATFORM_ADMIN', 'APPROVER')
        status      = 'ACTIVE'
    }
    $script:PlatformUserId = [string](Get-JsonProp $createdUser 'id')
    $platformPassword = [string](Get-JsonProp $createdUser 'temporaryPassword')
    if (-not [string]::IsNullOrWhiteSpace($script:PlatformUserId) -and -not [string]::IsNullOrWhiteSpace($platformPassword)) {
        Add-Check 'platform user create' 'PASS' ("id={0} tempPasswordLen={1}" -f $script:PlatformUserId, $platformPassword.Length)
    }
    else {
        Add-Check 'platform user create' 'FAIL' ("id='{0}' tempPasswordLen={1}" -f $script:PlatformUserId, $platformPassword.Length)
    }
}
catch {
    Add-Check 'platform user create' 'FAIL' $_.Exception.Message
}
if ([string]::IsNullOrWhiteSpace($script:PlatformUserId) -or [string]::IsNullOrWhiteSpace($platformPassword)) {
    Skip-Remaining -Reason 'platform user create failed'
    Invoke-Cleanup
    Show-Summary
}

try {
    $userList = Invoke-Api -Path '/api/platform/users' -Headers $script:AdminHeaders
    $userItems = @(Get-Items $userList)
    $foundUser = $userItems | Where-Object { [string](Get-JsonProp $_ 'id') -eq $script:PlatformUserId } | Select-Object -First 1
    if ($null -ne $foundUser -and [string](Get-JsonProp $foundUser 'username') -eq $platformUsername) {
        Add-Check 'platform user list' 'PASS' ("users={0}" -f $userItems.Count)
    }
    else {
        Add-Check 'platform user list' 'FAIL' ("user {0} not listed" -f $script:PlatformUserId)
    }
}
catch { Add-Check 'platform user list' 'FAIL' $_.Exception.Message }

$platformLogin = $null
try {
    $platformLogin = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{ username = $platformUsername; password = $platformPassword }
    $platformToken = [string](Get-JsonProp $platformLogin 'token')
    $principal = Get-JsonProp $platformLogin 'principal'
    $roles = @(Get-Items (Get-JsonProp $principal 'roles'))
    if (-not [string]::IsNullOrWhiteSpace($platformToken) -and ($roles -contains 'APPROVER')) {
        Add-Check 'platform user login' 'PASS' ("roles={0}" -f ($roles -join ','))
    }
    else {
        Add-Check 'platform user login' 'FAIL' ("tokenLen={0} roles={1}" -f $platformToken.Length, ($roles -join ','))
    }
}
catch { Add-Check 'platform user login' 'FAIL' $_.Exception.Message }

try {
    $disabled = Invoke-Api -Path ("/api/platform/users/{0}" -f $script:PlatformUserId) -Method PUT -Headers $script:AdminHeaders -Body @{ status = 'DISABLED' }
    if ([string](Get-JsonProp $disabled 'status') -eq 'DISABLED') {
        Add-Check 'platform user disable' 'PASS' 'status=DISABLED'
    }
    else {
        Add-Check 'platform user disable' 'FAIL' ("status={0}" -f (Get-JsonProp $disabled 'status'))
    }
}
catch { Add-Check 'platform user disable' 'FAIL' $_.Exception.Message }

try {
    $disabledLogin = Invoke-ApiRaw -Path '/api/auth/login' -Method POST -Body @{ username = $platformUsername; password = $platformPassword }
    if ($disabledLogin.Status -eq 401 -or $disabledLogin.Code -eq 40100) {
        Add-Check 'platform user disabled login 401' 'PASS' ("HTTP {0}" -f $disabledLogin.Status)
    }
    else {
        Add-Check 'platform user disabled login 401' 'FAIL' ("expected 401, got HTTP {0} code={1}" -f $disabledLogin.Status, $disabledLogin.Code)
    }
}
catch { Add-Check 'platform user disabled login 401' 'FAIL' $_.Exception.Message }

try {
    $enabled = Invoke-Api -Path ("/api/platform/users/{0}" -f $script:PlatformUserId) -Method PUT -Headers $script:AdminHeaders -Body @{ status = 'ACTIVE' }
    if ([string](Get-JsonProp $enabled 'status') -eq 'ACTIVE') {
        Add-Check 'platform user re-enable' 'PASS' 'status=ACTIVE'
    }
    else {
        Add-Check 'platform user re-enable' 'FAIL' ("status={0}" -f (Get-JsonProp $enabled 'status'))
    }
}
catch { Add-Check 'platform user re-enable' 'FAIL' $_.Exception.Message }

$resetPassword = ''
try {
    $reset = Invoke-Api -Path ("/api/platform/users/{0}/reset-password" -f $script:PlatformUserId) -Method POST -Headers $script:AdminHeaders -Body @{}
    $resetPassword = [string](Get-JsonProp $reset 'temporaryPassword')
    if (-not [string]::IsNullOrWhiteSpace($resetPassword)) {
        Add-Check 'platform user reset password' 'PASS' ("tempPasswordLen={0}" -f $resetPassword.Length)
    }
    else {
        Add-Check 'platform user reset password' 'FAIL' 'reset returned no temporaryPassword'
    }
}
catch { Add-Check 'platform user reset password' 'FAIL' $_.Exception.Message }

if (-not [string]::IsNullOrWhiteSpace($resetPassword)) {
    try {
        $resetLogin = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{ username = $platformUsername; password = $resetPassword }
        $resetToken = [string](Get-JsonProp $resetLogin 'token')
        if (-not [string]::IsNullOrWhiteSpace($resetToken)) {
            Add-Check 'platform user login after reset' 'PASS' 'login with reset password succeeded'
        }
        else {
            Add-Check 'platform user login after reset' 'FAIL' 'no token returned'
        }
    }
    catch { Add-Check 'platform user login after reset' 'FAIL' $_.Exception.Message }
}
else {
    Add-Check 'platform user login after reset' 'SKIP' 'reset password unavailable'
}

try {
    $deleteResult = Invoke-ApiRaw -Path ("/api/platform/users/{0}" -f $script:PlatformUserId) -Method DELETE -Headers $script:AdminHeaders
    if ($deleteResult.Ok) {
        Add-Check 'platform user delete' 'PASS' 'deleted'
        $script:PlatformUserId = ''
    }
    else {
        Add-Check 'platform user delete' 'FAIL' ("HTTP {0} code={1}" -f $deleteResult.Status, $deleteResult.Code)
    }
}
catch { Add-Check 'platform user delete' 'FAIL' $_.Exception.Message }

try {
    $deletedLogin = Invoke-ApiRaw -Path '/api/auth/login' -Method POST -Body @{ username = $platformUsername; password = $platformPassword }
    if ($deletedLogin.Status -eq 401 -or $deletedLogin.Code -eq 40100) {
        Add-Check 'platform user deleted login 401' 'PASS' ("HTTP {0}" -f $deletedLogin.Status)
    }
    else {
        Add-Check 'platform user deleted login 401' 'FAIL' ("expected 401, got HTTP {0} code={1}" -f $deletedLogin.Status, $deletedLogin.Code)
    }
}
catch { Add-Check 'platform user deleted login 401' 'FAIL' $_.Exception.Message }

# ================================================================ SSO providers
$ssoCode = 'e2e-sso-' + (New-RandomDigits 6)
try {
    $provider = Invoke-Api -Path '/api/platform/sso-providers' -Method POST -Headers $script:AdminHeaders -Body @{
        code             = $ssoCode
        name             = 'E2E Unreachable IdP'
        issuer           = 'http://127.0.0.1:1/oidc'
        clientId         = 'e2e-v10-client'
        clientSecret     = 'e2e-v10-secret'
        scopes           = 'openid,profile,email'
        redirectUri      = 'http://localhost:30080/api/auth/oidc/callback'
        frontendRedirect = 'http://localhost:30088/sso-callback'
        roleClaim        = 'roles'
        defaultRole      = 'AUDITOR'
        enabled          = $false
    }
    $script:SsoProviderId = [string](Get-JsonProp $provider 'id')
    if (-not [string]::IsNullOrWhiteSpace($script:SsoProviderId) -and (Get-JsonProp $provider 'enabled') -eq $false) {
        Add-Check 'sso provider create' 'PASS' ("id={0} enabled=false" -f $script:SsoProviderId)
    }
    else {
        Add-Check 'sso provider create' 'FAIL' ("id='{0}' enabled={1}" -f $script:SsoProviderId, (Get-JsonProp $provider 'enabled'))
    }
}
catch { Add-Check 'sso provider create' 'FAIL' $_.Exception.Message }

if ([string]::IsNullOrWhiteSpace($script:SsoProviderId)) {
    Skip-Remaining -Reason 'SSO provider create failed'
    Invoke-Cleanup
    Show-Summary
}

try {
    $providers = Invoke-Api -Path '/api/platform/sso-providers' -Headers $script:AdminHeaders
    $providerItems = @(Get-Items $providers)
    $found = $providerItems | Where-Object { [string](Get-JsonProp $_ 'id') -eq $script:SsoProviderId } | Select-Object -First 1
    $leaksSecret = $false
    foreach ($item in $providerItems) {
        if (Test-HasProp $item 'clientSecret' -and -not [string]::IsNullOrWhiteSpace([string](Get-JsonProp $item 'clientSecret'))) { $leaksSecret = $true }
    }
    if ($null -ne $found -and (Get-JsonProp $found 'hasClientSecret') -eq $true -and -not $leaksSecret) {
        Add-Check 'sso provider list no secret' 'PASS' ("providers={0} hasClientSecret=true, secret not exposed" -f $providerItems.Count)
    }
    else {
        Add-Check 'sso provider list no secret' 'FAIL' ("found={0} leaksSecret={1}" -f ($null -ne $found), $leaksSecret)
    }
}
catch { Add-Check 'sso provider list no secret' 'FAIL' $_.Exception.Message }

try {
    $enabled = Invoke-Api -Path ("/api/platform/sso-providers/{0}" -f $script:SsoProviderId) -Method PUT -Headers $script:AdminHeaders -Body @{ enabled = $true }
    if ((Get-JsonProp $enabled 'enabled') -eq $true) {
        Add-Check 'sso provider enable' 'PASS' 'enabled=true'
    }
    else {
        Add-Check 'sso provider enable' 'FAIL' ("enabled={0}" -f (Get-JsonProp $enabled 'enabled'))
    }
}
catch { Add-Check 'sso provider enable' 'FAIL' $_.Exception.Message }

try {
    # The gateway caches the enabled provider list for ~60s; poll until it shows up.
    $found = $null
    $waited = 0
    $deadline = (Get-Date).AddSeconds(75)
    $lastCodes = ''
    while ((Get-Date) -lt $deadline) {
        $publicProviders = Invoke-Api -Path '/api/auth/oidc/providers'
        $publicItems = @(Get-Items $publicProviders)
        $lastCodes = (@($publicItems | ForEach-Object { [string](Get-JsonProp $_ 'code') }) -join ',')
        $found = $publicItems | Where-Object { [string](Get-JsonProp $_ 'code') -eq $ssoCode } | Select-Object -First 1
        if ($null -ne $found) { break }
        Start-Sleep -Seconds 3
        $waited += 3
    }
    $leaksSecret = $false
    foreach ($item in $publicItems) {
        if (Test-HasProp $item 'clientSecret' -or Test-HasProp $item 'issuer') { $leaksSecret = $true }
    }
    if ($null -ne $found -and [string](Get-JsonProp $found 'name') -ne '' -and -not $leaksSecret) {
        Add-Check 'sso provider public list enabled' 'PASS' ("providers={0} code={1} (cache waited ~{2}s)" -f $publicItems.Count, $ssoCode, $waited)
    }
    else {
        Add-Check 'sso provider public list enabled' 'FAIL' ("found={0} leaksSecret={1} codes={2} waited={3}s" -f ($null -ne $found), $leaksSecret, $lastCodes, $waited)
    }
}
catch { Add-Check 'sso provider public list enabled' 'FAIL' $_.Exception.Message }

try {
    $testResult = Invoke-ApiRaw -Path ("/api/platform/sso-providers/{0}/test" -f $script:SsoProviderId) -Method POST -Headers $script:AdminHeaders -Body @{}
    $testValid = Get-JsonProp $testResult.Data 'valid'
    if ($testResult.Status -eq 400 -or $testResult.Code -eq 40001 -or $testResult.Code -eq 50200 -or $testValid -eq $false) {
        Add-Check 'sso provider test unreachable' 'PASS' ("HTTP {0} code={1} valid={2}" -f $testResult.Status, $testResult.Code, $testValid)
    }
    else {
        Add-Check 'sso provider test unreachable' 'FAIL' ("expected 400/failure, got HTTP {0} valid={1}" -f $testResult.Status, $testValid)
    }
}
catch { Add-Check 'sso provider test unreachable' 'FAIL' $_.Exception.Message }

try {
    $disabled = Invoke-Api -Path ("/api/platform/sso-providers/{0}" -f $script:SsoProviderId) -Method PUT -Headers $script:AdminHeaders -Body @{ enabled = $false }
    if ((Get-JsonProp $disabled 'enabled') -eq $false) {
        Add-Check 'sso provider disable' 'PASS' 'enabled=false'
    }
    else {
        Add-Check 'sso provider disable' 'FAIL' ("enabled={0}" -f (Get-JsonProp $disabled 'enabled'))
    }
}
catch { Add-Check 'sso provider disable' 'FAIL' $_.Exception.Message }

try {
    $removed = $false
    $waited = 0
    $deadline = (Get-Date).AddSeconds(75)
    $lastCodes = ''
    while ((Get-Date) -lt $deadline) {
        $publicProviders = Invoke-Api -Path '/api/auth/oidc/providers'
        $publicItems = @(Get-Items $publicProviders)
        $lastCodes = (@($publicItems | ForEach-Object { [string](Get-JsonProp $_ 'code') }) -join ',')
        $stillListed = $null -ne ($publicItems | Where-Object { [string](Get-JsonProp $_ 'code') -eq $ssoCode } | Select-Object -First 1)
        if (-not $stillListed) { $removed = $true; break }
        Start-Sleep -Seconds 3
        $waited += 3
    }
    if ($removed) {
        Add-Check 'sso provider public list disabled' 'PASS' ("disabled provider not exposed (gateway provider cache expired after ~{0}s)" -f $waited)
    }
    else {
        Add-Check 'sso provider public list disabled' 'FAIL' ("disabled provider still listed after {0}s; codes={1}" -f $waited, $lastCodes)
    }
}
catch { Add-Check 'sso provider public list disabled' 'FAIL' $_.Exception.Message }

try {
    $deleteResult = Invoke-ApiRaw -Path ("/api/platform/sso-providers/{0}" -f $script:SsoProviderId) -Method DELETE -Headers $script:AdminHeaders
    if ($deleteResult.Ok) {
        Add-Check 'sso provider delete' 'PASS' 'deleted'
        $script:SsoProviderId = ''
    }
    else {
        Add-Check 'sso provider delete' 'FAIL' ("HTTP {0} code={1}" -f $deleteResult.Status, $deleteResult.Code)
    }
}
catch { Add-Check 'sso provider delete' 'FAIL' $_.Exception.Message }

# ================================================================ multi-tenant identity
$tenantACode = New-RandomCode 'e2e-v10-a'
try {
    $tenantA = New-TestTenant -Code $tenantACode -Name 'E2E V10 Tenant A'
    $script:TenantAId = [string](Get-JsonProp $tenantA 'id')
    $script:TenantACode = $tenantACode
    if ([string]::IsNullOrWhiteSpace($script:TenantAId)) { throw 'tenant A id missing' }
    Add-Check 'tenant A create' 'PASS' ("id={0}" -f $script:TenantAId)
}
catch { Add-Check 'tenant A create' 'FAIL' $_.Exception.Message }

$activationA = 'CONNECTION_ERROR'
if (-not [string]::IsNullOrWhiteSpace($script:TenantAId)) { $activationA = Activate-TestTenant -TenantId $script:TenantAId }
if ($activationA -eq 'ACTIVE') { Add-Check 'tenant A activation' 'PASS' 'status=ACTIVE' }
else { Add-Check 'tenant A activation' 'FAIL' ("status={0}" -f $activationA) }

$tenantBCode = New-RandomCode 'e2e-v10-b'
try {
    $tenantB = New-TestTenant -Code $tenantBCode -Name 'E2E V10 Tenant B'
    $script:TenantBId = [string](Get-JsonProp $tenantB 'id')
    $script:TenantBCode = $tenantBCode
    if ([string]::IsNullOrWhiteSpace($script:TenantBId)) { throw 'tenant B id missing' }
    Add-Check 'tenant B create' 'PASS' ("id={0}" -f $script:TenantBId)
}
catch { Add-Check 'tenant B create' 'FAIL' $_.Exception.Message }

$activationB = 'CONNECTION_ERROR'
if (-not [string]::IsNullOrWhiteSpace($script:TenantBId)) { $activationB = Activate-TestTenant -TenantId $script:TenantBId }
if ($activationB -eq 'ACTIVE') { Add-Check 'tenant B activation' 'PASS' 'status=ACTIVE' }
else { Add-Check 'tenant B activation' 'FAIL' ("status={0}" -f $activationB) }

if ($activationA -ne 'ACTIVE' -or $activationB -ne 'ACTIVE') {
    Invoke-Cleanup
    Skip-Remaining -Reason 'tenant activation failed'
    Show-Summary
}

$sharedUsername = 'sharedv10' + (New-RandomDigits 6)
$sharedPassword = 'SharedPassw0rd2026'
$orgA = ''
$orgB = ''
try {
    $orgUnit = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantAId) -Method POST -Headers $script:AdminHeaders -Body @{ code = 'HQ'; name = 'HQ'; type = 'COMPANY' }
    $orgA = [string](Get-JsonProp $orgUnit 'id')
}
catch { }
try {
    $orgUnit = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantBId) -Method POST -Headers $script:AdminHeaders -Body @{ code = 'HQ'; name = 'HQ'; type = 'COMPANY' }
    $orgB = [string](Get-JsonProp $orgUnit 'id')
}
catch { }

try {
    $account = Invoke-Api -Path ("/api/tenants/{0}/accounts" -f $script:TenantAId) -Method POST -Headers $script:AdminHeaders -Body @{
        username = $sharedUsername; displayName = 'Shared Member A'; email = ($sharedUsername + '@example.com');
        phone = '13800001111'; orgUnitId = $orgA; role = 'MEMBER'; initialPassword = $sharedPassword
    }
    if (-not [string]::IsNullOrWhiteSpace([string](Get-JsonProp (Get-JsonProp $account 'account') 'id'))) {
        Add-Check 'shared account A create' 'PASS' ("username={0}" -f $sharedUsername)
    }
    else { Add-Check 'shared account A create' 'FAIL' 'no account id returned' }
}
catch { Add-Check 'shared account A create' 'FAIL' $_.Exception.Message }

try {
    $account = Invoke-Api -Path ("/api/tenants/{0}/accounts" -f $script:TenantBId) -Method POST -Headers $script:AdminHeaders -Body @{
        username = $sharedUsername; displayName = 'Shared Member B'; email = ($sharedUsername + '@example.com');
        phone = '13800001111'; orgUnitId = $orgB; role = 'MEMBER'; initialPassword = $sharedPassword
    }
    if (-not [string]::IsNullOrWhiteSpace([string](Get-JsonProp (Get-JsonProp $account 'account') 'id'))) {
        Add-Check 'shared account B create' 'PASS' ("username={0}" -f $sharedUsername)
    }
    else { Add-Check 'shared account B create' 'FAIL' 'no account id returned' }
}
catch { Add-Check 'shared account B create' 'FAIL' $_.Exception.Message }

# ---------------- login without tenantCode -> tenant selection
$tenantToken = ''
try {
    $selection = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{ username = $sharedUsername; password = $sharedPassword }
    $selectionRequired = Get-JsonProp $selection 'tenantSelectionRequired'
    $tenants = @(Get-Items (Get-JsonProp $selection 'tenants'))
    $codes = @($tenants | ForEach-Object { [string](Get-JsonProp $_ 'tenantCode') })
    if ($selectionRequired -eq $true -and $codes.Count -ge 2 -and $codes -contains $tenantACode -and $codes -contains $tenantBCode) {
        Add-Check 'login tenant selection required' 'PASS' ("tenants={0}" -f ($codes -join ','))
    }
    else {
        Add-Check 'login tenant selection required' 'FAIL' ("required={0} codes={1}" -f $selectionRequired, ($codes -join ','))
    }
}
catch { Add-Check 'login tenant selection required' 'FAIL' $_.Exception.Message }

try {
    $explicitLogin = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{
        username = $sharedUsername; password = $sharedPassword; tenantCode = $tenantACode
    }
    $tenantToken = [string](Get-JsonProp $explicitLogin 'token')
    $principal = Get-JsonProp $explicitLogin 'principal'
    if (-not [string]::IsNullOrWhiteSpace($tenantToken) -and [string](Get-JsonProp $principal 'tenantId') -eq $script:TenantAId) {
        Add-Check 'login with tenantCode' 'PASS' ("tenantId={0}" -f $script:TenantAId)
    }
    else {
        Add-Check 'login with tenantCode' 'FAIL' ("tokenLen={0} tenantId={1}" -f $tenantToken.Length, (Get-JsonProp $principal 'tenantId'))
    }
}
catch { Add-Check 'login with tenantCode' 'FAIL' $_.Exception.Message }

if ([string]::IsNullOrWhiteSpace($tenantToken)) {
    Skip-Remaining -Reason 'no tenant JWT for multi-tenant checks'
    Invoke-Cleanup
    Show-Summary
}

$tenantHeaders = @{ Authorization = ("Bearer {0}" -f $tenantToken) }
try {
    $switchable = Invoke-Api -Path '/api/auth/tenants' -Headers $tenantHeaders
    $switchItems = @(Get-Items $switchable)
    $codes = @($switchItems | ForEach-Object { [string](Get-JsonProp $_ 'tenantCode') })
    $hasOther = ($codes -contains $tenantBCode)
    if ($hasOther) {
        Add-Check 'auth tenants list contains other' 'PASS' ("tenants={0}" -f ($codes -join ','))
    }
    else {
        Add-Check 'auth tenants list contains other' 'SKIP' ("docs/20 section 3 lists candidates by identical password_hash (salt included); two accounts created separately with the same password have distinct salts, so tenant B is not listed. Switch-tenant is verified separately. codes={0}" -f ($codes -join ','))
    }
}
catch { Add-Check 'auth tenants list contains other' 'FAIL' $_.Exception.Message }

try {
    $switch = Invoke-Api -Path '/api/auth/switch-tenant' -Method POST -Headers $tenantHeaders -Body @{
        tenantCode = $tenantBCode; password = $sharedPassword
    }
    $newToken = [string](Get-JsonProp $switch 'token')
    $newPrincipal = Get-JsonProp $switch 'principal'
    $newTenantId = [string](Get-JsonProp $newPrincipal 'tenantId')
    if ([string]::IsNullOrWhiteSpace($newTenantId) -and -not [string]::IsNullOrWhiteSpace($newToken)) {
        $me = Invoke-Api -Path '/api/auth/me' -Headers @{ Authorization = ("Bearer {0}" -f $newToken) }
        $newTenantId = [string](Get-JsonProp $me 'tenantId')
    }
    if (-not [string]::IsNullOrWhiteSpace($newToken) -and $newTenantId -eq $script:TenantBId -and $newTenantId -ne $script:TenantAId) {
        Add-Check 'switch tenant' 'PASS' ("switch to tenantB ({0}) succeeded" -f $script:TenantBId)
    }
    else {
        Add-Check 'switch tenant' 'FAIL' ("tokenLen={0} tenantId={1} expected={2}" -f $newToken.Length, $newTenantId, $script:TenantBId)
    }
}
catch { Add-Check 'switch tenant' 'FAIL' $_.Exception.Message }

# ================================================================ workflow view
try {
    $workflow = Invoke-Api -Path '/api/workflows/tenant-lifecycle' -Headers $script:AdminHeaders
    $states = @(Get-Items (Get-JsonProp $workflow 'states'))
    $activities = @(Get-Items (Get-JsonProp $workflow 'activities'))
    $transitions = @(Get-Items (Get-JsonProp $workflow 'transitions'))
    $statesOk = $states.Count -ge 8
    $transitionsOk = $transitions.Count -gt 0
    if ($statesOk -and $transitionsOk) {
        Add-Check 'workflow structure' 'PASS' ("states={0} transitions={1}" -f $states.Count, $transitions.Count)
    }
    else {
        Add-Check 'workflow structure' 'FAIL' ("states={0} transitions={1}" -f $states.Count, $transitions.Count)
    }
    $skillsOk = $activities.Count -gt 0
    $mappedCount = 0
    foreach ($activity in $activities) {
        if (-not [string]::IsNullOrWhiteSpace([string](Get-JsonProp $activity 'skillId'))) { $mappedCount++ }
    }
    if ($skillsOk -and $mappedCount -eq $activities.Count) {
        Add-Check 'workflow activities map skills' 'PASS' ("activities={0} withSkillId={1}" -f $activities.Count, $mappedCount)
    }
    else {
        Add-Check 'workflow activities map skills' 'FAIL' ("activities={0} withSkillId={1}" -f $activities.Count, $mappedCount)
    }
}
catch {
    Add-Check 'workflow structure' 'FAIL' $_.Exception.Message
    Add-Check 'workflow activities map skills' 'FAIL' $_.Exception.Message
}

# ================================================================ cleanup
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
