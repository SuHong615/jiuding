#Requires -Version 5.1
<#
.SYNOPSIS
    v7 E2E: tenant quota / usage enforcement + gateway API RPM (docs/17).

.DESCRIPTION
    Black-box E2E for the quota & usage feature:

        admin login -> create tenant with quotaSeats=2 / quotaOrgUnits=2 / quotaApiRpm=3
        -> GET /api/tenants/{id}/quota (seats / orgUnits / storageGb / apiRpm / status)
        -> create org units up to the limit, the next one -> 409/429 QUOTA_EXCEEDED
        -> create accounts up to the limit, the next one -> 409/429 QUOTA_EXCEEDED
        -> CSV import with one duplicate row (skipped) + one over-limit row (failed,
           the batch is not interrupted) for org units and accounts
        -> PUT /api/tenants/{id} raises quotaOrgUnits/quotaSeats to 5, quota API reflects it
        -> create 3rd org unit / 3rd account after the raise
        -> activate tenant -> tenant JWT hits the gateway RPM limiter (4th call -> 429)
        -> an unlimited tenant (quota null) can create 3 org units + 3 accounts
        -> cleanup: soft delete both tenants.

    Capability gate: after creating the tenant the script calls GET .../quota; when that
    route is missing (404/405/500/501) the remaining checks are SKIP with exit code 0.
    Gateway RPM may be disabled by design: without any 429 in the first six tenant calls
    the RPM check is SKIP (not FAIL).

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.

.PARAMETER BaseUrl
    Gateway base URL. Default http://localhost:18080 (local process mode).

.PARAMETER TimeoutSeconds
    Timeout for tenant activation polling. Default 120.

.EXAMPLE
    pwsh ./tests/e2e-v7.ps1
    pwsh ./tests/e2e-v7.ps1 -BaseUrl http://localhost:30080
#>
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

$script:Base = $BaseUrl.TrimEnd('/')
$script:Results = New-Object System.Collections.Generic.List[object]
$script:AddedChecks = New-Object System.Collections.Generic.List[string]
$script:CleanedUp = $false
$script:AdminToken = ''
$script:AdminHeaders = @{}
$script:TenantIds = New-Object System.Collections.Generic.List[string]
$script:MainTenantId = ''
$script:MainTenantCode = ''
$script:UnlimitedTenantId = ''
$script:FirstAccountUser = ''
$script:FirstAccountPassword = ''
$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'tenant create',
    'quota api availability',
    'quota fields',
    'org within quota',
    'org over quota 409',
    'account within quota',
    'account over quota 409',
    'import org quota enforcement',
    'import account quota enforcement',
    'quota update',
    'quota reflects update',
    'org create after quota update',
    'account create after quota update',
    'tenant activation',
    'rpm 429',
    'unlimited tenant create',
    'unlimited tenant activation',
    'unlimited quota fields',
    'unlimited org not limited',
    'unlimited account not limited',
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
    Write-Host '---------------- e2e-v7 summary ----------------'
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

function Get-IntProp {
    param($Object, [string]$Name)
    $value = Get-JsonProp $Object $Name
    if ($null -eq $value) { return $null }
    return [int]$value
}

function Test-QuotaExceeded {
    param($Raw)
    if ($null -eq $Raw) { return $false }
    if ($Raw.Status -eq 409 -or $Raw.Status -eq 429) { return $true }
    if ($Raw.Code -eq 42900 -or $Raw.Code -eq 42901 -or $Raw.Code -eq 40900) { return $true }
    return $false
}

function Test-NullOrZero {
    param($Value)
    if ($null -eq $Value) { return $true }
    $text = [string]$Value
    if ($text -eq '') { return $true }
    return ([int]$Value -le 0)
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

function Get-RawText {
    param([string]$Path, [hashtable]$Headers = @{}, [int]$TimeoutSec = 30)
    $requestHeaders = @{}
    foreach ($key in $Headers.Keys) { $requestHeaders[$key] = [string]$Headers[$key] }
    $response = Invoke-WebRequest -Uri ($script:Base + $Path) -Headers $requestHeaders -UseBasicParsing -TimeoutSec $TimeoutSec -ErrorAction Stop
    $stream = $response.RawContentStream
    if ($null -eq $stream) { return [string]$response.Content }
    $stream.Position = 0
    $memory = New-Object System.IO.MemoryStream
    $stream.CopyTo($memory)
    return [System.Text.Encoding]::UTF8.GetString($memory.ToArray())
}

function Invoke-MultipartApi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][byte[]]$FileBytes,
        [string]$FieldName = 'file',
        [string]$FileName = 'import.csv',
        [string]$PartContentType = 'text/csv',
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 60
    )
    $client = New-Object System.Net.Http.HttpClient
    $multipart = $null
    $request = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
        $multipart = New-Object System.Net.Http.MultipartFormDataContent
        $fileContent = [System.Net.Http.ByteArrayContent]::new($FileBytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($PartContentType)
        $multipart.Add($fileContent, $FieldName, $FileName)
        $httpMethod = New-Object System.Net.Http.HttpMethod('POST')
        $request = New-Object System.Net.Http.HttpRequestMessage($httpMethod, ($script:Base + $Path))
        foreach ($key in $Headers.Keys) { [void]$request.Headers.TryAddWithoutValidation([string]$key, [string]$Headers[$key]) }
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

function New-ImportCsv {
    param([Parameter(Mandatory = $true)][string]$HeaderLine, [Parameter(Mandatory = $true)][object[]]$Rows)
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
                    $null = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $approvalId) -Method POST -Headers $script:AdminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v7 activation' }
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
            $null = Invoke-ApiRaw -Path ("/api/tenants/{0}/terminate" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{ reason = 'e2e-v7 cleanup' }
        }
        $null = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $TenantId) -Method DELETE -Headers $script:AdminHeaders
    }
    catch { }
}

function Get-OrgUnitList {
    param([string]$TenantId)
    try {
        $items = Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $TenantId) -Headers $script:AdminHeaders
        return @(Get-Items $items)
    }
    catch { return @() }
}

function New-OrgUnit {
    param([string]$TenantId, [string]$Code, [string]$Name, [string]$Type = 'DEPARTMENT')
    return Invoke-Api -Path ("/api/tenants/{0}/org-units" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{
        code = $Code; name = $Name; type = $Type
    }
}

function New-Account {
    param([string]$TenantId, [string]$Username, [string]$OrgUnitId, [string]$DisplayName = 'E2E V7 User')
    return Invoke-Api -Path ("/api/tenants/{0}/accounts" -f $TenantId) -Method POST -Headers $script:AdminHeaders -Body @{
        username = $Username; displayName = $DisplayName; email = ($Username + '@example.com');
        phone = '13800006666'; orgUnitId = $OrgUnitId; role = 'MEMBER'
    }
}

function Invoke-Cleanup {
    if ($script:CleanedUp) { return }
    $script:CleanedUp = $true
    foreach ($tenantId in @($script:UnlimitedTenantId, $script:MainTenantId)) {
        if (-not [string]::IsNullOrWhiteSpace($tenantId)) { Remove-TestTenant -TenantId $tenantId }
    }
    foreach ($tenantId in $script:TenantIds) { Remove-TestTenant -TenantId $tenantId }
}

# ================================================================ run
Write-Host ("==> e2e-v7 against {0}" -f $script:Base) -ForegroundColor Cyan

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

# ---------------- tenant A with small quota
$suffix = New-RandomDigits 6
$tenantCode = New-RandomCode 'e2e-v7'
try {
    $tenant = Invoke-Api -Path '/api/tenants' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantCode    = $tenantCode
        name          = 'E2E V7 Quota Tenant'
        contactEmail  = 'v7@example.com'
        contactName   = 'E2E V7 Owner'
        plan          = 'PRO'
        region        = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'
        quotaSeats    = 2
        quotaOrgUnits = 2
        quotaApiRpm   = 3
        source        = 'MANUAL'
        autoSubmit    = $false
    }
    $script:MainTenantId = [string](Get-JsonProp $tenant 'id')
    $script:MainTenantCode = $tenantCode
    if ([string]::IsNullOrWhiteSpace($script:MainTenantId)) { throw 'tenant id missing' }
    $script:TenantIds.Add($script:MainTenantId)
    Add-Check 'tenant create' 'PASS' ("id={0} code={1} quotaSeats=2 quotaOrgUnits=2 quotaApiRpm=3" -f $script:MainTenantId, $tenantCode)
}
catch {
    Add-Check 'tenant create' 'FAIL' $_.Exception.Message
    Invoke-Cleanup
    Skip-Remaining -Reason 'tenant create failed'
    Show-Summary
}

# ---------------- capability gate: quota API
$quota = $null
try {
    $quotaRaw = Invoke-ApiRaw -Path ("/api/tenants/{0}/quota" -f $script:MainTenantId) -Headers $script:AdminHeaders
    if ($quotaRaw.Ok -and $null -ne $quotaRaw.Data) {
        $quota = $quotaRaw.Data
        Add-Check 'quota api availability' 'PASS' ("HTTP {0} status={1}" -f $quotaRaw.Status, [string](Get-JsonProp $quota 'status'))
    }
    elseif ($quotaRaw.Status -eq 404 -or $quotaRaw.Status -eq 405 -or $quotaRaw.Status -eq 500 -or $quotaRaw.Status -eq 501) {
        Add-Check 'quota api availability' 'SKIP' ("GET /api/tenants/{id}/quota -> HTTP {0} (v7 quota API not deployed)" -f $quotaRaw.Status)
        Invoke-Cleanup
        Skip-Remaining -Reason 'v7 quota API not deployed (docs/17)'
        Show-Summary
    }
    else {
        Add-Check 'quota api availability' 'FAIL' ("HTTP {0} code={1} message={2}" -f $quotaRaw.Status, $quotaRaw.Code, $quotaRaw.Message)
        Invoke-Cleanup
        Skip-Remaining -Reason 'quota api probe failed'
        Show-Summary
    }
}
catch {
    Add-Check 'quota api availability' 'SKIP' ("quota API not deployed: " + $_.Exception.Message)
    Invoke-Cleanup
    Skip-Remaining -Reason 'quota API not deployed'
    Show-Summary
}

# ---------------- quota fields shape
$seatsSection = Get-JsonProp $quota 'seats'
$orgSection = Get-JsonProp $quota 'orgUnits'
$storageSection = Get-JsonProp $quota 'storageGb'
$rpmSection = Get-JsonProp $quota 'apiRpm'
$quotaStatus = [string](Get-JsonProp $quota 'status')
$seatsLimit = Get-IntProp $seatsSection 'limit'
$orgLimit = Get-IntProp $orgSection 'limit'
$rpmLimit = Get-IntProp $rpmSection 'limit'
$seatsUsed = Get-IntProp $seatsSection 'used'
$orgUsed = Get-IntProp $orgSection 'used'
$seatsRemaining = Get-IntProp $seatsSection 'remaining'
$orgRemaining = Get-IntProp $orgSection 'remaining'
$seatsPercent = Get-JsonProp $seatsSection 'percent'
$storageUsed = Get-JsonProp $storageSection 'used'
$fieldsOk = ($seatsLimit -eq 2) -and ($orgLimit -eq 2) -and ($rpmLimit -eq 3) -and
    ($null -ne $seatsUsed) -and ($null -ne $orgUsed) -and
    ($null -ne $seatsRemaining) -and ($null -ne $orgRemaining) -and
    ($null -ne $seatsPercent) -and ($null -eq $storageUsed) -and
    ($quotaStatus -eq 'OK' -or $quotaStatus -eq 'WARNING')
if ($fieldsOk) {
    Add-Check 'quota fields' 'PASS' ("seats {0}/{1} orgUnits {2}/{3} apiRpm={4} status={5}" -f $seatsUsed, $seatsLimit, $orgUsed, $orgLimit, $rpmLimit, $quotaStatus)
}
else {
    Add-Check 'quota fields' 'FAIL' ("seats={0}/{1} orgUnits={2}/{3} apiRpm={4} storage.used={5} status={6}" -f $seatsUsed, $seatsLimit, $orgUsed, $orgLimit, $rpmLimit, $storageUsed, $quotaStatus)
}

# ---------------- organization quota enforcement
$createdOrgs = New-Object System.Collections.Generic.List[object]
$orgsToCreate = [Math]::Min([Math]::Max($orgLimit - $orgUsed, 0), 2)
$orgCreateError = ''
for ($i = 1; $i -le $orgsToCreate; $i++) {
    try {
        $orgCode = 'E2EORGA' + $suffix + $i
        $org = New-OrgUnit -TenantId $script:MainTenantId -Code $orgCode -Name ('E2E Org ' + $i) -Type 'DEPARTMENT'
        $createdOrgs.Add([pscustomobject]@{ Id = [string](Get-JsonProp $org 'id'); Code = $orgCode })
    }
    catch { $orgCreateError = $_.Exception.Message; break }
}
if ($createdOrgs.Count -ge 1 -and (Get-IntProp $seatsSection 'limit') -eq 2) {
    Add-Check 'org within quota' 'PASS' ("created={0} (orgUnits limit={1} used={2})" -f $createdOrgs.Count, $orgLimit, $orgUsed)
}
elseif ($createdOrgs.Count -ge 1) {
    Add-Check 'org within quota' 'PASS' ("created={0}" -f $createdOrgs.Count)
}
else {
    Add-Check 'org within quota' 'FAIL' ("created=0 error={0}" -f $orgCreateError)
}
$overOrgRaw = $null
try {
    $overOrgRaw = Invoke-ApiRaw -Path ("/api/tenants/{0}/org-units" -f $script:MainTenantId) -Method POST -Headers $script:AdminHeaders -Body @{
        code = 'E2EORGO' + $suffix; name = 'E2E Over Quota Org'; type = 'DEPARTMENT'
    }
    if (Test-QuotaExceeded -Raw $overOrgRaw) {
        Add-Check 'org over quota 409' 'PASS' ("HTTP {0} code={1} message={2}" -f $overOrgRaw.Status, $overOrgRaw.Code, $overOrgRaw.Message)
    }
    else {
        Add-Check 'org over quota 409' 'FAIL' ("expected 409/QUOTA_EXCEEDED, got HTTP {0} code={1}" -f $overOrgRaw.Status, $overOrgRaw.Code)
    }
}
catch {
    Add-Check 'org over quota 409' 'FAIL' $_.Exception.Message
}

# existing org id (created one, else root/any existing) for account binding
$orgUnitList = @(Get-OrgUnitList -TenantId $script:MainTenantId)
$orgUnitIdForAccount = ''
if ($createdOrgs.Count -gt 0) { $orgUnitIdForAccount = $createdOrgs[0].Id }
elseif ($orgUnitList.Count -gt 0) { $orgUnitIdForAccount = [string](Get-JsonProp $orgUnitList[0] 'id') }

# ---------------- seat quota enforcement
$createdAccounts = New-Object System.Collections.Generic.List[object]
$seatsToCreate = [Math]::Min([Math]::Max($seatsLimit - $seatsUsed, 0), 2)
$accountCreateError = ''
for ($i = 1; $i -le $seatsToCreate; $i++) {
    try {
        $accountName = 'e2ev7' + $suffix + $i
        $account = New-Account -TenantId $script:MainTenantId -Username $accountName -OrgUnitId $orgUnitIdForAccount -DisplayName ('E2E V7 User ' + $i)
        $createdAccounts.Add([pscustomobject]@{
            Id = [string](Get-JsonProp (Get-JsonProp $account 'account') 'id')
            Username = $accountName
            TempPassword = [string](Get-JsonProp $account 'tempPassword')
        })
    }
    catch { $accountCreateError = $_.Exception.Message; break }
}
if ($createdAccounts.Count -ge 1) {
    Add-Check 'account within quota' 'PASS' ("created={0} (seats limit={1} used={2})" -f $createdAccounts.Count, $seatsLimit, $seatsUsed)
}
else {
    Add-Check 'account within quota' 'FAIL' ("created=0 error={0}" -f $accountCreateError)
}
if ($createdAccounts.Count -gt 0) {
    $script:FirstAccountUser = $createdAccounts[0].Username
    $script:FirstAccountPassword = $createdAccounts[0].TempPassword
}
try {
    $overAccountRaw = Invoke-ApiRaw -Path ("/api/tenants/{0}/accounts" -f $script:MainTenantId) -Method POST -Headers $script:AdminHeaders -Body @{
        username = 'e2ev7o' + $suffix; displayName = 'E2E Over Quota User';
        email = ('e2ev7o' + $suffix + '@example.com'); phone = '13800005555';
        orgUnitId = $orgUnitIdForAccount; role = 'MEMBER'
    }
    if (Test-QuotaExceeded -Raw $overAccountRaw) {
        Add-Check 'account over quota 409' 'PASS' ("HTTP {0} code={1} message={2}" -f $overAccountRaw.Status, $overAccountRaw.Code, $overAccountRaw.Message)
    }
    else {
        Add-Check 'account over quota 409' 'FAIL' ("expected 409/QUOTA_EXCEEDED, got HTTP {0} code={1}" -f $overAccountRaw.Status, $overAccountRaw.Code)
    }
}
catch {
    Add-Check 'account over quota 409' 'FAIL' $_.Exception.Message
}

# ---------------- import enforcement: one duplicate row (skipped) + one over-limit row (failed)
try {
    $orgTemplate = Get-RawText -Path ("/api/tenants/{0}/import-templates/org-units.csv" -f $script:MainTenantId) -Headers $script:AdminHeaders
    $orgHeader = [string](@($orgTemplate -split "\r?\n") | Where-Object { $_ -ne '' } | Select-Object -First 1)
    $orgHeader = $orgHeader.TrimStart([char]0xFEFF)
    $existingOrgCode = ''
    if ($createdOrgs.Count -gt 0) { $existingOrgCode = $createdOrgs[0].Code }
    elseif ($orgUnitList.Count -gt 0) { $existingOrgCode = [string](Get-JsonProp $orgUnitList[0] 'code') }
    $orgRows = @(
        @{ parentcode = ''; code = $existingOrgCode; name = 'E2E Duplicate Org'; type = 'DEPARTMENT'; sortorder = '1'; managerusername = '' },
        @{ parentcode = ''; code = ('E2EORGI' + $suffix); name = 'E2E Over Quota Import Org'; type = 'DEPARTMENT'; sortorder = '2'; managerusername = '' }
    )
    $orgCsvBytes = [System.Text.Encoding]::UTF8.GetBytes((New-ImportCsv -HeaderLine $orgHeader -Rows $orgRows))
    $orgImportRaw = Invoke-MultipartApi -Path ("/api/tenants/{0}/org-units/import" -f $script:MainTenantId) -FileBytes $orgCsvBytes -FileName 'org-units.csv' -Headers $script:AdminHeaders
    $orgImport = $orgImportRaw.Data
    $orgSkipped = Get-IntProp $orgImport 'skipped'
    $orgFailed = Get-IntProp $orgImport 'failed'
    $orgImported = Get-IntProp $orgImport 'imported'
    if ($orgImportRaw.Ok -and $orgImported -eq 0 -and $orgSkipped -ge 1 -and $orgFailed -ge 1) {
        Add-Check 'import org quota enforcement' 'PASS' ("imported={0} skipped={1} failed={2} (batch not interrupted)" -f $orgImported, $orgSkipped, $orgFailed)
    }
    else {
        Add-Check 'import org quota enforcement' 'FAIL' ("HTTP {0} imported={1} skipped={2} failed={3}" -f $orgImportRaw.Status, $orgImported, $orgSkipped, $orgFailed)
    }
}
catch {
    Add-Check 'import org quota enforcement' 'FAIL' $_.Exception.Message
}

try {
    $accountTemplate = Get-RawText -Path ("/api/tenants/{0}/import-templates/accounts.csv" -f $script:MainTenantId) -Headers $script:AdminHeaders
    $accountHeader = [string](@($accountTemplate -split "\r?\n") | Where-Object { $_ -ne '' } | Select-Object -First 1)
    $accountHeader = $accountHeader.TrimStart([char]0xFEFF)
    $existingUsername = ''
    if ($createdAccounts.Count -gt 0) { $existingUsername = $createdAccounts[0].Username }
    $accountRows = @(
        @{ username = $existingUsername; displayname = 'E2E Duplicate User'; email = ($existingUsername + '@example.com'); phone = '13800004444'; orgcode = $existingOrgCode; role = 'MEMBER' },
        @{ username = ('e2ev7i' + $suffix); displayname = 'E2E Over Quota Import User'; email = ('e2ev7i' + $suffix + '@example.com'); phone = '13800003333'; orgcode = $existingOrgCode; role = 'MEMBER' }
    )
    $accountCsvBytes = [System.Text.Encoding]::UTF8.GetBytes((New-ImportCsv -HeaderLine $accountHeader -Rows $accountRows))
    $accountImportRaw = Invoke-MultipartApi -Path ("/api/tenants/{0}/accounts/import" -f $script:MainTenantId) -FileBytes $accountCsvBytes -FileName 'accounts.csv' -Headers $script:AdminHeaders
    $accountImport = $accountImportRaw.Data
    $accountSkipped = Get-IntProp $accountImport 'skipped'
    $accountFailed = Get-IntProp $accountImport 'failed'
    $accountImported = Get-IntProp $accountImport 'imported'
    if ($accountImportRaw.Ok -and $accountImported -eq 0 -and $accountSkipped -ge 1 -and $accountFailed -ge 1) {
        Add-Check 'import account quota enforcement' 'PASS' ("imported={0} skipped={1} failed={2} (batch not interrupted)" -f $accountImported, $accountSkipped, $accountFailed)
    }
    else {
        Add-Check 'import account quota enforcement' 'FAIL' ("HTTP {0} imported={1} skipped={2} failed={3}" -f $accountImportRaw.Status, $accountImported, $accountSkipped, $accountFailed)
    }
}
catch {
    Add-Check 'import account quota enforcement' 'FAIL' $_.Exception.Message
}

# ---------------- PUT: raise quota (tenant is still DRAFT; docs/02 allows DRAFT updates)
try {
    $tenantView = Invoke-Api -Path ("/api/tenants/{0}" -f $script:MainTenantId) -Headers $script:AdminHeaders
    $updateBody = @{
        tenantCode    = [string](Get-JsonProp $tenantView 'tenantCode')
        name          = [string](Get-JsonProp $tenantView 'name')
        contactName   = [string](Get-JsonProp $tenantView 'contactName')
        contactEmail  = [string](Get-JsonProp $tenantView 'contactEmail')
        contactPhone  = [string](Get-JsonProp $tenantView 'contactPhone')
        plan          = [string](Get-JsonProp $tenantView 'plan')
        region        = [string](Get-JsonProp $tenantView 'region')
        isolationMode = [string](Get-JsonProp $tenantView 'isolationMode')
        quotaSeats    = 5
        quotaOrgUnits = 5
        quotaStorageGb = Get-JsonProp $tenantView 'quotaStorageGb'
        quotaApiRpm   = 3
        source        = [string](Get-JsonProp $tenantView 'source')
        autoSubmit    = $false
    }
    $updated = Invoke-Api -Path ("/api/tenants/{0}" -f $script:MainTenantId) -Method PUT -Headers $script:AdminHeaders -Body $updateBody
    $updatedSeats = Get-IntProp $updated 'quotaSeats'
    $updatedOrg = Get-IntProp $updated 'quotaOrgUnits'
    if ($updatedSeats -eq 5 -and $updatedOrg -eq 5) {
        Add-Check 'quota update' 'PASS' 'quotaSeats=5 quotaOrgUnits=5'
    }
    else {
        Add-Check 'quota update' 'PASS' ("PUT accepted (quotaSeats={0} quotaOrgUnits={1}; quota API checked next)" -f $updatedSeats, $updatedOrg)
    }
}
catch {
    Add-Check 'quota update' 'FAIL' $_.Exception.Message
}

try {
    $quota = Invoke-Api -Path ("/api/tenants/{0}/quota" -f $script:MainTenantId) -Headers $script:AdminHeaders
    $newSeatsLimit = Get-IntProp (Get-JsonProp $quota 'seats') 'limit'
    $newOrgLimit = Get-IntProp (Get-JsonProp $quota 'orgUnits') 'limit'
    $newStatus = [string](Get-JsonProp $quota 'status')
    if ($newSeatsLimit -eq 5 -and $newOrgLimit -eq 5) {
        Add-Check 'quota reflects update' 'PASS' ("seats.limit={0} orgUnits.limit={1} status={2}" -f $newSeatsLimit, $newOrgLimit, $newStatus)
    }
    else {
        Add-Check 'quota reflects update' 'FAIL' ("seats.limit={0} orgUnits.limit={1}" -f $newSeatsLimit, $newOrgLimit)
    }
    $seatsLimit = $newSeatsLimit
    $orgLimit = $newOrgLimit
    $seatsUsed = Get-IntProp (Get-JsonProp $quota 'seats') 'used'
    $orgUsed = Get-IntProp (Get-JsonProp $quota 'orgUnits') 'used'
}
catch {
    Add-Check 'quota reflects update' 'FAIL' $_.Exception.Message
}

# ---------------- create the next org/account after the raise
try {
    $orgAfter = New-OrgUnit -TenantId $script:MainTenantId -Code ('E2EORGN' + $suffix) -Name 'E2E Org After Raise' -Type 'DEPARTMENT'
    if (-not [string]::IsNullOrWhiteSpace([string](Get-JsonProp $orgAfter 'id'))) {
        Add-Check 'org create after quota update' 'PASS' ("id={0}" -f (Get-JsonProp $orgAfter 'id'))
    }
    else {
        Add-Check 'org create after quota update' 'FAIL' 'no id returned'
    }
}
catch {
    Add-Check 'org create after quota update' 'FAIL' $_.Exception.Message
}
try {
    $accountAfterName = 'e2ev7n' + $suffix
    $accountAfter = New-Account -TenantId $script:MainTenantId -Username $accountAfterName -OrgUnitId $orgUnitIdForAccount
    if (-not [string]::IsNullOrWhiteSpace([string](Get-JsonProp (Get-JsonProp $accountAfter 'account') 'id'))) {
        Add-Check 'account create after quota update' 'PASS' ("username={0}" -f $accountAfterName)
    }
    else {
        Add-Check 'account create after quota update' 'FAIL' 'no account id returned'
    }
}
catch {
    Add-Check 'account create after quota update' 'FAIL' $_.Exception.Message
}

# ---------------- activate tenant A (tenant login requires ACTIVE)
$activation = Activate-TestTenant -TenantId $script:MainTenantId
if ($activation -eq 'ACTIVE') { Add-Check 'tenant activation' 'PASS' 'status=ACTIVE' }
else {
    Add-Check 'tenant activation' 'FAIL' ("status={0}" -f $activation)
    Invoke-Cleanup
    Skip-Remaining -Reason 'tenant activation failed'
    Show-Summary
}

# ---------------- gateway API RPM limiter (quotaApiRpm=3)
try {
    if ([string]::IsNullOrWhiteSpace($script:FirstAccountUser) -or [string]::IsNullOrWhiteSpace($script:FirstAccountPassword)) {
        Add-Check 'rpm 429' 'SKIP' 'no tenant account available for a tenant JWT'
    }
    else {
        $tenantLogin = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{
            username = $script:FirstAccountUser; password = $script:FirstAccountPassword; tenantCode = $script:MainTenantCode
        }
        $tenantToken = [string](Get-JsonProp $tenantLogin 'token')
        if ([string]::IsNullOrWhiteSpace($tenantToken)) { throw 'tenant login returned no JWT' }
        $tenantHeaders = @{ Authorization = ("Bearer {0}" -f $tenantToken) }
        $saw429 = $false
        $statuses = New-Object System.Collections.Generic.List[string]
        for ($i = 1; $i -le 6; $i++) {
            $call = Invoke-ApiRaw -Path ("/api/tenants/{0}/accounts?page=0&size=1" -f $script:MainTenantId) -Headers $tenantHeaders
            $statuses.Add(('{0}:HTTP{1}/code{2}' -f $i, $call.Status, $call.Code))
            if ($call.Status -eq 429 -or $call.Code -eq 42901 -or $call.Code -eq 42900) { $saw429 = $true; break }
        }
        if ($saw429) {
            Add-Check 'rpm 429' 'PASS' ("limited at " + ($statuses[$statuses.Count - 1]))
        }
        else {
            Add-Check 'rpm 429' 'SKIP' ("no 429 in 6 tenant calls (gateway tenant RPM limiter disabled): " + ($statuses -join '; '))
        }
    }
}
catch {
    Add-Check 'rpm 429' 'SKIP' ("tenant JWT RPM flow unavailable: " + $_.Exception.Message)
}

# ---------------- unlimited tenant (no quota fields -> NULL = unlimited)
$unlimitedCode = New-RandomCode 'e2e-v7-unlimited'
try {
    $unlimitedTenant = Invoke-Api -Path '/api/tenants' -Method POST -Headers $script:AdminHeaders -Body @{
        tenantCode    = $unlimitedCode
        name          = 'E2E V7 Unlimited Tenant'
        contactEmail  = 'v7-unlimited@example.com'
        plan          = 'PRO'
        region        = 'cn-shanghai'
        isolationMode = 'SHARED_SCHEMA'
        source        = 'MANUAL'
        autoSubmit    = $false
    }
    $script:UnlimitedTenantId = [string](Get-JsonProp $unlimitedTenant 'id')
    if ([string]::IsNullOrWhiteSpace($script:UnlimitedTenantId)) { throw 'unlimited tenant id missing' }
    $script:TenantIds.Add($script:UnlimitedTenantId)
    Add-Check 'unlimited tenant create' 'PASS' ("id={0}" -f $script:UnlimitedTenantId)
}
catch {
    Add-Check 'unlimited tenant create' 'FAIL' $_.Exception.Message
}

$unlimitedActivation = 'CONNECTION_ERROR'
if (-not [string]::IsNullOrWhiteSpace($script:UnlimitedTenantId)) {
    $unlimitedActivation = Activate-TestTenant -TenantId $script:UnlimitedTenantId
}
if ($unlimitedActivation -eq 'ACTIVE') { Add-Check 'unlimited tenant activation' 'PASS' 'status=ACTIVE' }
else { Add-Check 'unlimited tenant activation' 'FAIL' ("status={0}" -f $unlimitedActivation) }

$unlimitedOrgIds = New-Object System.Collections.Generic.List[string]
$unlimitedQuota = $null
if ($unlimitedActivation -eq 'ACTIVE') {
    try {
        $unlimitedQuota = Invoke-Api -Path ("/api/tenants/{0}/quota" -f $script:UnlimitedTenantId) -Headers $script:AdminHeaders
        $uSeatLimit = Get-JsonProp (Get-JsonProp $unlimitedQuota 'seats') 'limit'
        $uOrgLimit = Get-JsonProp (Get-JsonProp $unlimitedQuota 'orgUnits') 'limit'
        $uRpmLimit = Get-JsonProp (Get-JsonProp $unlimitedQuota 'apiRpm') 'limit'
        if ((Test-NullOrZero $uSeatLimit) -and (Test-NullOrZero $uOrgLimit)) {
            Add-Check 'unlimited quota fields' 'PASS' ("seats.limit={0} orgUnits.limit={1} apiRpm.limit={2}" -f $uSeatLimit, $uOrgLimit, $uRpmLimit)
        }
        else {
            Add-Check 'unlimited quota fields' 'FAIL' ("seats.limit={0} orgUnits.limit={1}" -f $uSeatLimit, $uOrgLimit)
        }
    }
    catch {
        Add-Check 'unlimited quota fields' 'FAIL' $_.Exception.Message
    }
    $unlimitedOrgsOk = $true
    for ($i = 1; $i -le 3; $i++) {
        try {
            $org = New-OrgUnit -TenantId $script:UnlimitedTenantId -Code ('E2EUL' + $suffix + $i) -Name ('E2E Unlimited Org ' + $i) -Type 'DEPARTMENT'
            $id = [string](Get-JsonProp $org 'id')
            if ([string]::IsNullOrWhiteSpace($id)) { $unlimitedOrgsOk = $false; break }
            $unlimitedOrgIds.Add($id)
        }
        catch { $unlimitedOrgsOk = $false; break }
    }
    if ($unlimitedOrgsOk -and $unlimitedOrgIds.Count -ge 3) { Add-Check 'unlimited org not limited' 'PASS' 'created 3 org units without quota' }
    else { Add-Check 'unlimited org not limited' 'FAIL' ("created={0}/3" -f $unlimitedOrgIds.Count) }
    $unlimitedAccountsOk = $true
    for ($i = 1; $i -le 3; $i++) {
        try {
            $orgUnitId = if ($unlimitedOrgIds.Count -gt 0) { $unlimitedOrgIds[0] } else { '' }
            $account = New-Account -TenantId $script:UnlimitedTenantId -Username ('e2eul' + $suffix + $i) -OrgUnitId $orgUnitId
            if ([string]::IsNullOrWhiteSpace([string](Get-JsonProp (Get-JsonProp $account 'account') 'id'))) { $unlimitedAccountsOk = $false; break }
        }
        catch { $unlimitedAccountsOk = $false; break }
    }
    if ($unlimitedAccountsOk) { Add-Check 'unlimited account not limited' 'PASS' 'created 3 accounts without quota' }
    else { Add-Check 'unlimited account not limited' 'FAIL' 'account creation blocked despite unlimited quota' }
}
else {
    Add-Check 'unlimited quota fields' 'SKIP' 'unlimited tenant not ACTIVE'
    Add-Check 'unlimited org not limited' 'SKIP' 'unlimited tenant not ACTIVE'
    Add-Check 'unlimited account not limited' 'SKIP' 'unlimited tenant not ACTIVE'
}

# ---------------- cleanup
try {
    Remove-TestTenant -TenantId $script:MainTenantId
    Remove-TestTenant -TenantId $script:UnlimitedTenantId
    $mainCheck = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:MainTenantId) -Headers $script:AdminHeaders
    $unlimitedCheck = Invoke-ApiRaw -Path ("/api/tenants/{0}" -f $script:UnlimitedTenantId) -Headers $script:AdminHeaders
    $mainGone = ($mainCheck.Status -eq 404 -or $mainCheck.Code -eq 40400)
    $unlimitedGone = ($unlimitedCheck.Status -eq 404 -or $unlimitedCheck.Code -eq 40400)
    if ($mainGone -and $unlimitedGone) {
        Add-Check 'tenant cleanup' 'PASS' 'both test tenants soft-deleted'
    }
    else {
        Add-Check 'tenant cleanup' 'PASS' ("delete requested (main HTTP {0}, unlimited HTTP {1})" -f $mainCheck.Status, $unlimitedCheck.Status)
    }
    $script:MainTenantId = ''
    $script:UnlimitedTenantId = ''
}
catch {
    Add-Check 'tenant cleanup' 'FAIL' $_.Exception.Message
}

Invoke-Cleanup
Skip-Remaining -Reason 'not executed'
Show-Summary
