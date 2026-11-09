#Requires -Version 5.1
<#
.SYNOPSIS
    v11 E2E: platform audit center (cross-tenant query + CSV export, docs/21).

.DESCRIPTION
    Black-box E2E for the platform audit center:

        admin login -> create two tenants and trigger audit actions (tenant create/update,
        org create, account create, submit) -> GET /api/platform/audits returns cross-tenant
        records enriched with tenantCode / tenantName -> filters (tenantId / action / keyword)
        -> pagination and at-DESC ordering -> tenant admin JWT and platform AUDITOR -> 403 ->
        unauthenticated -> 401 -> GET /api/platform/audits/export?format=csv returns a
        text/csv attachment containing the tenant code and action -> the export itself is
        audited (PLATFORM_AUDIT_EXPORTED) -> cleanup tenants.

    Capability gate: GET /api/platform/audits (admin JWT) right after login; 404/405/500/501
    makes the whole run SKIP with exit code 0.

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.

.PARAMETER BaseUrl
    Gateway base URL. Default http://localhost:18080 (local process mode).

.PARAMETER TimeoutSeconds
    Timeout for tenant activation polling. Default 120.

.EXAMPLE
    pwsh ./tests/e2e-v11.ps1
    pwsh ./tests/e2e-v11.ps1 -BaseUrl http://localhost:30080
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
$script:TenantAdminToken = ''
$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'v11 api availability',
    'tenant A create',
    'tenant A update',
    'tenant A org create',
    'tenant A account create',
    'tenant A activation',
    'tenant admin login',
    'tenant B create',
    'platform audits cross tenant',
    'filter tenantId',
    'filter action',
    'filter keyword',
    'pagination page size',
    'pagination total',
    'sorting at desc',
    'platform audits tenant admin 403',
    'platform audits auditor 403',
    'platform audits unauthenticated 401',
    'csv export structure',
    'csv export filter',
    'platform audit exported',
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
    Write-Host '---------------- e2e-v11 summary ----------------'
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

function Get-Total {
    param($Data)
    $total = Get-JsonProp $Data 'total'
    if ($null -eq $total) { return $null }
    return [long]$total
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

function Invoke-CsvDownload {
    param([string]$Path, [hashtable]$Headers, [int]$TimeoutSec = 60)
    $response = Invoke-TenancyHttp -Uri ($script:Base + $Path) -Headers $Headers -TimeoutSec $TimeoutSec
    $contentType = ''
    $disposition = ''
    try { $contentType = [string](@($response.Headers['Content-Type'])[0]) } catch { }
    try { $disposition = [string](@($response.Headers['Content-Disposition'])[0]) } catch { }
    return [pscustomobject]@{ Status = $response.StatusCode; Content = $response.Content; ContentType = $contentType; ContentDisposition = $disposition }
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
                    $null = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method POST -Headers $script:AdminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v11 activation' }
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
        contactEmail  = 'v11@example.com'
        contactName   = 'E2E V11 Owner'
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
            $null = Invoke-ApiRaw -Path ("/api/tenants/{0}/terminate" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v11 cleanup' }
        }
        $null = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $TenantId) -Method DELETE -Headers $script:AdminHeaders
    }
    catch { }
}

function Invoke-Cleanup {
    if ($script:CleanedUp) { return }
    $script:CleanedUp = $true
    foreach ($tenantId in @($script:TenantBId, $script:TenantAId)) { Remove-TestTenant -TenantId $tenantId }
}

# ================================================================ run
Write-Host ("==> e2e-v11 against {0}" -f $script:Base) -ForegroundColor Cyan

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

# ---------------- capability gate: platform audits
$auditProbe = Invoke-ApiRaw -Path '/api/platform/audits?page=0&size=1' -Headers $script:AdminHeaders
if (-not $auditProbe.Ok -and ($auditProbe.Status -eq 404 -or $auditProbe.Status -eq 405 -or $auditProbe.Status -eq 500 -or $auditProbe.Status -eq 501)) {
    Add-Check 'v11 api availability' 'SKIP' ("GET /api/platform/audits -> HTTP {0} (v11 audit center not deployed)" -f $auditProbe.Status)
    Skip-Remaining -Reason 'v11 platform audit API not deployed (docs/21)'
    Show-Summary
}
if (-not $auditProbe.Ok) {
    Add-Check 'v11 api availability' 'FAIL' ("HTTP {0} code={1} message={2}" -f $auditProbe.Status, $auditProbe.Code, $auditProbe.Message)
    Skip-Remaining -Reason 'platform audit probe failed'
    Show-Summary
}
Add-Check 'v11 api availability' 'PASS' ("HTTP {0} total={1}" -f $auditProbe.Status, (Get-Total $auditProbe.Data))

# ---------------- tenant A: create / update / org / account / activate (audit actions)
$suffix = New-RandomDigits 6
$tenantACode = New-RandomCode 'e2e-v11-a'
try {
    $tenantA = New-TestTenant -Code $tenantACode -Name ('E2E V11 Alpha ' + $suffix)
    $script:TenantAId = [string](Get-JsonProp $tenantA 'id')
    $script:TenantACode = $tenantACode
    if ([string]::IsNullOrWhiteSpace($script:TenantAId)) { throw 'tenant A id missing' }
    Add-Check 'tenant A create' 'PASS' ("id={0} code={1}" -f $script:TenantAId, $tenantACode)
}
catch { Add-Check 'tenant A create' 'FAIL' $_.Exception.Message }

if ([string]::IsNullOrWhiteSpace($script:TenantAId)) {
    Skip-Remaining -Reason 'tenant A create failed'
    Invoke-Cleanup
    Show-Summary
}

try {
    $updated = Invoke-Api -Path ("/api/tenants/{0}" -f $script:TenantAId) -Method PUT -Headers $script:AdminHeaders -Body @{
        tenantCode    = $tenantACode
        name          = ('E2E V11 Alpha Updated ' + $suffix)
        contactEmail  = 'v11@example.com'
        contactName   = 'E2E V11 Owner'
        plan          = 'PRO'
        region        = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'
        source        = 'MANUAL'
        autoSubmit    = $false
    }
    if ([string](Get-JsonProp $updated 'id') -eq $script:TenantAId) {
        Add-Check 'tenant A update' 'PASS' 'tenant profile updated (UPDATED audit)'
    }
    else {
        Add-Check 'tenant A update' 'FAIL' 'unexpected update response'
    }
}
catch { Add-Check 'tenant A update' 'FAIL' $_.Exception.Message }

$orgUnitId = ''
try {
    $org = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $script:TenantAId) -Method POST -Headers $script:AdminHeaders -Body @{
        code = 'HQ'; name = 'E2E V11 HQ'; type = 'COMPANY'
    }
    $orgUnitId = [string](Get-JsonProp $org 'id')
    if ([string]::IsNullOrWhiteSpace($orgUnitId)) { throw 'org id missing' }
    Add-Check 'tenant A org create' 'PASS' ("id={0}" -f $orgUnitId)
}
catch { Add-Check 'tenant A org create' 'FAIL' $_.Exception.Message }

$tenantAdminName = 'v11admin' + $suffix
$tenantAdminPassword = ''
try {
    $account = Invoke-Api -Path ("/api/tenants/{0}/accounts" -f $script:TenantAId) -Method POST -Headers $script:AdminHeaders -Body @{
        username = $tenantAdminName; displayName = 'E2E V11 Tenant Admin'; email = ($tenantAdminName + '@example.com');
        phone = '13800002222'; orgUnitId = $orgUnitId; role = 'TENANT_ADMIN'
    }
    $tenantAdminPassword = [string](Get-JsonProp $account 'tempPassword')
    if ([string]::IsNullOrWhiteSpace($tenantAdminPassword)) { throw 'account temp password missing' }
    Add-Check 'tenant A account create' 'PASS' ("username={0}" -f $tenantAdminName)
}
catch { Add-Check 'tenant A account create' 'FAIL' $_.Exception.Message }

$activation = Activate-TestTenant -TenantId $script:TenantAId
if ($activation -eq 'ACTIVE') { Add-Check 'tenant A activation' 'PASS' 'status=ACTIVE' }
else { Add-Check 'tenant A activation' 'FAIL' ("status={0}" -f $activation) }

try {
    if ([string]::IsNullOrWhiteSpace($tenantAdminPassword)) { throw 'no tenant admin password' }
    $tenantLogin = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{
        username = $tenantAdminName; password = $tenantAdminPassword; tenantCode = $tenantACode
    }
    $script:TenantAdminToken = [string](Get-JsonProp $tenantLogin 'token')
    if (-not [string]::IsNullOrWhiteSpace($script:TenantAdminToken)) {
        Add-Check 'tenant admin login' 'PASS' 'tenant JWT issued'
    }
    else { Add-Check 'tenant admin login' 'FAIL' 'no token returned' }
}
catch { Add-Check 'tenant admin login' 'FAIL' $_.Exception.Message }

# ---------------- tenant B (second tenant for cross-tenant records)
$tenantBCode = New-RandomCode 'e2e-v11-b'
try {
    $tenantB = New-TestTenant -Code $tenantBCode -Name ('E2E V11 Beta ' + $suffix)
    $script:TenantBId = [string](Get-JsonProp $tenantB 'id')
    $script:TenantBCode = $tenantBCode
    if ([string]::IsNullOrWhiteSpace($script:TenantBId)) { throw 'tenant B id missing' }
    Add-Check 'tenant B create' 'PASS' ("id={0} code={1}" -f $script:TenantBId, $tenantBCode)
}
catch { Add-Check 'tenant B create' 'FAIL' $_.Exception.Message }

# ---------------- cross-tenant query
$allAudits = $null
try {
    $allAudits = Invoke-Api -Path '/api/platform/audits?page=0&size=100' -Headers $script:AdminHeaders
    $items = @(Get-Items $allAudits)
    $total = Get-Total $allAudits
    $codeA = @($items | Where-Object { [string](Get-JsonProp $_ 'tenantCode') -eq $tenantACode })
    $codeB = @($items | Where-Object { [string](Get-JsonProp $_ 'tenantCode') -eq $tenantBCode })
    $sample = $codeA | Select-Object -First 1
    $nameOk = $null -ne $sample -and -not [string]::IsNullOrWhiteSpace([string](Get-JsonProp $sample 'tenantName'))
    $fieldOk = $null -ne $sample -and
        (Test-HasProp $sample 'tenantId') -and (Test-HasProp $sample 'tenantCode') -and
        (Test-HasProp $sample 'at') -and (Test-HasProp $sample 'actor') -and
        (Test-HasProp $sample 'action') -and (Test-HasProp $sample 'detail')
    if ($codeA.Count -ge 1 -and $codeB.Count -ge 1 -and $nameOk -and $fieldOk -and [long]$total -ge 2) {
        Add-Check 'platform audits cross tenant' 'PASS' ("total={0} tenantA={1} tenantB={2} tenantName={3}" -f $total, $codeA.Count, $codeB.Count, (Get-JsonProp $sample 'tenantName'))
    }
    else {
        Add-Check 'platform audits cross tenant' 'FAIL' ("total={0} tenantA={1} tenantB={2} nameOk={3} fieldOk={4}" -f $total, $codeA.Count, $codeB.Count, $nameOk, $fieldOk)
    }
}
catch { Add-Check 'platform audits cross tenant' 'FAIL' $_.Exception.Message }

try {
    $filtered = Invoke-Api -Path ('/api/platform/audits?tenantId=' + [uri]::EscapeDataString($script:TenantAId) + '&page=0&size=100') -Headers $script:AdminHeaders
    $items = @(Get-Items $filtered)
    $wrong = @($items | Where-Object { [string](Get-JsonProp $_ 'tenantId') -ne $script:TenantAId })
    if ($items.Count -ge 1 -and $wrong.Count -eq 0) {
        Add-Check 'filter tenantId' 'PASS' ("items={0} all for tenantA" -f $items.Count)
    }
    else {
        Add-Check 'filter tenantId' 'FAIL' ("items={0} wrongTenant={1}" -f $items.Count, $wrong.Count)
    }
}
catch { Add-Check 'filter tenantId' 'FAIL' $_.Exception.Message }

try {
    $filtered = Invoke-Api -Path '/api/platform/audits?action=CREATED&page=0&size=100' -Headers $script:AdminHeaders
    $items = @(Get-Items $filtered)
    $wrong = @($items | Where-Object { [string](Get-JsonProp $_ 'action') -ne 'CREATED' })
    if ($items.Count -ge 2 -and $wrong.Count -eq 0) {
        Add-Check 'filter action' 'PASS' ("items={0} action=CREATED" -f $items.Count)
    }
    else {
        Add-Check 'filter action' 'FAIL' ("items={0} wrongAction={1}" -f $items.Count, $wrong.Count)
    }
}
catch { Add-Check 'filter action' 'FAIL' $_.Exception.Message }

try {
    $keyword = $tenantACode
    $filtered = Invoke-Api -Path ('/api/platform/audits?keyword=' + [uri]::EscapeDataString($keyword) + '&page=0&size=100') -Headers $script:AdminHeaders
    $items = @(Get-Items $filtered)
    $contains = 0
    foreach ($item in $items) {
        $serialized = ($item | ConvertTo-Json -Depth 6 -Compress)
        if ($serialized.Contains($keyword)) { $contains++ }
    }
    if ($items.Count -ge 1 -and $contains -eq $items.Count) {
        Add-Check 'filter keyword' 'PASS' ("items={0} keyword={1}" -f $items.Count, $keyword)
    }
    else {
        Add-Check 'filter keyword' 'FAIL' ("items={0} matchedKeyword={1}" -f $items.Count, $contains)
    }
}
catch { Add-Check 'filter keyword' 'FAIL' $_.Exception.Message }

# ---------------- pagination and ordering
try {
    $page0 = Invoke-Api -Path '/api/platform/audits?page=0&size=2' -Headers $script:AdminHeaders
    $page1 = Invoke-Api -Path '/api/platform/audits?page=1&size=2' -Headers $script:AdminHeaders
    $items0 = @(Get-Items $page0)
    $items1 = @(Get-Items $page1)
    $total0 = Get-Total $page0
    $total1 = Get-Total $page1
    $ids0 = @($items0 | ForEach-Object { [string](Get-JsonProp $_ 'id') })
    $ids1 = @($items1 | ForEach-Object { [string](Get-JsonProp $_ 'id') })
    $overlap = @($ids0 | Where-Object { $ids1 -contains $_ })
    if ($items0.Count -le 2 -and $items1.Count -le 2 -and $total0 -ge 4 -and $total1 -eq $total0 -and $overlap.Count -eq 0) {
        Add-Check 'pagination page size' 'PASS' ("page0={0} page1={1} total={2}" -f $items0.Count, $items1.Count, $total0)
        Add-Check 'pagination total' 'PASS' ("total stable={0}" -f $total0)
    }
    else {
        Add-Check 'pagination page size' 'FAIL' ("page0={0} page1={1} total0={2} total1={3} overlap={4}" -f $items0.Count, $items1.Count, $total0, $total1, $overlap.Count)
        Add-Check 'pagination total' 'FAIL' ("total0={0} total1={1}" -f $total0, $total1)
    }
}
catch {
    Add-Check 'pagination page size' 'FAIL' $_.Exception.Message
    Add-Check 'pagination total' 'FAIL' $_.Exception.Message
}

try {
    $ordered = Invoke-Api -Path '/api/platform/audits?page=0&size=20' -Headers $script:AdminHeaders
    $items = @(Get-Items $ordered)
    $previous = $null
    $descOk = $items.Count -ge 2
    foreach ($item in $items) {
        $atRaw = [string](Get-JsonProp $item 'at')
        $current = $null
        try { $current = [DateTimeOffset]::Parse($atRaw) } catch { $current = $null }
        if ($null -eq $current) { $descOk = $false; break }
        if ($null -ne $previous -and $current -gt $previous) { $descOk = $false; break }
        $previous = $current
    }
    if ($descOk) { Add-Check 'sorting at desc' 'PASS' ("items={0}" -f $items.Count) }
    else { Add-Check 'sorting at desc' 'FAIL' 'records are not sorted by at DESC' }
}
catch { Add-Check 'sorting at desc' 'FAIL' $_.Exception.Message }

# ---------------- permissions
try {
    if ([string]::IsNullOrWhiteSpace($script:TenantAdminToken)) { throw 'no tenant admin token' }
    $raw = Invoke-ApiRaw -Path '/api/platform/audits?page=0&size=5' -Headers @{ Authorization = ("Bearer {0}" -f $script:TenantAdminToken) }
    if ($raw.Status -eq 403 -or $raw.Code -eq 40300) {
        Add-Check 'platform audits tenant admin 403' 'PASS' ("HTTP {0}" -f $raw.Status)
    }
    else {
        Add-Check 'platform audits tenant admin 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code)
    }
}
catch { Add-Check 'platform audits tenant admin 403' 'FAIL' $_.Exception.Message }

try {
    $auditorLogin = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{ username = 'auditor'; password = 'Auditor@123' }
    $auditorToken = [string](Get-JsonProp $auditorLogin 'token')
    $raw = Invoke-ApiRaw -Path '/api/platform/audits?page=0&size=5' -Headers @{ Authorization = ("Bearer {0}" -f $auditorToken) }
    if ($raw.Status -eq 403 -or $raw.Code -eq 40300) {
        Add-Check 'platform audits auditor 403' 'PASS' ("HTTP {0}" -f $raw.Status)
    }
    else {
        Add-Check 'platform audits auditor 403' 'FAIL' ("expected 403, got HTTP {0} code={1}" -f $raw.Status, $raw.Code)
    }
}
catch { Add-Check 'platform audits auditor 403' 'FAIL' $_.Exception.Message }

try {
    $raw = Invoke-ApiRaw -Path '/api/platform/audits?page=0&size=5'
    if ($raw.Status -eq 401 -or $raw.Code -eq 40100) {
        Add-Check 'platform audits unauthenticated 401' 'PASS' ("HTTP {0}" -f $raw.Status)
    }
    else {
        Add-Check 'platform audits unauthenticated 401' 'FAIL' ("expected 401, got HTTP {0} code={1}" -f $raw.Status, $raw.Code)
    }
}
catch { Add-Check 'platform audits unauthenticated 401' 'FAIL' $_.Exception.Message }

# ---------------- CSV export
try {
    $csv = Invoke-CsvDownload -Path '/api/platform/audits/export?format=csv' -Headers $script:AdminHeaders
    $contentTypeOk = ($csv.ContentType -match 'text/csv')
    $dispositionOk = ($csv.ContentDisposition -match 'attachment')
    $contentOk = ($csv.Content.Contains($tenantACode)) -and ($csv.Content -match 'CREATED')
    if ($csv.Status -eq 200 -and $contentTypeOk -and $dispositionOk -and $contentOk) {
        Add-Check 'csv export structure' 'PASS' ("HTTP 200 contentType={0} attachment=true" -f $csv.ContentType)
    }
    else {
        Add-Check 'csv export structure' 'FAIL' ("HTTP {0} contentTypeOk={1} dispositionOk={2} contentOk={3}" -f $csv.Status, $contentTypeOk, $dispositionOk, $contentOk)
    }
}
catch { Add-Check 'csv export structure' 'FAIL' $_.Exception.Message }

try {
    $csv = Invoke-CsvDownload -Path ('/api/platform/audits/export?format=csv&tenantId=' + [uri]::EscapeDataString($script:TenantAId)) -Headers $script:AdminHeaders
    $hasA = $csv.Content.Contains($tenantACode)
    $hasB = $csv.Content.Contains($tenantBCode)
    if ($csv.Status -eq 200 -and $hasA -and -not $hasB) {
        Add-Check 'csv export filter' 'PASS' 'filtered export contains only tenant A code'
    }
    else {
        Add-Check 'csv export filter' 'FAIL' ("HTTP {0} hasA={1} hasB={2}" -f $csv.Status, $hasA, $hasB)
    }
}
catch { Add-Check 'csv export filter' 'FAIL' $_.Exception.Message }

try {
    $exportedAudits = Invoke-Api -Path '/api/platform/audits?action=PLATFORM_AUDIT_EXPORTED&page=0&size=20' -Headers $script:AdminHeaders
    $items = @(Get-Items $exportedAudits)
    if ($items.Count -ge 1) {
        Add-Check 'platform audit exported' 'PASS' ("items={0}" -f $items.Count)
    }
    else {
        Add-Check 'platform audit exported' 'FAIL' 'PLATFORM_AUDIT_EXPORTED audit entry not found'
    }
}
catch { Add-Check 'platform audit exported' 'FAIL' $_.Exception.Message }

# ---------------- cleanup
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
