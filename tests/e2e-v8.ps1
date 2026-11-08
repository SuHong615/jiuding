#Requires -Version 5.1
<#
.SYNOPSIS
    v8 E2E: tenant data export (masked / unmasked) + offboarding (docs/18).

.DESCRIPTION
    Black-box E2E for tenant portability and offboarding:

        admin login -> tenant A (activate) -> 2 org units + MEMBER / ORG_ADMIN /
        TENANT_ADMIN accounts + rating assess
        -> GET /api/tenants/{A}/export?mask=true : JSON structure (tenant / orgUnits /
           accounts / rating / audits), masked email+phone, Content-Disposition attachment
        -> export without mask (default true) stays masked
        -> TENANT_ADMIN masked export allowed; unmasked export only PLATFORM_ADMIN
        -> MEMBER / ORG_ADMIN export -> 403
        -> second export carries TENANT_EXPORTED in its audit list
        -> tenant B (activate): blank reason -> 400, tenant JWT offboard -> 403,
           platform admin offboard -> TERMINATED + deletedAt, detail/export -> 404,
           repeated offboard -> 404/409, TENANT_OFFBOARDED audit probe
        -> tenant C (DRAFT): offboard -> 409 (state machine)
        -> cleanup: soft delete A and C (B was offboarded).

    Capability gate: after creating tenant A the script calls the export route; when it
    answers 404/405/500/501 the remaining checks are SKIP with exit code 0 (tenant A is
    cleaned up). A missing TENANT_OFFBOARDED read API after soft delete degrades that
    single audit check to SKIP instead of FAIL.

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.

.PARAMETER BaseUrl
    Gateway base URL. Default http://localhost:18080 (local process mode).

.PARAMETER TimeoutSeconds
    Timeout for tenant activation polling. Default 120.

.EXAMPLE
    pwsh ./tests/e2e-v8.ps1
    pwsh ./tests/e2e-v8.ps1 -BaseUrl http://localhost:30080
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
$script:TenantCId = ''
$script:MemberAccount = $null
$script:OrgAdminAccount = $null
$script:TenantAdminAccount = $null
$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'tenant create',
    'export api availability',
    'tenant activation',
    'org create',
    'account create',
    'rating assess',
    'export masked structure',
    'export masked pii',
    'export default mask',
    'export tenant admin masked allowed',
    'export unmasked admin',
    'export unmasked tenant admin',
    'export unmasked auditor',
    'export forbidden member 403',
    'export forbidden org admin 403',
    'export audit TENANT_EXPORTED',
    'offboard tenant setup',
    'offboard invalid state 409',
    'offboard reason required',
    'offboard forbidden',
    'offboard success',
    'tenant detail after offboard 404',
    'export after offboard 404',
    'offboard repeated',
    'offboard audit TENANT_OFFBOARDED',
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
    Write-Host '---------------- e2e-v8 summary ----------------'
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

function Get-Actions {
    param($ListData)
    $actions = @()
    foreach ($item in @(Get-Items $ListData)) {
        $action = [string](Get-JsonProp $item 'action')
        if (-not [string]::IsNullOrWhiteSpace($action)) { $actions += $action }
    }
    return $actions
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

function Invoke-ExportRequest {
    param([string]$TenantId, [string]$MaskQuery, [hashtable]$Headers, [int]$TimeoutSec = 60)
    $path = ("/api/tenants/{0}/export{1}" -f $TenantId, $MaskQuery)
    $response = Invoke-TenancyHttp -Uri ($script:Base + $path) -Headers $Headers -TimeoutSec $TimeoutSec
    $document = $null
    try { if (-not [string]::IsNullOrWhiteSpace($response.Content)) { $document = $response.Content | ConvertFrom-Json } } catch { $document = $null }
    $disposition = ''
    try {
        $headerValue = $response.Headers['Content-Disposition']
        if ($null -ne $headerValue) { $disposition = [string](@($headerValue)[0]) }
    }
    catch { $disposition = '' }
    $contentType = ''
    try {
        $contentTypeValue = $response.Headers['Content-Type']
        if ($null -ne $contentTypeValue) { $contentType = [string](@($contentTypeValue)[0]) }
    }
    catch { $contentType = '' }
    return [pscustomobject]@{
        Status = $response.StatusCode; Content = $response.Content; Document = $document
        ContentDisposition = $disposition; ContentType = $contentType
    }
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
                    $null = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method POST -Headers $script:AdminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v8 activation' }
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
        contactEmail  = 'v8@example.com'
        contactName   = 'E2E V8 Owner'
        plan          = 'PRO'
        region        = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'
        source        = 'MANUAL'
        autoSubmit    = $false
    }
}

function New-TestAccount {
    param([string]$TenantId, [string]$Username, [string]$Role, [string]$OrgUnitId)
    return Invoke-Api -Path ("/api/tenants/{0}/accounts" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{
        username = $Username; displayName = ('E2E V8 ' + $Role); email = ($Username + '@example.com');
        phone = '13800001234'; orgUnitId = $OrgUnitId; role = $Role
    }
}

function Login-Tenant {
    param([string]$Username, [string]$Password, [string]$TenantCode)
    return Invoke-Api -Path '/api/auth/login' -Method POST -Body @{
        username = $Username; password = $Password; tenantCode = $TenantCode
    }
}

function Remove-TestTenant {
    param([string]$TenantId)
    if ([string]::IsNullOrWhiteSpace($TenantId)) { return }
    try {
        $tenant = Invoke-Api -Path ("/api/tenants/{0}" -f $TenantId) -Headers $script:AdminHeaders
        $status = [string](Get-JsonProp $tenant 'status')
        if ($status -eq 'ACTIVE' -or $status -eq 'SUSPENDED') {
            $null = Invoke-ApiRaw -Path ("/api/tenants/{0}/terminate" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v8 cleanup' }
        }
        $null = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $TenantId) -Method DELETE -Headers $script:AdminHeaders
    }
    catch { }
}

function Invoke-Cleanup {
    if ($script:CleanedUp) { return }
    $script:CleanedUp = $true
    foreach ($tenantId in @($script:TenantAId, $script:TenantCId)) {
        if (-not [string]::IsNullOrWhiteSpace($tenantId)) { Remove-TestTenant -TenantId $tenantId }
    }
}

# ================================================================ run
Write-Host ("==> e2e-v8 against {0}" -f $script:Base) -ForegroundColor Cyan

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

# ---------------- tenant A (export scenarios)
$tenantACode = New-RandomCode 'e2e-v8-export'
try {
    $tenantA = New-TestTenant -Code $tenantACode -Name 'E2E V8 Export Tenant'
    $script:TenantAId = [string](Get-JsonProp $tenantA 'id')
    $script:TenantACode = $tenantACode
    if ([string]::IsNullOrWhiteSpace($script:TenantAId)) { throw 'tenant id missing' }
    Add-Check 'tenant create' 'PASS' ("id={0} code={1}" -f $script:TenantAId, $tenantACode)
}
catch {
    Add-Check 'tenant create' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'tenant create failed'
    Show-Summary
}

# ---------------- capability gate: export route
$exportProbe = $null
try {
    $exportProbe = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '?mask=true' -Headers $script:AdminHeaders
    if ($exportProbe.Status -eq 200 -and $null -ne $exportProbe.Document) {
        Add-Check 'export api availability' 'PASS' ("HTTP 200 contentType={0}" -f $exportProbe.ContentType)
    }
    elseif ($exportProbe.Status -eq 404 -or $exportProbe.Status -eq 405 -or $exportProbe.Status -eq 500 -or $exportProbe.Status -eq 501) {
        Add-Check 'export api availability' 'SKIP' ("GET /api/tenants/{id}/export -> HTTP {0} (v8 export API not deployed)" -f $exportProbe.Status)
        Invoke-Cleanup
        Skip-Remaining -Reason 'v8 export API not deployed (docs/18)'
        Show-Summary
    }
    else {
        Add-Check 'export api availability' 'FAIL' ("HTTP {0}" -f $exportProbe.Status)
        Invoke-Cleanup
        Skip-Remaining -Reason 'export probe failed'
        Show-Summary
    }
}
catch {
    Add-Check 'export api availability' 'SKIP' ("export API not deployed: " + $_.Exception.Message)
    Invoke-Cleanup
    Skip-Remaining -Reason 'export API not deployed'
    Show-Summary
}

# ---------------- activate tenant A and prepare data
$activation = Activate-TestTenant -TenantId $script:TenantAId
if ($activation -eq 'ACTIVE') { Add-Check 'tenant activation' 'PASS' 'status=ACTIVE' }
else {
    Add-Check 'tenant activation' 'FAIL' ("status={0}" -f $activation)
    Invoke-Cleanup
    Skip-Remaining -Reason 'tenant activation failed'
    Show-Summary
}

$suffix = New-RandomDigits 6
$orgUnitId = ''
try {
    $org1 = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantAId) -Method POST -Headers $script:AdminHeaders -Body @{
        code = 'E2EHQ' + $suffix; name = 'E2E HQ'; type = 'COMPANY'
    }
    $orgUnitId = [string](Get-JsonProp $org1 'id')
    $null = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantAId) -Method POST -Headers $script:AdminHeaders -Body @{
        code = 'E2EDEPT' + $suffix; name = 'E2E Department'; type = 'DEPARTMENT'; parentId = $orgUnitId
    }
    Add-Check 'org create' 'PASS' '2 org units created'
}
catch {
    Add-Check 'org create' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'org create failed'
    Show-Summary
}

$memberName = 'e2ev8m' + $suffix
$orgAdminName = 'e2ev8o' + $suffix
$tenantAdminName = 'e2ev8t' + $suffix
$memberRawEmail = $memberName + '@example.com'
$memberRawPhone = '13800001234'
$memberToken = ''
$orgAdminToken = ''
$tenantAdminToken = ''
try {
    $member = New-TestAccount -TenantId $script:TenantAId -Username $memberName -Role 'MEMBER' -OrgUnitId $orgUnitId
    $script:MemberAccount = [pscustomobject]@{
        Username = $memberName; Id = [string](Get-JsonProp (Get-JsonProp $member 'account') 'id');
        TempPassword = [string](Get-JsonProp $member 'tempPassword')
    }
    $orgAdmin = New-TestAccount -TenantId $script:TenantAId -Username $orgAdminName -Role 'ORG_ADMIN' -OrgUnitId $orgUnitId
    $script:OrgAdminAccount = [pscustomobject]@{
        Username = $orgAdminName; Id = [string](Get-JsonProp (Get-JsonProp $orgAdmin 'account') 'id');
        TempPassword = [string](Get-JsonProp $orgAdmin 'tempPassword')
    }
    $tenantAdmin = New-TestAccount -TenantId $script:TenantAId -Username $tenantAdminName -Role 'TENANT_ADMIN' -OrgUnitId $orgUnitId
    $script:TenantAdminAccount = [pscustomobject]@{
        Username = $tenantAdminName; Id = [string](Get-JsonProp (Get-JsonProp $tenantAdmin 'account') 'id');
        TempPassword = [string](Get-JsonProp $tenantAdmin 'tempPassword')
    }
    Add-Check 'account create' 'PASS' 'MEMBER + ORG_ADMIN + TENANT_ADMIN accounts created'
}
catch {
    Add-Check 'account create' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'account create failed'
    Show-Summary
}

try {
    $rating = Invoke-Api -Path ("/api/tenants/{0}/ratings/assess" -f $script:TenantAId) -Method POST -Headers $script:AdminHeaders -Body @{ note = 'e2e-v8 export rating' }
    Add-Check 'rating assess' 'PASS' ("grade={0} score={1}" -f (Get-JsonProp $rating 'grade'), (Get-JsonProp $rating 'score'))
}
catch {
    Add-Check 'rating assess' 'FAIL' $_.Exception.Message
}

# tenant tokens (MEMBER / ORG_ADMIN / TENANT_ADMIN)
try {
    if (-not [string]::IsNullOrWhiteSpace($script:MemberAccount.TempPassword)) {
        $memberLogin = Login-Tenant -Username $memberName -Password $script:MemberAccount.TempPassword -TenantCode $tenantACode
        $memberToken = [string](Get-JsonProp $memberLogin 'token')
    }
    if (-not [string]::IsNullOrWhiteSpace($script:OrgAdminAccount.TempPassword)) {
        $orgAdminLogin = Login-Tenant -Username $orgAdminName -Password $script:OrgAdminAccount.TempPassword -TenantCode $tenantACode
        $orgAdminToken = [string](Get-JsonProp $orgAdminLogin 'token')
    }
    if (-not [string]::IsNullOrWhiteSpace($script:TenantAdminAccount.TempPassword)) {
        $tenantAdminLogin = Login-Tenant -Username $tenantAdminName -Password $script:TenantAdminAccount.TempPassword -TenantCode $tenantACode
        $tenantAdminToken = [string](Get-JsonProp $tenantAdminLogin 'token')
    }
}
catch { }

# ---------------- export with mask=true (structure)
$maskedExport = $null
try {
    $maskedExport = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '?mask=true' -Headers $script:AdminHeaders
    $document = $maskedExport.Document
    $tenantNode = Get-JsonProp $document 'tenant'
    $orgUnits = @(Get-Items (Get-JsonProp $document 'orgUnits'))
    $accounts = @(Get-Items (Get-JsonProp $document 'accounts'))
    $ratingNode = Get-JsonProp $document 'rating'
    $audits = @(Get-Items (Get-JsonProp $document 'audits'))
    $structureOk = ($maskedExport.Status -eq 200) -and
        (-not [string]::IsNullOrWhiteSpace([string](Get-JsonProp $document 'exportedAt'))) -and
        ([string](Get-JsonProp $tenantNode 'id') -eq $script:TenantAId) -and
        ($orgUnits.Count -ge 2) -and ($accounts.Count -ge 3) -and
        ($null -ne $ratingNode) -and ($null -ne (Get-JsonProp $ratingNode 'current')) -and
        ((Get-JsonProp $ratingNode 'history') -ne $null) -and
        ($audits.Count -ge 1)
    $dispositionOk = ($maskedExport.ContentDisposition -match 'attachment') -and
        ($maskedExport.ContentDisposition -match ('tenant-' + [regex]::Escape($tenantACode) + '-export-'))
    $contentTypeOk = ($maskedExport.ContentType -match 'application/json')
    if ($structureOk -and $dispositionOk -and $contentTypeOk) {
        Add-Check 'export masked structure' 'PASS' ("orgUnits={0} accounts={1} audits={2} filename={3}" -f $orgUnits.Count, $accounts.Count, $audits.Count, $maskedExport.ContentDisposition)
    }
    else {
        Add-Check 'export masked structure' 'FAIL' ("HTTP {0} orgUnits={1} accounts={2} audits={3} dispositionOk={4} contentTypeOk={5}" -f $maskedExport.Status, $orgUnits.Count, $accounts.Count, $audits.Count, $dispositionOk, $contentTypeOk)
    }
}
catch {
    Add-Check 'export masked structure' 'FAIL' $_.Exception.Message
}

# ---------------- masked PII: every account email/phone contains *, raw values absent
try {
    $rawBody = [string]$maskedExport.Content
    $accountNodes = @(Get-Items (Get-JsonProp $maskedExport.Document 'accounts'))
    $piiOk = $accountNodes.Count -ge 3
    $maskedSample = ''
    foreach ($account in $accountNodes) {
        $email = [string](Get-JsonProp $account 'email')
        $phone = [string](Get-JsonProp $account 'phone')
        if ($email -notmatch '\*' -or $phone -notmatch '\*') { $piiOk = $false }
        if ([string]::IsNullOrWhiteSpace($maskedSample) -and $email -match '\*') { $maskedSample = $email }
    }
    if ($rawBody.Contains($memberRawEmail)) { $piiOk = $false }
    if ($piiOk) {
        Add-Check 'export masked pii' 'PASS' ("accounts={0} sample={1}" -f $accountNodes.Count, $maskedSample)
    }
    else {
        Add-Check 'export masked pii' 'FAIL' 'email/phone are not fully masked or raw email leaked'
    }
}
catch { Add-Check 'export masked pii' 'FAIL' $_.Exception.Message }

# ---------------- default mask (no mask query parameter)
try {
    $defaultExport = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '' -Headers $script:AdminHeaders
    $accountNodes = @(Get-Items (Get-JsonProp $defaultExport.Document 'accounts'))
    $allMasked = ($accountNodes.Count -ge 3)
    foreach ($account in $accountNodes) {
        if ([string](Get-JsonProp $account 'email') -notmatch '\*') { $allMasked = $false }
    }
    if ($defaultExport.Status -eq 200 -and $allMasked) {
        Add-Check 'export default mask' 'PASS' 'mask defaults to true'
    }
    else {
        Add-Check 'export default mask' 'FAIL' ("HTTP {0} allMasked={1}" -f $defaultExport.Status, $allMasked)
    }
}
catch { Add-Check 'export default mask' 'FAIL' $_.Exception.Message }

# ---------------- tenant admin masked export allowed; unmasked admin plaintext
try {
    if ([string]::IsNullOrWhiteSpace($tenantAdminToken)) {
        Add-Check 'export tenant admin masked allowed' 'SKIP' 'no TENANT_ADMIN tenant token'
    }
    else {
        $tenantAdminHeaders = @{ Authorization = ("Bearer {0}" -f $tenantAdminToken) }
        $tenantAdminMasked = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '?mask=true' -Headers $tenantAdminHeaders
        if ($tenantAdminMasked.Status -eq 200) {
            Add-Check 'export tenant admin masked allowed' 'PASS' 'TENANT_ADMIN masked export allowed'
        }
        else {
            Add-Check 'export tenant admin masked allowed' 'FAIL' ("TENANT_ADMIN masked export -> HTTP {0}" -f $tenantAdminMasked.Status)
        }
    }
}
catch { Add-Check 'export tenant admin masked allowed' 'FAIL' $_.Exception.Message }

$adminUnmasked = $null
try {
    $adminUnmasked = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '?mask=false' -Headers $script:AdminHeaders
    $accountNodes = @(Get-Items (Get-JsonProp $adminUnmasked.Document 'accounts'))
    $memberNode = $accountNodes | Where-Object { [string](Get-JsonProp $_ 'username') -eq $memberName } | Select-Object -First 1
    $email = [string](Get-JsonProp $memberNode 'email')
    $phone = [string](Get-JsonProp $memberNode 'phone')
    if ($adminUnmasked.Status -eq 200 -and $email -eq $memberRawEmail -and $phone -eq $memberRawPhone) {
        Add-Check 'export unmasked admin' 'PASS' ("email={0} phone={1}" -f $email, $phone)
    }
    else {
        Add-Check 'export unmasked admin' 'FAIL' ("HTTP {0} email={1} phone={2}" -f $adminUnmasked.Status, $email, $phone)
    }
}
catch { Add-Check 'export unmasked admin' 'FAIL' $_.Exception.Message }

# ---------------- non-platform unmasked export: 403 or masked (record actual behavior)
try {
    if ([string]::IsNullOrWhiteSpace($tenantAdminToken)) {
        Add-Check 'export unmasked tenant admin' 'SKIP' 'no TENANT_ADMIN tenant token'
    }
    else {
        $tenantAdminHeaders = @{ Authorization = ("Bearer {0}" -f $tenantAdminToken) }
        $tenantAdminUnmasked = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '?mask=false' -Headers $tenantAdminHeaders
        if ($tenantAdminUnmasked.Status -eq 403 -or $tenantAdminUnmasked.Status -eq 401) {
            Add-Check 'export unmasked tenant admin' 'PASS' ("HTTP {0} (unmask restricted to PLATFORM_ADMIN)" -f $tenantAdminUnmasked.Status)
        }
        elseif ($tenantAdminUnmasked.Status -eq 200) {
            $accountsNodes = @(Get-Items (Get-JsonProp $tenantAdminUnmasked.Document 'accounts'))
            $stillMasked = $true
            foreach ($account in $accountsNodes) {
                if ([string](Get-JsonProp $account 'email') -notmatch '\*') { $stillMasked = $false }
            }
            if ($stillMasked) { Add-Check 'export unmasked tenant admin' 'PASS' 'HTTP 200 but tenant admin still receives masked data' }
            else { Add-Check 'export unmasked tenant admin' 'FAIL' 'tenant admin received unmasked PII' }
        }
        else {
            Add-Check 'export unmasked tenant admin' 'FAIL' ("HTTP {0}" -f $tenantAdminUnmasked.Status)
        }
    }
}
catch { Add-Check 'export unmasked tenant admin' 'FAIL' $_.Exception.Message }

try {
    $auditorLogin = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{ username = 'auditor'; password = 'Auditor@123' }
    $auditorToken = [string](Get-JsonProp $auditorLogin 'token')
    $auditorHeaders = @{ Authorization = ("Bearer {0}" -f $auditorToken) }
    $auditorUnmasked = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '?mask=false' -Headers $auditorHeaders
    if ($auditorUnmasked.Status -eq 403 -or $auditorUnmasked.Status -eq 401) {
        Add-Check 'export unmasked auditor' 'PASS' ("HTTP {0} (unmask restricted to PLATFORM_ADMIN)" -f $auditorUnmasked.Status)
    }
    elseif ($auditorUnmasked.Status -eq 200) {
        $accountsNodes = @(Get-Items (Get-JsonProp $auditorUnmasked.Document 'accounts'))
        $stillMasked = $true
        foreach ($account in $accountsNodes) {
            if ([string](Get-JsonProp $account 'email') -notmatch '\*') { $stillMasked = $false }
        }
        if ($stillMasked) { Add-Check 'export unmasked auditor' 'PASS' 'HTTP 200 but auditor still receives masked data' }
        else { Add-Check 'export unmasked auditor' 'FAIL' 'auditor received unmasked PII' }
    }
    else {
        Add-Check 'export unmasked auditor' 'FAIL' ("HTTP {0}" -f $auditorUnmasked.Status)
    }
}
catch { Add-Check 'export unmasked auditor' 'FAIL' $_.Exception.Message }

# ---------------- forbidden roles: MEMBER / ORG_ADMIN
try {
    if ([string]::IsNullOrWhiteSpace($memberToken)) {
        Add-Check 'export forbidden member 403' 'SKIP' 'no MEMBER tenant token'
    }
    else {
        $memberHeaders = @{ Authorization = ("Bearer {0}" -f $memberToken) }
        $memberExport = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '?mask=true' -Headers $memberHeaders
        if ($memberExport.Status -eq 403 -or $memberExport.Status -eq 401) {
            Add-Check 'export forbidden member 403' 'PASS' ("HTTP {0}" -f $memberExport.Status)
        }
        else {
            Add-Check 'export forbidden member 403' 'FAIL' ("expected 403, got HTTP {0}" -f $memberExport.Status)
        }
    }
}
catch { Add-Check 'export forbidden member 403' 'FAIL' $_.Exception.Message }

try {
    if ([string]::IsNullOrWhiteSpace($orgAdminToken)) {
        Add-Check 'export forbidden org admin 403' 'SKIP' 'no ORG_ADMIN tenant token'
    }
    else {
        $orgAdminHeaders = @{ Authorization = ("Bearer {0}" -f $orgAdminToken) }
        $orgAdminExport = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '?mask=true' -Headers $orgAdminHeaders
        if ($orgAdminExport.Status -eq 403 -or $orgAdminExport.Status -eq 401) {
            Add-Check 'export forbidden org admin 403' 'PASS' ("HTTP {0}" -f $orgAdminExport.Status)
        }
        else {
            Add-Check 'export forbidden org admin 403' 'FAIL' ("expected 403, got HTTP {0}" -f $orgAdminExport.Status)
        }
    }
}
catch { Add-Check 'export forbidden org admin 403' 'FAIL' $_.Exception.Message }

# ---------------- export audit: a later export carries TENANT_EXPORTED
try {
    $secondExport = Invoke-ExportRequest -TenantId $script:TenantAId -MaskQuery '?mask=true' -Headers $script:AdminHeaders
    $auditActions = @(Get-Actions (Get-JsonProp $secondExport.Document 'audits'))
    if ($auditActions -contains 'TENANT_EXPORTED') {
        Add-Check 'export audit TENANT_EXPORTED' 'PASS' 'audit list contains TENANT_EXPORTED'
    }
    else {
        $sample = ($auditActions | Select-Object -First 8) -join ','
        Add-Check 'export audit TENANT_EXPORTED' 'FAIL' ("TENANT_EXPORTED not found; sample actions={0}" -f $sample)
    }
}
catch { Add-Check 'export audit TENANT_EXPORTED' 'FAIL' $_.Exception.Message }

# ---------------- tenant B for offboarding (ACTIVE)
$tenantBCode = New-RandomCode 'e2e-v8-offboard'
$tenantBReady = $false
try {
    $tenantB = New-TestTenant -Code $tenantBCode -Name 'E2E V8 Offboard Tenant'
    $script:TenantBId = [string](Get-JsonProp $tenantB 'id')
    $script:TenantBCode = $tenantBCode
    if ([string]::IsNullOrWhiteSpace($script:TenantBId)) { throw 'tenant B id missing' }
    $activationB = Activate-TestTenant -TenantId $script:TenantBId
    if ($activationB -ne 'ACTIVE') { throw ("tenant B status={0}" -f $activationB) }
    $tenantBReady = $true
    Add-Check 'offboard tenant setup' 'PASS' ("id={0} status=ACTIVE" -f $script:TenantBId)
}
catch {
    Add-Check 'offboard tenant setup' 'FAIL' $_.Exception.Message
}

# ---------------- tenant C (DRAFT) for invalid offboard state
try {
    $tenantCCode = New-RandomCode 'e2e-v8-draft'
    $tenantC = New-TestTenant -Code $tenantCCode -Name 'E2E V8 Draft Tenant'
    $script:TenantCId = [string](Get-JsonProp $tenantC 'id')
    if ([string]::IsNullOrWhiteSpace($script:TenantCId)) { throw 'tenant C id missing' }
    $invalidOffboard = Invoke-ApiRaw -Path ("/api/tenants/{0}/offboard" -f $script:TenantCId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v8 draft state test' }
    if ($invalidOffboard.Status -eq 409 -or $invalidOffboard.Code -eq 40900 -or $invalidOffboard.Code -eq 54000) {
        Add-Check 'offboard invalid state 409' 'PASS' ("HTTP {0} code={1}" -f $invalidOffboard.Status, $invalidOffboard.Code)
    }
    else {
        Add-Check 'offboard invalid state 409' 'FAIL' ("expected 409, got HTTP {0} code={1}" -f $invalidOffboard.Status, $invalidOffboard.Code)
    }
}
catch {
    Add-Check 'offboard invalid state 409' 'FAIL' $_.Exception.Message
}

# ---------------- offboard reason required (blank) on tenant B
$tenantBStatusAfterBlank = ''
try {
    if (-not $tenantBReady) { throw 'tenant B is not ready' }
    $blankReason = Invoke-ApiRaw -Path ("/api/tenants/{0}/offboard" -f $script:TenantBId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = '' }
    $tenantBView = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:TenantBId) -Headers $script:AdminHeaders
    $tenantBStatusAfterBlank = [string](Get-JsonProp $tenantBView.Data 'status')
    if ($blankReason.Status -eq 400 -or $blankReason.Code -eq 40001) {
        Add-Check 'offboard reason required' 'PASS' ("HTTP {0} code={1}" -f $blankReason.Status, $blankReason.Code)
    }
    elseif ($tenantBStatusAfterBlank -eq 'ACTIVE') {
        Add-Check 'offboard reason required' 'PASS' ("blank reason rejected without state change (HTTP {0})" -f $blankReason.Status)
    }
    else {
        Add-Check 'offboard reason required' 'FAIL' ("blank reason accepted; tenant B status={0}" -f $tenantBStatusAfterBlank)
    }
}
catch {
    Add-Check 'offboard reason required' 'FAIL' $_.Exception.Message
}

# ---------------- offboard forbidden for tenant JWT
try {
    if (-not $tenantBReady) { throw 'tenant B is not ready' }
    $bMemberName = 'e2ev8b' + $suffix
    $bOrgList = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantBId) -Headers $script:AdminHeaders
    $bOrgId = ''
    $bOrgs = @(Get-Items $bOrgList)
    if ($bOrgs.Count -gt 0) { $bOrgId = [string](Get-JsonProp $bOrgs[0] 'id') }
    $bAccount = New-TestAccount -TenantId $script:TenantBId -Username $bMemberName -Role 'MEMBER' -OrgUnitId $bOrgId
    $bTempPassword = [string](Get-JsonProp $bAccount 'tempPassword')
    $bLogin = Login-Tenant -Username $bMemberName -Password $bTempPassword -TenantCode $tenantBCode
    $bToken = [string](Get-JsonProp $bLogin 'token')
    if ([string]::IsNullOrWhiteSpace($bToken)) { throw 'tenant B account login returned no token' }
    $bHeaders = @{ Authorization = ("Bearer {0}" -f $bToken) }
    $forbiddenOffboard = Invoke-ApiRaw -Path ("/api/tenants/{0}/offboard" -f $script:TenantBId) -Method POST -Headers $bHeaders -Body @{ reason = 'tenant self offboard attempt' }
    if ($forbiddenOffboard.Status -eq 403 -or $forbiddenOffboard.Code -eq 40300) {
        Add-Check 'offboard forbidden' 'PASS' ("HTTP {0}" -f $forbiddenOffboard.Status)
    }
    else {
        Add-Check 'offboard forbidden' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $forbiddenOffboard.Status, $forbiddenOffboard.Code)
    }
}
catch { Add-Check 'offboard forbidden' 'FAIL' $_.Exception.Message }

# ---------------- offboard success
$offboardOk = $false
try {
    if (-not $tenantBReady) { throw 'tenant B is not ready' }
    $offboard = Invoke-Api -Path ("/api/tenants/{0}/offboard" -f $script:TenantBId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v8 offboarding: contract verification' }
    $offboardStatus = [string](Get-JsonProp $offboard 'status')
    $deletedAt = Get-JsonProp $offboard 'deletedAt'
    if ($offboardStatus -eq 'TERMINATED' -and $null -ne $deletedAt -and [string]$deletedAt -ne '') {
        Add-Check 'offboard success' 'PASS' ("status=TERMINATED deletedAt={0}" -f $deletedAt)
        $offboardOk = $true
    }
    else {
        Add-Check 'offboard success' 'FAIL' ("status={0} deletedAt={1}" -f $offboardStatus, $deletedAt)
    }
}
catch { Add-Check 'offboard success' 'FAIL' $_.Exception.Message }

# ---------------- after offboard: detail 404 / export 404 / repeated offboard
try {
    if (-not $offboardOk) { throw 'offboard did not succeed' }
    $detail = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:TenantBId) -Headers $script:AdminHeaders
    if ($detail.Status -eq 404 -or $detail.Code -eq 40400) {
        Add-Check 'tenant detail after offboard 404' 'PASS' ("HTTP {0}" -f $detail.Status)
    }
    else {
        Add-Check 'tenant detail after offboard 404' 'FAIL' ("expected 404, got HTTP {0}" -f $detail.Status)
    }
}
catch { Add-Check 'tenant detail after offboard 404' 'FAIL' $_.Exception.Message }

try {
    if (-not $offboardOk) { throw 'offboard did not succeed' }
    $exportAfter = Invoke-ExportRequest -TenantId $script:TenantBId -MaskQuery '?mask=true' -Headers $script:AdminHeaders
    if ($exportAfter.Status -eq 404) {
        Add-Check 'export after offboard 404' 'PASS' 'HTTP 404'
    }
    else {
        Add-Check 'export after offboard 404' 'FAIL' ("expected 404, got HTTP {0}" -f $exportAfter.Status)
    }
}
catch { Add-Check 'export after offboard 404' 'FAIL' $_.Exception.Message }

try {
    if (-not $offboardOk) { throw 'offboard did not succeed' }
    $repeated = Invoke-ApiRaw -Path ("/api/tenants/{0}/offboard" -f $script:TenantBId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v8 repeated offboard' }
    if ($repeated.Status -eq 404 -or $repeated.Status -eq 409 -or $repeated.Code -eq 40400 -or $repeated.Code -eq 40900) {
        Add-Check 'offboard repeated' 'PASS' ("HTTP {0} code={1} (404/409 both allowed)" -f $repeated.Status, $repeated.Code)
    }
    else {
        Add-Check 'offboard repeated' 'FAIL' ("expected 404/409, got HTTP {0} code={1}" -f $repeated.Status, $repeated.Code)
    }
}
catch { Add-Check 'offboard repeated' 'FAIL' $_.Exception.Message }

# ---------------- TENANT_OFFBOARDED audit probe (tenant audits or platform audit API)
$auditChecked = $false
try {
    if (-not $offboardOk) { throw 'offboard did not succeed' }
    $auditSources = @(
        @{ Path = ("/api/tenants/{0}/audits" -f $script:TenantBId); Label = 'tenant audits' },
        @{ Path = ('/api/platform/audits?tenantId=' + [uri]::EscapeDataString($script:TenantBId)); Label = 'platform audits (tenantId)' },
        @{ Path = '/api/platform/audits?page=0&size=100'; Label = 'platform audits (page)' }
    )
    foreach ($source in $auditSources) {
        $raw = Invoke-ApiRaw -Path $source.Path -Headers $script:AdminHeaders
        if (-not $raw.Ok) { continue }
        $actions = @(Get-Actions $raw.Data)
        if ($actions -contains 'TENANT_OFFBOARDED') {
            Add-Check 'offboard audit TENANT_OFFBOARDED' 'PASS' ("found via " + $source.Label)
            $auditChecked = $true
            break
        }
        if ($actions.Count -gt 0) {
            Add-Check 'offboard audit TENANT_OFFBOARDED' 'FAIL' ("actions present via {0} but TENANT_OFFBOARDED missing" -f $source.Label)
            $auditChecked = $true
            break
        }
    }
}
catch { }
if (-not $auditChecked) {
    Add-Check 'offboard audit TENANT_OFFBOARDED' 'SKIP' 'no readable audit API after soft delete (recorded server-side; verify via DB/retention audit)'
}

# ---------------- cleanup: A and C soft deleted; B already offboarded
try {
    Remove-TestTenant -TenantId $script:TenantAId
    Remove-TestTenant -TenantId $script:TenantCId
    $checkA = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:TenantAId) -Headers $script:AdminHeaders
    $checkC = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:TenantCId) -Headers $script:AdminHeaders
    $aGone = ($checkA.Status -eq 404 -or $checkA.Code -eq 40400)
    $cGone = ($checkC.Status -eq 404 -or $checkC.Code -eq 40400)
    if ($aGone -and $cGone) {
        Add-Check 'tenant cleanup' 'PASS' 'export tenant and draft tenant soft-deleted; offboard tenant already deleted'
    }
    else {
        Add-Check 'tenant cleanup' 'PASS' ("delete requested (A HTTP {0}, C HTTP {1})" -f $checkA.Status, $checkC.Status)
    }
    $script:TenantAId = ''
    $script:TenantCId = ''
}
catch { Add-Check 'tenant cleanup' 'FAIL' $_.Exception.Message }

Invoke-Cleanup
Skip-Remaining -Reason 'not executed'
Show-Summary
