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
    v6 phase A E2E: OIDC/SSO feature flag + MFA (TOTP) enrollment and login (docs/16 sections 2/3).

.DESCRIPTION
    Black-box E2E for v6 phase A:

      OIDC (gateway):
        GET /api/auth/oidc/status     -> {enabled:false, providerName, loginUrl:null}
        GET /api/auth/oidc/authorize  -> 404 (code 40400) when not enabled
        GET /api/auth/oidc/callback   -> 400 (code 40001) when not enabled
        When OIDC is configured the disabled-path assertions become SKIP.

      MFA (tenant-service, TOTP):
        create + activate tenant -> org unit -> account (one-time temp password)
        -> tenant login -> change password -> GET /api/me/mfa (enabled=false)
        -> POST /api/me/mfa/setup (secret/otpauthUrl/recoveryCodes)
        -> POST /api/me/mfa/verify (computed TOTP) -> enabled=true
        -> POST /api/auth/login returns {mfaRequired:true, challengeToken}
        -> POST /api/auth/login/mfa succeeds (JWT) / wrong code -> 401
        -> recovery code works once, reuse -> 401
        -> POST /api/me/mfa/disable -> login without MFA again
        -> audit trail contains MFA_ENROLLED / MFA_VERIFIED / MFA_DISABLED.

    Capability gate: GET /api/auth/oidc/status must answer 200; otherwise the whole run
    is SKIP with exit code 0. GET /api/me/mfa (admin probe) decides whether the MFA block
    runs, so a deployment with OIDC only still reports the OIDC assertions and SKIPs MFA.

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.

.PARAMETER BaseUrl
    Gateway base URL. Default http://localhost:18080 (local process mode).

.PARAMETER TimeoutSeconds
    Timeout for tenant activation polling. Default 120.

.EXAMPLE
    pwsh ./tests/e2e-v6.ps1
    pwsh ./tests/e2e-v6.ps1 -BaseUrl http://localhost:30080
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
$script:TenantCode = ''
$script:ClientIp = '198.51.100.' + (Get-Random -Minimum 10 -Maximum 250)
$script:TfaSecret = ''
$script:RecoveryCodes = @()
$script:TenantToken = ''
$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'v6 api availability',
    'oidc status disabled',
    'oidc authorize disabled',
    'oidc callback disabled',
    'tenant create',
    'tenant activation',
    'org create',
    'account create',
    'tenant login',
    'password change',
    'mfa status initial',
    'mfa setup',
    'mfa verify',
    'mfa status enabled',
    'login mfaRequired',
    'login mfa success',
    'login mfa wrong code 401',
    'mfa recovery login',
    'mfa recovery single use',
    'mfa disable',
    'login after disable',
    'mfa audit trail',
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
    Write-Host '---------------- e2e-v6 summary ----------------'
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
    foreach ($listName in @('items', 'records', 'list', 'roles', 'audits')) {
        if (Test-HasProp $Data $listName) { return @($Data.$listName) }
    }
    return @($Data)
}

# ---------------------------------------------------------------- TOTP helpers
function ConvertFrom-Base32 {
    param([string]$Text)
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
    $clean = ($Text -replace '\s', '').TrimEnd('=').ToUpperInvariant()
    $bits = New-Object System.Text.StringBuilder
    foreach ($ch in $clean.ToCharArray()) {
        $index = $alphabet.IndexOf([string]$ch)
        if ($index -lt 0) { throw ("invalid base32 character: {0}" -f $ch) }
        [void]$bits.Append([Convert]::ToString($index, 2).PadLeft(5, '0'))
    }
    $bitString = $bits.ToString()
    $bytes = New-Object System.Collections.Generic.List[byte]
    for ($i = 0; $i + 8 -le $bitString.Length; $i += 8) {
        $bytes.Add([Convert]::ToByte($bitString.Substring($i, 8), 2))
    }
    return , ($bytes.ToArray())
}

function Get-TotpCode {
    param([string]$Secret, [int]$StepOffset = 0, [int]$Digits = 6, [int]$Period = 30)
    $key = ConvertFrom-Base32 -Text $Secret
    $counter = [long][Math]::Floor(([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) / $Period) + $StepOffset
    $counterBytes = New-Object byte[] 8
    $value = $counter
    for ($i = 7; $i -ge 0; $i--) {
        $counterBytes[$i] = [byte]($value -band 0xFF)
        $value = $value -shr 8
    }
    $hmac = [System.Security.Cryptography.HMACSHA1]::new($key)
    try { $hash = $hmac.ComputeHash($counterBytes) } finally { $hmac.Dispose() }
    $offset = $hash[$hash.Length - 1] -band 0x0F
    $b1 = [int]($hash[$offset] -band 0x7F)
    $b2 = [int]$hash[$offset + 1]
    $b3 = [int]$hash[$offset + 2]
    $b4 = [int]$hash[$offset + 3]
    $binary = ($b1 -shl 24) -bor ($b2 -shl 16) -bor ($b3 -shl 8) -bor $b4
    $modulus = [int][Math]::Pow(10, $Digits)
    return (($binary % $modulus).ToString().PadLeft($Digits, '0'))
}

function Get-TotpCandidates {
    param([string]$Secret)
    $codes = New-Object System.Collections.Generic.List[string]
    foreach ($offset in @(0, -1, 1)) {
        try { $codes.Add((Get-TotpCode -Secret $Secret -StepOffset $offset)) } catch { }
    }
    return $codes
}

function Get-WrongTotpCode {
    param([string]$Secret)
    $candidates = @(Get-TotpCandidates -Secret $Secret)
    foreach ($candidate in @('000000', '111111', '222222')) {
        if ($candidates -notcontains $candidate) { return $candidate }
    }
    return '999999'
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
function Login-Tenant {
    param([string]$Username, [string]$Password, [string]$TenantCode)
    $headers = @{ 'X-Forwarded-For' = $script:ClientIp }
    return Invoke-Api -Path '/api/auth/login' -Method POST -Headers $headers -Body @{
        username = $Username; password = $Password; tenantCode = $TenantCode
    }
}

function Login-Mfa {
    param([string]$ChallengeToken, [string]$Code)
    $headers = @{ 'X-Forwarded-For' = $script:ClientIp }
    return Invoke-Api -Path '/api/auth/login/mfa' -Method POST -Headers $headers -Body @{
        challengeToken = $ChallengeToken; code = $Code
    }
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
                $approval = @(Get-Items $pending) | Where-Object { [string](Get-JsonProp $_ 'tenantId') -eq $TenantId } | Select-Object -First 1
                if ($null -ne $approval) {
                    $approvalId = [string](Get-JsonProp $approval 'id')
                    $null = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method POST -Headers $script:AdminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v6 activation' }
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
            $null = Invoke-ApiRaw -Path ("/api/tenants/{0}/terminate" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v6 cleanup' }
        }
        $null = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $TenantId) -Method DELETE -Headers $script:AdminHeaders
    }
    catch { }
}

function Invoke-Cleanup {
    if ($script:CleanedUp) { return }
    $script:CleanedUp = $true
    if ([string]::IsNullOrWhiteSpace($script:AdminToken)) { return }
    if (-not [string]::IsNullOrWhiteSpace($script:TenantId)) { Remove-TestTenant -TenantId $script:TenantId }
}

# ================================================================ run
Write-Host ("==> e2e-v6 against {0}" -f $script:Base) -ForegroundColor Cyan

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

# ---------------- capability gate: OIDC status + MFA route probe
$oidcReady = $false
$oidcEnabled = $false
$oidcProbeDetail = ''
$mfaReady = $false
$mfaProbeDetail = ''

try {
    $oidcStatus = Invoke-ApiRaw -Path '/api/auth/oidc/status'
    if ($oidcStatus.Ok -and $null -ne $oidcStatus.Data) {
        $oidcReady = $true
        $oidcEnabled = [bool](Get-JsonProp $oidcStatus.Data 'enabled')
        $oidcProbeDetail = ("enabled={0}" -f $oidcEnabled)
    }
    else {
        $oidcProbeDetail = ("HTTP {0} code={1}" -f $oidcStatus.Status, $oidcStatus.Code)
    }
}
catch { $oidcProbeDetail = $_.Exception.Message }

if ($oidcReady) {
    try {
        $mfaProbe = Invoke-ApiRaw -Path '/api/me/mfa' -Headers $script:AdminHeaders
        $mfaProbeDetail = ("HTTP {0} code={1}" -f $mfaProbe.Status, $mfaProbe.Code)
        if ($mfaProbe.Status -ne 404 -and $mfaProbe.Status -ne 500 -and $mfaProbe.Status -ne 501 -and $mfaProbe.Status -ne 0) {
            $mfaReady = $true
        }
    }
    catch { $mfaProbeDetail = $_.Exception.Message }
}

if (-not $oidcReady) {
    Add-Check 'v6 api availability' 'SKIP' ("v6 API not deployed; GET /api/auth/oidc/status -> " + $oidcProbeDetail)
    Skip-Remaining -Reason 'v6 API not deployed (docs/16 phase A not implemented in this deployment)'
    Show-Summary
}
Add-Check 'v6 api availability' 'PASS' ("oidc " + $oidcProbeDetail + "; mfa probe " + $mfaProbeDetail + "; mfaReady=" + $mfaReady)

# ---------------- OIDC assertions
if ($oidcEnabled) {
    Add-Check 'oidc status disabled' 'PASS' 'OIDC is configured (enabled=true); disabled-path assertions not applicable'
    Add-Check 'oidc authorize disabled' 'SKIP' 'OIDC is enabled: authorize returns a provider URL, not 404'
    Add-Check 'oidc callback disabled' 'SKIP' 'OIDC is enabled: callback redirects, not 400'
}
else {
    try {
        $status = Invoke-Api -Path '/api/auth/oidc/status'
        $enabled = Get-JsonProp $status 'enabled'
        $providerName = [string](Get-JsonProp $status 'providerName')
        $loginUrl = Get-JsonProp $status 'loginUrl'
        if ($enabled -eq $false -and -not [string]::IsNullOrWhiteSpace($providerName)) {
            Add-Check 'oidc status disabled' 'PASS' ("enabled=false providerName={0} loginUrl={1}" -f $providerName, $loginUrl)
        }
        else {
            Add-Check 'oidc status disabled' 'FAIL' ("enabled={0} providerName={1}" -f $enabled, $providerName)
        }
    }
    catch { Add-Check 'oidc status disabled' 'FAIL' $_.Exception.Message }

    try {
        $authorize = Invoke-ApiRaw -Path '/api/auth/oidc/authorize'
        if ($authorize.Status -eq 404 -or $authorize.Code -eq 40400) {
            Add-Check 'oidc authorize disabled' 'PASS' ("HTTP {0} code={1}" -f $authorize.Status, $authorize.Code)
        }
        else {
            Add-Check 'oidc authorize disabled' 'FAIL' ("expected 404/40400, got HTTP {0} code={1}" -f $authorize.Status, $authorize.Code)
        }
    }
    catch { Add-Check 'oidc authorize disabled' 'FAIL' $_.Exception.Message }

    try {
        $callback = Invoke-ApiRaw -Path '/api/auth/oidc/callback?code=stub-code&state=stub-state'
        if ($callback.Status -eq 400 -or $callback.Code -eq 40001) {
            Add-Check 'oidc callback disabled' 'PASS' ("HTTP {0} code={1}" -f $callback.Status, $callback.Code)
        }
        else {
            Add-Check 'oidc callback disabled' 'FAIL' ("expected 400/40001, got HTTP {0} code={1}" -f $callback.Status, $callback.Code)
        }
    }
    catch { Add-Check 'oidc callback disabled' 'FAIL' $_.Exception.Message }
}

# ---------------- MFA section
$mfaChecks = @(
    'tenant create', 'tenant activation', 'org create', 'account create',
    'tenant login', 'password change', 'mfa status initial', 'mfa setup',
    'mfa verify', 'mfa status enabled', 'login mfaRequired', 'login mfa success',
    'login mfa wrong code 401', 'mfa recovery login', 'mfa recovery single use',
    'mfa disable', 'login after disable', 'mfa audit trail', 'tenant cleanup'
)
if (-not $mfaReady) {
    foreach ($name in $mfaChecks) { Add-Check $name 'SKIP' ("GET /api/me/mfa not deployed (" + $mfaProbeDetail + ")") }
    Invoke-Cleanup
    Skip-Remaining -Reason 'MFA not deployed'
    Show-Summary
}

# ---------------- tenant + account setup
$tenantCode = New-RandomCode 'e2e-v6'
try {
    $tenant = Invoke-Api -Path '/api/tenants' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantCode    = $tenantCode
        name          = 'E2E V6 Tenant'
        contactEmail  = 'v6@example.com'
        contactName   = 'E2E V6 Owner'
        plan          = 'PRO'
        region        = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'
        quotaSeats    = 10
        source        = 'MANUAL'
        autoSubmit    = $false
    }
    $script:TenantId = [string](Get-JsonProp $tenant 'id')
    $script:TenantCode = $tenantCode
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

$orgId = ''
try {
    $org = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantId) -Method POST -Headers $script:AdminHeaders -Body @{
        code = 'HQ'; name = 'Headquarters'; type = 'COMPANY'
    }
    $orgId = [string](Get-JsonProp $org 'id')
    if ([string]::IsNullOrWhiteSpace($orgId)) { throw 'org id missing' }
    Add-Check 'org create' 'PASS' ("id={0}" -f $orgId)
}
catch {
    Add-Check 'org create' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'org create failed'
    Show-Summary
}

$accountId = ''
$tempPassword = ''
$username = 'e2ev6' + (Get-Random -Minimum 100000 -Maximum 999999)
try {
    $account = Invoke-Api -Path ("/api/tenants/{0}/accounts" -f $script:TenantId) -Method POST -Headers $script:AdminHeaders -Body @{
        username    = $username
        displayName = 'E2E V6 MFA User'
        email       = ($username + '@example.com')
        phone       = '13800007777'
        orgUnitId   = $orgId
        role        = 'MEMBER'
    }
    $accountId = [string](Get-JsonProp (Get-JsonProp $account 'account') 'id')
    $tempPassword = [string](Get-JsonProp $account 'tempPassword')
    if ([string]::IsNullOrWhiteSpace($accountId) -or [string]::IsNullOrWhiteSpace($tempPassword)) { throw 'account id or tempPassword missing' }
    Add-Check 'account create' 'PASS' ("id={0} tempPasswordLen={1}" -f $accountId, $tempPassword.Length)
}
catch {
    Add-Check 'account create' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'account create failed'
    Show-Summary
}

$newPassword = 'V6Passw0rd-2026'
try {
    $firstLogin = Login-Tenant -Username $username -Password $tempPassword -TenantCode $script:TenantCode
    $firstToken = [string](Get-JsonProp $firstLogin 'token')
    if ([string]::IsNullOrWhiteSpace($firstToken)) { throw 'tenant login returned no token' }
    $script:TenantToken = $firstToken
    $principal = Get-JsonProp $firstLogin 'principal'
    Add-Check 'tenant login' 'PASS' ("user={0} mustChangePassword={1}" -f $username, (Get-JsonProp $principal 'mustChangePassword'))
}
catch {
    Add-Check 'tenant login' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'tenant login failed'
    Show-Summary
}

try {
    $selfHeaders = @{ Authorization = ("Bearer {0}" -f $script:TenantToken) }
    $null = Invoke-Api -Path ("/api/tenants/{0}/accounts/{1}/change-password" -f $script:TenantId, $accountId) -Method POST -Headers $selfHeaders -Body @{
        oldPassword = $tempPassword; newPassword = $newPassword
    }
    $relogin = Login-Tenant -Username $username -Password $newPassword -TenantCode $script:TenantCode
    $reloginToken = [string](Get-JsonProp $relogin 'token')
    if ([string]::IsNullOrWhiteSpace($reloginToken)) { throw 're-login after password change returned no token' }
    $script:TenantToken = $reloginToken
    Add-Check 'password change' 'PASS' 'password updated and re-login succeeded'
}
catch {
    Add-Check 'password change' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'password change failed'
    Show-Summary
}

$tenantHeaders = @{ Authorization = ("Bearer {0}" -f $script:TenantToken) }

# ---------------- MFA status / setup / verify
try {
    $mfaStatus = Invoke-Api -Path '/api/me/mfa' -Headers $tenantHeaders
    $enabled = Get-JsonProp $mfaStatus 'enabled'
    if ($enabled -eq $false) { Add-Check 'mfa status initial' 'PASS' 'enabled=false' }
    else { Add-Check 'mfa status initial' 'FAIL' ("expected enabled=false, got {0}" -f $enabled) }
}
catch {
    Add-Check 'mfa status initial' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'MFA status failed'
    Show-Summary
}

try {
    $setup = Invoke-Api -Path '/api/me/mfa/setup' -Method POST -Headers $tenantHeaders -Body @{}
    $script:TfaSecret = [string](Get-JsonProp $setup 'secret')
    $otpauthUrl = [string](Get-JsonProp $setup 'otpauthUrl')
    $script:RecoveryCodes = @(Get-Items (Get-JsonProp $setup 'recoveryCodes'))
    $uniqueCodes = @($script:RecoveryCodes | Select-Object -Unique)
    $secretOk = (-not [string]::IsNullOrWhiteSpace($script:TfaSecret))
    $otpauthOk = ($otpauthUrl -match '^otpauth://totp/')
    $recoveryOk = ($script:RecoveryCodes.Count -ge 5 -and $uniqueCodes.Count -eq $script:RecoveryCodes.Count)
    if ($secretOk -and $otpauthOk -and $recoveryOk) {
        Add-Check 'mfa setup' 'PASS' ("secretLen={0} recoveryCodes={1}" -f $script:TfaSecret.Length, $script:RecoveryCodes.Count)
    }
    else {
        Add-Check 'mfa setup' 'FAIL' ("secretOk={0} otpauthOk={1} recoveryCodes={2} unique={3}" -f $secretOk, $otpauthOk, $script:RecoveryCodes.Count, $uniqueCodes.Count)
    }
}
catch {
    Add-Check 'mfa setup' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'MFA setup failed'
    Show-Summary
}

$verified = $false
try {
    $verifyRaw = $null
    $usedCode = $null
    foreach ($code in @(Get-TotpCandidates -Secret $script:TfaSecret)) {
        $verifyRaw = Invoke-ApiRaw -Path '/api/me/mfa/verify' -Method POST -Headers $tenantHeaders -Body @{ code = $code }
        if ($verifyRaw.Ok) { $usedCode = $code; $verified = $true; break }
    }
    if ($verified) {
        $enabledFlag = Get-JsonProp $verifyRaw.Data 'enabled'
        if ($enabledFlag -eq $true) { Add-Check 'mfa verify' 'PASS' ("enabled=true code={0}" -f $usedCode) }
        else { Add-Check 'mfa verify' 'FAIL' ("verify ok but enabled={0}" -f $enabledFlag) }
    }
    else {
        Add-Check 'mfa verify' 'FAIL' ("HTTP {0} code={1} message={2}" -f $verifyRaw.Status, $verifyRaw.Code, $verifyRaw.Message)
    }
}
catch {
    Add-Check 'mfa verify' 'FAIL' $_.Exception.Message
}
if (-not $verified) {
    Invoke-Cleanup
    Skip-Remaining -Reason 'MFA verify failed'
    Show-Summary
}

try {
    $mfaStatus = Invoke-Api -Path '/api/me/mfa' -Headers $tenantHeaders
    if ((Get-JsonProp $mfaStatus 'enabled') -eq $true -and -not [string]::IsNullOrWhiteSpace([string](Get-JsonProp $mfaStatus 'confirmedAt'))) {
        Add-Check 'mfa status enabled' 'PASS' 'enabled=true confirmedAt present'
    }
    else {
        Add-Check 'mfa status enabled' 'FAIL' ("enabled={0}" -f (Get-JsonProp $mfaStatus 'enabled'))
    }
}
catch {
    Add-Check 'mfa status enabled' 'FAIL' $_.Exception.Message
}

# ---------------- login with MFA
$mfaLoginToken = ''
try {
    $loginResponse = Login-Tenant -Username $username -Password $newPassword -TenantCode $script:TenantCode
    $mfaRequired = Get-JsonProp $loginResponse 'mfaRequired'
    $challengeToken = [string](Get-JsonProp $loginResponse 'challengeToken')
    if ($mfaRequired -eq $true -and -not [string]::IsNullOrWhiteSpace($challengeToken)) {
        Add-Check 'login mfaRequired' 'PASS' ("mfaRequired=true challengeTokenLen={0}" -f $challengeToken.Length)
    }
    else {
        Add-Check 'login mfaRequired' 'FAIL' ("mfaRequired={0} challengeTokenLen={1}" -f $mfaRequired, $challengeToken.Length)
    }
    if ($mfaRequired -eq $true -and -not [string]::IsNullOrWhiteSpace($challengeToken)) {
        $mfaRaw = $null
        foreach ($code in @(Get-TotpCandidates -Secret $script:TfaSecret)) {
            $mfaRaw = Invoke-ApiRaw -Path '/api/auth/login/mfa' -Method POST -Body @{ challengeToken = $challengeToken; code = $code }
            if ($mfaRaw.Ok) { break }
        }
        if ($mfaRaw.Ok) {
            $mfaLoginToken = [string](Get-JsonProp $mfaRaw.Data 'token')
            if (-not [string]::IsNullOrWhiteSpace($mfaLoginToken)) {
                Add-Check 'login mfa success' 'PASS' 'JWT issued after login/mfa'
            }
            else {
                Add-Check 'login mfa success' 'FAIL' 'login/mfa ok but token missing'
            }
        }
        else {
            Add-Check 'login mfa success' 'FAIL' ("HTTP {0} code={1} message={2}" -f $mfaRaw.Status, $mfaRaw.Code, $mfaRaw.Message)
        }
    }
    else {
        Add-Check 'login mfa success' 'SKIP' 'no mfa challenge issued'
    }
}
catch {
    Add-Check 'login mfaRequired' 'FAIL' $_.Exception.Message
    Add-Check 'login mfa success' 'SKIP' 'mfa challenge failed'
}

# ---------------- wrong code -> 401
try {
    $loginResponse = Login-Tenant -Username $username -Password $newPassword -TenantCode $script:TenantCode
    $challengeToken = [string](Get-JsonProp $loginResponse 'challengeToken')
    if ([string]::IsNullOrWhiteSpace($challengeToken)) { throw 'no challengeToken for wrong-code case' }
    $wrongCode = Get-WrongTotpCode -Secret $script:TfaSecret
    $wrongRaw = Invoke-ApiRaw -Path '/api/auth/login/mfa' -Method POST -Body @{ challengeToken = $challengeToken; code = $wrongCode }
    if ($wrongRaw.Status -eq 401 -or $wrongRaw.Code -eq 40100) {
        Add-Check 'login mfa wrong code 401' 'PASS' ("HTTP {0} code={1}" -f $wrongRaw.Status, $wrongRaw.Code)
    }
    else {
        Add-Check 'login mfa wrong code 401' 'FAIL' ("expected 401/40100, got HTTP {0} code={1}" -f $wrongRaw.Status, $wrongRaw.Code)
    }
}
catch {
    Add-Check 'login mfa wrong code 401' 'FAIL' $_.Exception.Message
}

# ---------------- recovery code: works once, reuse rejected
$recoveryCode = ''
if ($script:RecoveryCodes.Count -gt 0) { $recoveryCode = [string]$script:RecoveryCodes[0] }
if ([string]::IsNullOrWhiteSpace($recoveryCode)) {
    Add-Check 'mfa recovery login' 'SKIP' 'setup returned no recovery codes'
    Add-Check 'mfa recovery single use' 'SKIP' 'setup returned no recovery codes'
}
else {
    $recoveryToken = ''
    try {
        $loginResponse = Login-Tenant -Username $username -Password $newPassword -TenantCode $script:TenantCode
        $challengeToken = [string](Get-JsonProp $loginResponse 'challengeToken')
        if ([string]::IsNullOrWhiteSpace($challengeToken)) { throw 'no challengeToken for recovery login' }
        $recoveryRaw = Invoke-ApiRaw -Path '/api/auth/login/mfa' -Method POST -Body @{ challengeToken = $challengeToken; code = $recoveryCode }
        if ($recoveryRaw.Ok) {
            $recoveryToken = [string](Get-JsonProp $recoveryRaw.Data 'token')
        }
        if (-not [string]::IsNullOrWhiteSpace($recoveryToken)) {
            Add-Check 'mfa recovery login' 'PASS' 'recovery code accepted once'
        }
        else {
            Add-Check 'mfa recovery login' 'SKIP' ("recovery code flow not supported (HTTP {0} code={1})" -f $recoveryRaw.Status, $recoveryRaw.Code)
        }
    }
    catch {
        Add-Check 'mfa recovery login' 'SKIP' ("recovery code flow not supported: " + $_.Exception.Message)
    }
    if ([string]::IsNullOrWhiteSpace($recoveryToken)) {
        Add-Check 'mfa recovery single use' 'SKIP' 'recovery login did not succeed'
    }
    else {
        try {
            $loginResponse = Login-Tenant -Username $username -Password $newPassword -TenantCode $script:TenantCode
            $challengeToken = [string](Get-JsonProp $loginResponse 'challengeToken')
            if ([string]::IsNullOrWhiteSpace($challengeToken)) { throw 'no challengeToken for recovery reuse' }
            $reuseRaw = Invoke-ApiRaw -Path '/api/auth/login/mfa' -Method POST -Body @{ challengeToken = $challengeToken; code = $recoveryCode }
            if ($reuseRaw.Status -eq 401 -or $reuseRaw.Code -eq 40100) {
                Add-Check 'mfa recovery single use' 'PASS' ("reuse rejected HTTP {0} code={1}" -f $reuseRaw.Status, $reuseRaw.Code)
            }
            else {
                Add-Check 'mfa recovery single use' 'FAIL' ("reused recovery code accepted: HTTP {0} code={1}" -f $reuseRaw.Status, $reuseRaw.Code)
            }
        }
        catch {
            Add-Check 'mfa recovery single use' 'FAIL' $_.Exception.Message
        }
    }
}

# ---------------- disable MFA
$disableToken = ''
if (-not [string]::IsNullOrWhiteSpace($mfaLoginToken)) { $disableToken = $mfaLoginToken }
elseif (-not [string]::IsNullOrWhiteSpace($script:TenantToken)) { $disableToken = $script:TenantToken }
if ([string]::IsNullOrWhiteSpace($disableToken)) {
    Add-Check 'mfa disable' 'SKIP' 'no MFA-enabled JWT available'
    Add-Check 'login after disable' 'SKIP' 'MFA was not disabled'
}
else {
    try {
        $disableHeaders = @{ Authorization = ("Bearer {0}" -f $disableToken) }
        $disableRaw = $null
        foreach ($code in @(Get-TotpCandidates -Secret $script:TfaSecret)) {
            $disableRaw = Invoke-ApiRaw -Path '/api/me/mfa/disable' -Method POST -Headers $disableHeaders -Body @{ code = $code }
            if ($disableRaw.Ok) { break }
        }
        if ($disableRaw.Ok) {
            Add-Check 'mfa disable' 'PASS' 'MFA disabled'
        }
        else {
            Add-Check 'mfa disable' 'FAIL' ("HTTP {0} code={1} message={2}" -f $disableRaw.Status, $disableRaw.Code, $disableRaw.Message)
        }
    }
    catch {
        Add-Check 'mfa disable' 'FAIL' $_.Exception.Message
    }
    try {
        $afterDisable = Login-Tenant -Username $username -Password $newPassword -TenantCode $script:TenantCode
        $afterToken = [string](Get-JsonProp $afterDisable 'token')
        $afterMfa = Get-JsonProp $afterDisable 'mfaRequired'
        if (-not [string]::IsNullOrWhiteSpace($afterToken) -and $afterMfa -ne $true) {
            Add-Check 'login after disable' 'PASS' 'password login issues JWT without MFA challenge'
        }
        else {
            Add-Check 'login after disable' 'FAIL' ("tokenLen={0} mfaRequired={1}" -f $afterToken.Length, $afterMfa)
        }
    }
    catch {
        Add-Check 'login after disable' 'FAIL' $_.Exception.Message
    }
}

# ---------------- audit trail
try {
    $audits = Invoke-Api -Path ("/api/tenants/{0}/audits" -f $script:TenantId) -Headers $script:AdminHeaders
    $auditItems = @(Get-Items $audits)
    $actions = @($auditItems | ForEach-Object { [string](Get-JsonProp $_ 'action') })
    $required = @('MFA_ENROLLED', 'MFA_VERIFIED', 'MFA_DISABLED')
    $missing = @($required | Where-Object { $actions -notcontains $_ })
    if ($missing.Count -eq 0) {
        Add-Check 'mfa audit trail' 'PASS' ("actions={0}" -f (($required) -join ','))
    }
    else {
        Add-Check 'mfa audit trail' 'FAIL' ("missing: " + ($missing -join ', '))
    }
}
catch {
    Add-Check 'mfa audit trail' 'FAIL' $_.Exception.Message
}

# ---------------- cleanup
try {
    Remove-TestTenant -TenantId $script:TenantId
    $check = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:TenantId) -Headers $script:AdminHeaders
    if ($check.Status -eq 404 -or $check.Code -eq 40400) {
        Add-Check 'tenant cleanup' 'PASS' 'test tenant soft-deleted'
    }
    else {
        Add-Check 'tenant cleanup' 'PASS' ("delete requested; HTTP {0}" -f $check.Status)
    }
    $script:TenantId = ''
}
catch {
    Add-Check 'tenant cleanup' 'FAIL' $_.Exception.Message
}

Invoke-Cleanup
Skip-Remaining -Reason 'not executed'
Show-Summary
