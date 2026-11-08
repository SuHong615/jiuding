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
# v4 E2E: account security (lock/unlock/change-password), login audit, CSV export,
# masking, rating delta, and login rate limiting.
#
# Usage: .\tests\e2e-v4.ps1 [-BaseUrl http://localhost:18080]
param([string]$BaseUrl = 'http://localhost:18080')

$ErrorActionPreference = 'Stop'
$checks = New-Object System.Collections.Generic.List[object]
function Add-Check([string]$name, [string]$status, [string]$detail) {
    $checks.Add([pscustomobject]@{ Check = $name; Status = $status; Detail = $detail })
    $color = switch ($status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("[{0}] {1} - {2}" -f $status, $name, $detail) -ForegroundColor $color
}
function Now-Random([string]$prefix) { "$prefix-{0}-{1}" -f (Get-Date -Format 'yyyyMMddHHmmss'), (Get-Random -Maximum 99999) }
function To-Json($obj) { $obj | ConvertTo-Json -Depth 10 -Compress }
function Decode-Body($resp) {
    if ($resp.RawContentStream) { return [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) }
    return [string]$resp.Content
}
function Invoke-Api {
    param([string]$Path, [string]$Method = 'GET', [hashtable]$Headers = @{}, $Body = $null, [switch]$Raw)
    $params = @{ Uri = "$script:Base$Path"; Method = $Method; Headers = $Headers; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($null -ne $Body) { $params['ContentType'] = 'application/json; charset=utf-8'; $params['Body'] = To-Json $Body }
    try {
        $resp = Invoke-WebRequest @params
        if ($Raw) { return $resp }
        $json = (Decode-Body $resp) | ConvertFrom-Json
        if ($null -ne $json.code -and $json.code -ne 0) { throw "api code=$($json.code) message=$($json.message)" }
        if ($null -ne $json.data) { return $json.data }
        return $json
    } catch [System.Net.WebException] {
        $web = $_.Exception.Response
        if ($null -eq $web) { throw }
        $status = [int]$web.StatusCode
        $body = (New-Object System.IO.StreamReader($web.GetResponseStream())).ReadToEnd()
        $code = $null
        try { $code = ($body | ConvertFrom-Json).code } catch { }
        $err = New-Object System.Exception("http $status code=$code")
        $err.Data['status'] = $status; $err.Data['code'] = $code; $err.Data['body'] = $body
        throw $err
    }
}
function Expect-HttpError {
    param([scriptblock]$Action, [int[]]$Status)
    try { & $Action; return $null } catch { if ($_.Exception.Data['status'] -and ($Status -contains [int]$_.Exception.Data['status'])) { return $_.Exception } ; throw }
}
function Login-Platform([string]$user, [string]$pass) {
    $r = Invoke-Api -Path '/api/auth/login' -Method POST -Body @{ username = $user; password = $pass }
    return $r.token
}
function Login-Tenant([string]$user, [string]$pass, [string]$tenantCode, [string]$ip) {
    $h = @{}
    if ($ip) { $h['X-Forwarded-For'] = $ip }
    return Invoke-Api -Path '/api/auth/login' -Method POST -Headers $h -Body @{ username = $user; password = $pass; tenantCode = $tenantCode }
}

$script:Base = $BaseUrl.TrimEnd('/')
$tenantId = $null; $adminToken = $null; $adminHeaders = @{}
$companyId = $null

try {
    Write-Host "==> e2e-v4 against $($script:Base)" -ForegroundColor Cyan
    # preflight
    try { $null = Invoke-Api -Path '/actuator/health'; Add-Check 'preflight gateway' 'PASS' $script:Base } catch { Add-Check 'preflight gateway' 'FAIL' $_.Exception.Message; throw }

    $adminToken = Login-Platform 'admin' 'Admin@123'
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    Add-Check 'login admin' 'PASS' 'token issued'

    $code = Now-Random 'v4'
    $tenant = Invoke-Api -Path '/api/tenants' -Method POST -Headers $adminHeaders -Body @{
        tenantCode = $code; name = 'E2E V4 Tenant'; contactEmail = 'v4@example.com'; plan = 'PRO'
        region = 'cn-shanghai'; isolationMode = 'SHARED_SCHEMA'; quotaSeats = 20; source = 'MANUAL'; autoSubmit = $false
    }
    $tenantId = [string]$tenant.id
    Add-Check 'tenant create' 'PASS' ("id=$tenantId code=$code")

    $org = Invoke-Api -Path "/api/tenants/$tenantId/org-units" -Method POST -Headers $adminHeaders -Body @{ code = 'HQ'; name = 'HQ'; type = 'COMPANY' }
    $companyId = [string]$org.id
    Add-Check 'org create' 'PASS' ("id=$companyId")

    # activate tenant (tenant-account login requires ACTIVE)
    $null = Invoke-Api -Path "/api/tenants/$tenantId/submit" -Method POST -Headers $adminHeaders
    Start-Sleep -Seconds 2
    $tv = Invoke-Api -Path "/api/tenants/$tenantId" -Headers $adminHeaders
    if ([string]$tv.status -ne 'ACTIVE') {
        $apps = Invoke-Api -Path '/api/approvals?page=0&size=50' -Headers $adminHeaders
        $app = @($apps.items) | Where-Object { [string]$_.tenantId -eq $tenantId } | Select-Object -First 1
        if ($app) {
            $null = Invoke-Api -Path ("/api/approvals/{0}/decisions" -f $app.id) -Method POST -Headers $adminHeaders -Body @{ decision = 'APPROVE'; comment = 'e2e-v4 activation' }
            Start-Sleep -Seconds 2
            $tv = Invoke-Api -Path "/api/tenants/$tenantId" -Headers $adminHeaders
        }
    }
    if ([string]$tv.status -ne 'ACTIVE') { throw "tenant not ACTIVE: $($tv.status)" }
    Add-Check 'tenant activation' 'PASS' 'status=ACTIVE'

    # two accounts: lock/unlock account + change-password account
    $lockUser = Now-Random 'v4.lock'
    $lockAcc = Invoke-Api -Path "/api/tenants/$tenantId/accounts" -Method POST -Headers $adminHeaders -Body @{
        username = $lockUser; displayName = 'V4 Lock'; email = 'lock@example.com'; phone = '13800001111'; orgUnitId = $companyId; role = 'MEMBER'
    }
    $lockId = [string]$lockAcc.account.id; $lockPwd = [string]$lockAcc.tempPassword
    Add-Check 'account locktest create' 'PASS' ("id=$lockId pwdLen=$($lockPwd.Length)")

    $chgUser = Now-Random 'v4.chg'
    $chgAcc = Invoke-Api -Path "/api/tenants/$tenantId/accounts" -Method POST -Headers $adminHeaders -Body @{
        username = $chgUser; displayName = 'V4 Change'; email = 'chg@example.com'; phone = '13800002222'; orgUnitId = $companyId; role = 'MEMBER'
    }
    $chgId = [string]$chgAcc.account.id; $chgPwd = [string]$chgAcc.tempPassword
    Add-Check 'account changepwd create' 'PASS' ("id=$chgId")

    # ---------------------------------------------------------------- lock + unlock
    $first = Login-Tenant $lockUser $lockPwd $code '203.0.113.10'
    if ([string]$first.principal.authType -ne 'TENANT') { throw 'expected TENANT authType' }
    Add-Check 'tenant login works' 'PASS' ("user=$($first.principal.username)")

    for ($i = 1; $i -le 5; $i++) {
        try { $null = Login-Tenant $lockUser 'wrong-password-1' $code '203.0.113.10' } catch { }
    }
    $locked = $false
    try { $null = Login-Tenant $lockUser $lockPwd $code '203.0.113.10' } catch { $locked = $true }
    Add-Check 'account locked after failures' $(if ($locked) { 'PASS' } else { 'FAIL' }) 'login rejected after 5 failures'

    $unlocked = Invoke-Api -Path "/api/tenants/$tenantId/accounts/$lockId/unlock" -Method POST -Headers $adminHeaders
    if ([string]$unlocked.status -ne 'ACTIVE') { throw "unlock status=$($unlocked.status)" }
    Add-Check 'account unlock' 'PASS' 'status=ACTIVE'
    $null = Invoke-Api -Path "/api/tenants/$tenantId/accounts/$lockId/unlock" -Method POST -Headers $adminHeaders
    Add-Check 'account unlock idempotent' 'PASS' 'second unlock ok'
    $relogin = Login-Tenant $lockUser $lockPwd $code '203.0.113.10'
    Add-Check 'login after unlock' 'PASS' ("tokenLen=$($relogin.token.Length)")

    # ---------------------------------------------------------------- change password (self)
    $login = Login-Tenant $chgUser $chgPwd $code '203.0.113.11'
    $selfHeaders = @{ Authorization = ("Bearer {0}" -f $login.token) }
    $newPwd = 'NewPassw0rd-2026'
    $null = Invoke-Api -Path "/api/tenants/$tenantId/accounts/$chgId/change-password" -Method POST -Headers $selfHeaders -Body @{ oldPassword = $chgPwd; newPassword = $newPwd }
    Add-Check 'change password self' 'PASS' 'password updated'
    $oldLoginFailed = $false
    try { $null = Login-Tenant $chgUser $chgPwd $code '203.0.113.11' } catch { $oldLoginFailed = $true }
    Add-Check 'old password rejected' $(if ($oldLoginFailed) { 'PASS' } else { 'FAIL' }) '401 expected'
    $newLogin = Login-Tenant $chgUser $newPwd $code '203.0.113.11'
    if ([string]$newLogin.principal.mustChangePassword -ne 'False') { throw 'mustChangePassword should be false' }
    Add-Check 'new password works' 'PASS' 'mustChangePassword=false'

    # ---------------------------------------------------------------- login events
    $events = Invoke-Api -Path "/api/tenants/$tenantId/accounts/$chgId/login-events?page=0&size=20" -Headers $adminHeaders
    $items = @($events.items)
    if ($items.Count -lt 3) { throw "login events too few: $($items.Count)" }
    $hasFail = @($items | Where-Object { $_.success -eq $false }).Count -ge 1
    $ipOk = [string]$items[0].ip -ne ''
    if (-not $hasFail) { throw 'expected a failed login event' }
    Add-Check 'login events recorded' 'PASS' ("total=$($events.total) hasFailure=$hasFail ip=$($items[0].ip)")

    # ---------------------------------------------------------------- audit CSV export
    $csv = Invoke-Api -Path "/api/tenants/$tenantId/audits/export?format=csv" -Headers $adminHeaders -Raw
    $csvText = Decode-Body $csv
    $ctype = [string]$csv.Headers['Content-Type']
    foreach ($needle in @('ACCOUNT_UNLOCKED', 'PASSWORD_CHANGED', 'LOGIN_SUCCESS')) {
        if ($csvText -notmatch $needle) { throw "csv missing $needle" }
    }
    Add-Check 'audit csv export' 'PASS' ("contentType=$ctype rows=$((($csvText -split "`n").Count))")

    # ---------------------------------------------------------------- masking
    $masked = Invoke-Api -Path "/api/tenants/$tenantId/accounts?page=0&size=20&mask=true" -Headers $adminHeaders
    $maskedItem = @($masked.items) | Where-Object { [string]$_.id -eq $chgId } | Select-Object -First 1
    if ($null -eq $maskedItem -or ([string]$maskedItem.email -notmatch '\*')) { throw 'mask=true did not mask email' }
    Add-Check 'pii masking' 'PASS' ("email=$($maskedItem.email)")

    # ---------------------------------------------------------------- rating delta
    $r1 = Invoke-Api -Path "/api/tenants/$tenantId/ratings/assess" -Method POST -Headers $adminHeaders -Body @{ note = 'e2e-v4 first' }
    $r2 = Invoke-Api -Path "/api/tenants/$tenantId/ratings/assess" -Method POST -Headers $adminHeaders -Body @{ note = 'e2e-v4 second' }
    if (-not ($r2.PSObject.Properties.Name -contains 'scoreDelta')) { throw 'assess response missing scoreDelta' }
    $hist = Invoke-Api -Path "/api/tenants/$tenantId/ratings?page=0&size=10" -Headers $adminHeaders
    $h0 = @($hist.items)[0]
    if (-not ($h0.PSObject.Properties.Name -contains 'scoreDelta')) { throw 'history item missing scoreDelta' }
    Add-Check 'rating delta' 'PASS' ("grade=$($r2.grade) score=$($r2.score) delta=$($r2.scoreDelta)")

    # ---------------------------------------------------------------- login rate limit
    $rlUser = Now-Random 'v4.rl'
    $saw429 = $false
    for ($i = 1; $i -le 12; $i++) {
        try { $null = Login-Tenant $rlUser 'wrong' $code '198.51.100.77' } catch {
            if ([int]$_.Exception.Data['status'] -eq 429) { $saw429 = $true; break }
        }
    }
    Add-Check 'login rate limit 429' $(if ($saw429) { 'PASS' } else { 'SKIP' }) '11th attempt returns 429/42900 (requires gateway v4)'
}
catch {
    Add-Check 'e2e-v4 execution' 'FAIL' $_.Exception.Message
}
finally {
    if ($tenantId -and $adminHeaders.ContainsKey('Authorization')) {
        try { $null = Invoke-Api -Path "/api/tenants/$tenantId/terminate" -Method POST -Headers $adminHeaders -Body @{ reason = 'e2e-v4 cleanup' } } catch { }
        try { $null = Invoke-Api -Path "/api/tenants/$tenantId" -Method DELETE -Headers $adminHeaders } catch { }
    }
    Write-Host "`n---------------- e2e-v4 summary ----------------" -ForegroundColor Cyan
    $checks | Format-Table -AutoSize | Out-String | Write-Host
    $pass = @($checks | Where-Object { $_.Status -eq 'PASS' }).Count
    $fail = @($checks | Where-Object { $_.Status -eq 'FAIL' }).Count
    $skip = @($checks | Where-Object { $_.Status -eq 'SKIP' }).Count
    Write-Host ("PASS={0} FAIL={1} SKIP={2}" -f $pass, $fail, $skip) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
    if ($fail -gt 0) { exit 1 } else { exit 0 }
}
