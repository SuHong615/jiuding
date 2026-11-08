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
    Verification entry point: static deploy checks + E2E + MCP smoke, PASS/FAIL/SKIP summary.

.DESCRIPTION
    Local process mode uses gateway port 18080 (8080 is occupied on this box):
        pwsh ./scripts/run-local.ps1
        pwsh ./scripts/verify.ps1

    Behaviour:
      * static checks always run (compose YAML parse, Dockerfiles, scripts, CLI jar);
      * if the gateway is not reachable, the E2E/MCP steps are reported as SKIP
        (not FAIL) so the script is safe during parallel development;
      * exit code is 1 only when something actually FAILED.

.EXAMPLE
    pwsh ./scripts/verify.ps1                    # full verification
    pwsh ./scripts/verify.ps1 -StaticOnly        # no network, deploy/scripts sanity only
    pwsh ./scripts/verify.ps1 -BaseUrl http://localhost:8080
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$McpApiKey = 'dev-mcp-key',
    [string]$TenantServiceUrl = '',
    [switch]$StaticOnly,
    [switch]$SkipE2E,
    [switch]$SkipMCP,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$root = $global:TenancyRoot
$results = New-Object System.Collections.Generic.List[object]

function Add-Result {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$Detail = ''
    )
    $results.Add([pscustomobject]@{ Check = $Name; Status = $Status; Detail = $Detail })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } 'SKIP' { 'Yellow' } default { 'Gray' } }
    Write-Host ("[{0}] {1}{2}" -f $Status, $Name, $(if ($Detail) { " - $Detail" } else { '' })) -ForegroundColor $color
}

function Test-GatewayUp {
    param([string]$Url)
    try {
        $response = Invoke-WebRequest -Uri "$Url/actuator/health" -UseBasicParsing -TimeoutSec 5
        return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300)
    }
    catch {
        $response = $_.Exception.Response
        if ($response) { return ([int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 300) }
        return $false
    }
}

function Get-AdminProbeToken {
    param([string]$Url)
    try {
        $body = @{ username = 'admin'; password = 'Admin@123' } | ConvertTo-Json -Compress
        $response = Invoke-WebRequest -Uri "$Url/api/auth/login" -Method Post -UseBasicParsing `
            -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 10
        $payload = $response.Content | ConvertFrom-Json
        return [string]$payload.data.token
    }
    catch {
        return $null
    }
}

function Test-V2ApiAvailable {
    param([string]$Url, [string]$Token)
    $missing = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrWhiteSpace($Token)) {
        return [pscustomobject]@{ Available = $false; Missing = @('POST /api/auth/login'); Detail = 'admin login failed; cannot probe v2 API' }
    }
    $headers = @{ Authorization = ("Bearer {0}" -f $Token) }
    $probeId = [guid]::NewGuid().ToString()
    try {
        $list = Invoke-WebRequest -Uri "$Url/api/tenants?page=0&size=1" -Headers $headers -UseBasicParsing -TimeoutSec 10
        $payload = $list.Content | ConvertFrom-Json
        $items = @($payload.data.items)
        if ($items.Count -gt 0) {
            $probeId = [string]$items[0].id
            if (-not ($items[0].PSObject.Properties.Name -contains 'ratingGrade')) {
                $missing.Add('GET /api/tenants -> TenantView.ratingGrade')
            }
        }
    }
    catch {
        $missing.Add('GET /api/tenants')
    }
    $routeProbes = @(
        @{ Path = "/api/tenants/$probeId/org-units"; Label = 'GET /api/tenants/{tenantId}/org-units' },
        @{ Path = "/api/tenants/$probeId/accounts?page=0&size=1"; Label = 'GET /api/tenants/{tenantId}/accounts' },
        @{ Path = "/api/tenants/$probeId/ratings/current"; Label = 'GET /api/tenants/{tenantId}/ratings/current' }
    )
    foreach ($probe in $routeProbes) {
        $status = 0
        try {
            $null = Invoke-WebRequest -Uri ("$Url" + $probe.Path) -Headers $headers -UseBasicParsing -TimeoutSec 10
            $status = 200
        }
        catch {
            $response = $_.Exception.Response
            if ($response) { $status = [int]$response.StatusCode }
        }
        if ($status -eq 0 -or $status -ge 500) { $missing.Add($probe.Label) }
    }
    $unique = @($missing | Select-Object -Unique)
    if ($unique.Count -eq 0) {
        return [pscustomobject]@{ Available = $true; Missing = @(); Detail = 'ratingGrade + org/account/rating routes present' }
    }
    return [pscustomobject]@{
        Available = $false
        Missing   = $unique
        Detail    = ('v2 API not deployed; missing: ' + ($unique -join '; '))
    }
}

function Test-McpV2Available {
    param([string]$Url, [string]$ApiKey)
    $requiredTools = @(
        'org.tree', 'org.create', 'account.list', 'account.create',
        'account.reset_password', 'rating.assess', 'rating.list', 'tenant.delete'
    )
    try {
        $body = '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
        $headers = @{ 'X-API-Key' = $ApiKey; 'Accept' = 'application/json' }
        $response = Invoke-WebRequest -Uri "$Url/mcp" -Method Post -Headers $headers -UseBasicParsing `
            -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 15
        $document = $response.Content | ConvertFrom-Json
        $tools = @($document.result.tools)
        $names = @($tools | ForEach-Object { $_.name })
        $missing = @($requiredTools | Where-Object { $names -notcontains $_ })
        if ($tools.Count -le 14 -or $missing.Count -eq $requiredTools.Count) {
            return [pscustomobject]@{
                Available = $false
                Detail    = ("v2 MCP tools not deployed; tools/list={0}, missing: {1}" -f $tools.Count, ($missing -join ', '))
            }
        }
        if ($missing.Count -gt 0) {
            return [pscustomobject]@{
                Available = $true
                Detail    = ("partial v2 MCP tools; missing: {0}" -f ($missing -join ', '))
            }
        }
        return [pscustomobject]@{ Available = $true; Detail = ("tools/list={0}" -f $tools.Count) }
    }
    catch {
        return [pscustomobject]@{ Available = $false; Detail = ("MCP probe failed: {0}" -f $_.Exception.Message) }
    }
}

function Test-V3ApiAvailable {
    param([string]$Url)
    $loginBody = @{ username = 'admin'; password = 'Admin@123' } | ConvertTo-Json -Compress
    $payload = $null
    try {
        $login = Invoke-WebRequest -Uri "$Url/api/auth/login" -Method Post -UseBasicParsing `
            -ContentType 'application/json; charset=utf-8' -Body $loginBody -TimeoutSec 10
        $payload = $login.Content | ConvertFrom-Json
    }
    catch {
        return [pscustomobject]@{ Available = $false; Detail = ('admin login failed: ' + $_.Exception.Message) }
    }
    $token = [string]$payload.data.token
    $principal = $payload.data.principal
    if ([string]::IsNullOrWhiteSpace($token)) {
        return [pscustomobject]@{ Available = $false; Detail = 'admin login returned no token' }
    }
    # v3 marker: the login principal exposes the new authType field (docs/13 section 2.3).
    if ($null -eq $principal -or -not ($principal.PSObject.Properties.Name -contains 'authType')) {
        return [pscustomobject]@{
            Available = $false
            Detail    = 'gateway v3 not deployed: POST /api/auth/login principal lacks authType (docs/13 section 2.3)'
        }
    }
    # Tenant login probe: unknown tenant/user must yield the unified 401, never 404/500/503.
    $probeBody = @{ username = 'no-such-user'; password = 'Wrong@12345'; tenantCode = 'no-such-tenant' } |
        ConvertTo-Json -Compress
    $probeStatus = 0
    try {
        $probe = Invoke-WebRequest -Uri "$Url/api/auth/login" -Method Post -UseBasicParsing `
            -ContentType 'application/json; charset=utf-8' -Body $probeBody -TimeoutSec 10
        $probeStatus = [int]$probe.StatusCode
    }
    catch {
        $response = $_.Exception.Response
        if ($response) { $probeStatus = [int]$response.StatusCode }
    }
    if ($probeStatus -ne 401) {
        return [pscustomobject]@{
            Available = $false
            Detail    = ("tenant login probe expected 401, got HTTP {0} (tenant-service /internal/auth/authenticate not ready?)" -f $probeStatus)
        }
    }
    try {
        $headers = @{ Authorization = ("Bearer {0}" -f $token) }
        $list = Invoke-WebRequest -Uri "$Url/api/tenants?page=0&size=1" -Headers $headers -UseBasicParsing -TimeoutSec 10
        $listPayload = $list.Content | ConvertFrom-Json
        $items = @($listPayload.data.items)
        if ($items.Count -gt 0 -and -not ($items[0].PSObject.Properties.Name -contains 'ratingGrade')) {
            return [pscustomobject]@{
                Available = $false
                Detail    = 'v2 fields missing: GET /api/tenants item lacks ratingGrade (tenant-service v2 not deployed)'
            }
        }
    }
    catch {
        return [pscustomobject]@{ Available = $false; Detail = ('GET /api/tenants failed: ' + $_.Exception.Message) }
    }
    return [pscustomobject]@{
        Available = $true
        Detail    = 'authType marker + tenant login 401 probe + ratingGrade present'
    }
}

function Test-V5ApiAvailable {
    param([string]$Url, [string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) {
        return [pscustomobject]@{ Available = $false; Detail = 'admin login failed; cannot probe v5 API' }
    }
    $headers = @{ Authorization = ("Bearer {0}" -f $Token) }
    $markers = @(
        @{ Path = '/api/me/permissions'; Label = 'GET /api/me/permissions' },
        @{ Path = '/api/mcp/config'; Label = 'GET /api/mcp/config' },
        @{ Path = '/api/agents/catalog'; Label = 'GET /api/agents/catalog' },
        @{ Path = '/api/platform/roles'; Label = 'GET /api/platform/roles' }
    )
    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($marker in $markers) {
        $status = 0
        try {
            $response = Invoke-WebRequest -Uri ("$Url" + $marker.Path) -Headers $headers -UseBasicParsing -TimeoutSec 10
            $status = [int]$response.StatusCode
            $envelope = $null
            try { $envelope = $response.Content | ConvertFrom-Json } catch { $envelope = $null }
            if ($null -ne $envelope -and ($envelope.PSObject.Properties.Name -contains 'code') -and [int]$envelope.code -ne 0) {
                $status = 0
            }
        }
        catch {
            $response = $_.Exception.Response
            if ($response) { $status = [int]$response.StatusCode }
        }
        if ($status -lt 200 -or $status -ge 300) { $missing.Add($marker.Label) }
    }
    if ($missing.Count -eq 0) {
        return [pscustomobject]@{ Available = $true; Detail = 'me/permissions + mcp/config + agents/catalog + platform/roles present' }
    }
    return [pscustomobject]@{
        Available = $false
        Detail    = ('v5 API not deployed; missing: ' + (($missing | Select-Object -Unique) -join '; '))
    }
}

function Test-SkillVersionsAvailable {
    param([string]$Url, [string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) {
        return [pscustomobject]@{ Available = $false; Detail = 'admin login failed; cannot probe skill versions' }
    }
    $headers = @{ Authorization = ("Bearer {0}" -f $Token) }
    try {
        $listResponse = Invoke-WebRequest -Uri "$Url/api/skills" -Headers $headers -UseBasicParsing -TimeoutSec 10
        $listPayload = $listResponse.Content | ConvertFrom-Json
        $skills = @($listPayload.data)
        if ($skills.Count -eq 0) {
            return [pscustomobject]@{ Available = $false; Detail = 'skill center has no skill packages to probe GET /api/skills/{id}/versions' }
        }
        $skillId = [string]$skills[0].id
        if ([string]::IsNullOrWhiteSpace($skillId)) {
            return [pscustomobject]@{ Available = $false; Detail = 'skill list item has no id' }
        }
        $versionsResponse = Invoke-WebRequest -Uri ("$Url/api/skills/" + [uri]::EscapeDataString($skillId) + "/versions") -Headers $headers -UseBasicParsing -TimeoutSec 10
        $versionsPayload = $versionsResponse.Content | ConvertFrom-Json
        if ($null -eq $versionsPayload -or ($versionsPayload.PSObject.Properties.Name -contains 'code' -and [int]$versionsPayload.code -ne 0)) {
            return [pscustomobject]@{ Available = $false; Detail = 'GET /api/skills/{id}/versions returned a non-zero envelope' }
        }
        $versions = @($versionsPayload.data)
        return [pscustomobject]@{ Available = $true; Detail = ("GET /api/skills/{0}/versions -> {1} version(s)" -f $skillId, $versions.Count) }
    }
    catch {
        return [pscustomobject]@{ Available = $false; Detail = ('skill versions API not deployed: ' + $_.Exception.Message) }
    }
}

function Test-V6ApiAvailable {
    param([string]$Url, [string]$Token)
    $oidcStatus = 0
    $oidcDetail = ''
    try {
        $response = Invoke-WebRequest -Uri "$Url/api/auth/oidc/status" -UseBasicParsing -TimeoutSec 10
        $oidcStatus = [int]$response.StatusCode
        $payload = $response.Content | ConvertFrom-Json
        if ($null -eq $payload -or ($payload.PSObject.Properties.Name -contains 'code' -and [int]$payload.code -ne 0)) {
            $oidcStatus = 0
        }
        else {
            $oidcDetail = ('enabled=' + [string]$payload.data.enabled)
        }
    }
    catch {
        $response = $_.Exception.Response
        if ($response) { $oidcStatus = [int]$response.StatusCode }
        $oidcDetail = ('probe failed: ' + $_.Exception.Message)
    }
    $mfaStatus = 0
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        try {
            $headers = @{ Authorization = ("Bearer {0}" -f $Token) }
            $mfaResponse = Invoke-WebRequest -Uri "$Url/api/me/mfa" -Headers $headers -UseBasicParsing -TimeoutSec 10
            $mfaStatus = [int]$mfaResponse.StatusCode
        }
        catch {
            $mfaResponse = $_.Exception.Response
            if ($mfaResponse) { $mfaStatus = [int]$mfaResponse.StatusCode }
        }
    }
    if ($oidcStatus -ge 200 -and $oidcStatus -lt 300) {
        return [pscustomobject]@{
            Available = $true
            Detail    = ('oidc ' + $oidcDetail + '; mfa probe HTTP ' + $mfaStatus)
        }
    }
    return [pscustomobject]@{
        Available = $false
        Detail    = ('v6 API not deployed; GET /api/auth/oidc/status -> HTTP ' + $oidcStatus + ' (' + $oidcDetail + ')')
    }
}

function Test-ScimAvailable {
    param([string]$Url, [string]$Token)
    $scimStatus = 0
    try {
        $scimResponse = Invoke-WebRequest -Uri "$Url/scim/v2/ServiceProviderConfig" -UseBasicParsing -TimeoutSec 10
        $scimStatus = [int]$scimResponse.StatusCode
    }
    catch {
        $response = $_.Exception.Response
        if ($response) { $scimStatus = [int]$response.StatusCode }
    }
    $tokenStatus = 0
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        try {
            $headers = @{ Authorization = ("Bearer {0}" -f $Token) }
            $tokenResponse = Invoke-WebRequest -Uri "$Url/api/platform/scim-tokens" -Headers $headers -UseBasicParsing -TimeoutSec 10
            $tokenStatus = [int]$tokenResponse.StatusCode
        }
        catch {
            $response = $_.Exception.Response
            if ($response) { $tokenStatus = [int]$response.StatusCode }
        }
    }
    $scimRoutePresent = ($scimStatus -eq 401)
    $tokenApiPresent = ($tokenStatus -eq 200 -or $tokenStatus -eq 400)
    if ($tokenApiPresent) {
        return [pscustomobject]@{
            Available = $true
            Detail    = ('scim-tokens HTTP ' + $tokenStatus + '; scim config HTTP ' + $scimStatus)
        }
    }
    return [pscustomobject]@{
        Available = $false
        Detail    = ('SCIM API not deployed; scim-tokens HTTP ' + $tokenStatus + '; scim config HTTP ' + $scimStatus)
    }
}

function Test-QuotaAvailable {
    param([string]$Url, [string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) {
        return [pscustomobject]@{ Available = $false; Detail = 'admin login failed; cannot probe quota API' }
    }
    $headers = @{ Authorization = ("Bearer {0}" -f $Token) }
    $tenantId = ''
    try {
        $listResponse = Invoke-WebRequest -Uri "$Url/api/tenants?page=0&size=1" -Headers $headers -UseBasicParsing -TimeoutSec 10
        $listPayload = $listResponse.Content | ConvertFrom-Json
        $items = @($listPayload.data.items)
        if ($items.Count -gt 0) { $tenantId = [string]$items[0].id }
    }
    catch {
        return [pscustomobject]@{ Available = $false; Detail = ('GET /api/tenants failed: ' + $_.Exception.Message) }
    }
    if ([string]::IsNullOrWhiteSpace($tenantId)) {
        return [pscustomobject]@{ Available = $false; Detail = 'no tenant available to probe GET /api/tenants/{id}/quota' }
    }
    try {
        $quotaResponse = Invoke-WebRequest -Uri ("$Url/api/tenants/" + [uri]::EscapeDataString($tenantId) + "/quota") -Headers $headers -UseBasicParsing -TimeoutSec 10
        $quotaPayload = $quotaResponse.Content | ConvertFrom-Json
        $ok = ($null -ne $quotaPayload) -and -not (($quotaPayload.PSObject.Properties.Name -contains 'code') -and [int]$quotaPayload.code -ne 0)
        if ($ok) {
            return [pscustomobject]@{ Available = $true; Detail = ('GET /api/tenants/{id}/quota -> 200 (tenant ' + $tenantId + ')') }
        }
        return [pscustomobject]@{ Available = $false; Detail = 'quota API returned a non-zero envelope' }
    }
    catch {
        $status = 0
        $response = $_.Exception.Response
        if ($response) { $status = [int]$response.StatusCode }
        return [pscustomobject]@{ Available = $false; Detail = ('GET /api/tenants/{id}/quota -> HTTP ' + $status) }
    }
}

function Test-ExportAvailable {
    param([string]$Url, [string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) {
        return [pscustomobject]@{ Available = $false; Detail = 'admin login failed; cannot probe tenant export API' }
    }
    $headers = @{ Authorization = ("Bearer {0}" -f $Token) }
    $tenantId = ''
    try {
        $listResponse = Invoke-WebRequest -Uri "$Url/api/tenants?page=0&size=1" -Headers $headers -UseBasicParsing -TimeoutSec 10
        $listPayload = $listResponse.Content | ConvertFrom-Json
        $items = @($listPayload.data.items)
        if ($items.Count -gt 0) { $tenantId = [string]$items[0].id }
    }
    catch {
        return [pscustomobject]@{ Available = $false; Detail = ('GET /api/tenants failed: ' + $_.Exception.Message) }
    }
    if ([string]::IsNullOrWhiteSpace($tenantId)) {
        return [pscustomobject]@{ Available = $false; Detail = 'no tenant available to probe GET /api/tenants/{id}/export' }
    }
    try {
        $exportResponse = Invoke-WebRequest -Uri ("$Url/api/tenants/" + [uri]::EscapeDataString($tenantId) + "/export?mask=true") -Headers $headers -UseBasicParsing -TimeoutSec 15
        $payload = $exportResponse.Content | ConvertFrom-Json
        $hasTenant = ($null -ne $payload) -and ($payload.PSObject.Properties.Name -contains 'tenant') -and
            ($payload.PSObject.Properties.Name -contains 'accounts') -and ($payload.PSObject.Properties.Name -contains 'audits')
        if ([int]$exportResponse.StatusCode -eq 200 -and $hasTenant) {
            return [pscustomobject]@{ Available = $true; Detail = ('GET /api/tenants/{id}/export -> 200 (tenant ' + $tenantId + ')') }
        }
        return [pscustomobject]@{ Available = $false; Detail = 'export response has no tenant/accounts/audits structure' }
    }
    catch {
        $status = 0
        $response = $_.Exception.Response
        if ($response) { $status = [int]$response.StatusCode }
        return [pscustomobject]@{ Available = $false; Detail = ('GET /api/tenants/{id}/export -> HTTP ' + $status) }
    }
}

function Test-V10Available {
    param([string]$Url, [string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) {
        return [pscustomobject]@{ Available = $false; Detail = 'admin login failed; cannot probe v10 API' }
    }
    $headers = @{ Authorization = ("Bearer {0}" -f $Token) }
    $workflowStatus = 0
    $workflowOk = $false
    try {
        $workflowResponse = Invoke-WebRequest -Uri "$Url/api/workflows/tenant-lifecycle" -Headers $headers -UseBasicParsing -TimeoutSec 10
        $workflowStatus = [int]$workflowResponse.StatusCode
        $payload = $workflowResponse.Content | ConvertFrom-Json
        $workflowOk = ($null -ne $payload.data) -and ($payload.data.PSObject.Properties.Name -contains 'states')
    }
    catch {
        $response = $_.Exception.Response
        if ($response) { $workflowStatus = [int]$response.StatusCode }
    }
    $usersStatus = 0
    $usersOk = $false
    try {
        $usersResponse = Invoke-WebRequest -Uri "$Url/api/platform/users" -Headers $headers -UseBasicParsing -TimeoutSec 10
        $usersStatus = [int]$usersResponse.StatusCode
        $usersPayload = $usersResponse.Content | ConvertFrom-Json
        $usersOk = ($null -ne $usersPayload) -and -not (($usersPayload.PSObject.Properties.Name -contains 'code') -and [int]$usersPayload.code -ne 0)
    }
    catch {
        $response = $_.Exception.Response
        if ($response) { $usersStatus = [int]$response.StatusCode }
    }
    if ($workflowOk -or $usersOk) {
        return [pscustomobject]@{ Available = $true; Detail = ('workflow HTTP ' + $workflowStatus + '; platform users HTTP ' + $usersStatus) }
    }
    return [pscustomobject]@{ Available = $false; Detail = ('v10 API not deployed; workflow HTTP ' + $workflowStatus + '; platform users HTTP ' + $usersStatus) }
}

# ---------------------------------------------------------------- static checks
Write-Host '==> static checks' -ForegroundColor Cyan
$composeFile = Join-Path $root 'deploy\docker-compose.yml'
if (Test-Path $composeFile) {
    $python = Get-Command 'python' -ErrorAction SilentlyContinue
    if ($python) {
        $yamlCheck = & $python.Source -c "import yaml,sys; d=yaml.safe_load(open(sys.argv[1],encoding='utf-8')); svc=d.get('services',{}); need={'postgres','tenant-service','approval-service','agent-service','mcp-service','gateway','frontend'}; missing=need-set(svc); print(('OK services='+str(len(svc))+' gatewayUp='+str('gateway' in svc)) if not missing else ('MISSING '+','.join(sorted(missing))))" $composeFile 2>&1
        if ($LASTEXITCODE -eq 0 -and "$yamlCheck" -match '^OK') { Add-Result 'compose YAML' 'PASS' "$yamlCheck" }
        else { Add-Result 'compose YAML' 'FAIL' "$yamlCheck" }
    }
    else {
        Add-Result 'compose YAML' 'SKIP' 'python/PyYAML not available'
    }
}
else {
    Add-Result 'compose YAML' 'FAIL' "missing $composeFile"
}

$dockerfileDir = Join-Path $root 'deploy\dockerfiles'
$expectedDockerfiles = @(
    'tenant-service.Dockerfile', 'approval-service.Dockerfile', 'agent-service.Dockerfile',
    'mcp-service.Dockerfile', 'gateway.Dockerfile', 'frontend.Dockerfile'
)
$missingDockerfiles = @()
foreach ($dockerfile in $expectedDockerfiles) {
    if (-not (Test-Path (Join-Path $dockerfileDir $dockerfile))) { $missingDockerfiles += $dockerfile }
}
if ($missingDockerfiles.Count -eq 0) { Add-Result 'dockerfiles' 'PASS' "$($expectedDockerfiles.Count) files" }
else { Add-Result 'dockerfiles' 'FAIL' ("missing: " + ($missingDockerfiles -join ', ')) }

$requiredScripts = @('env.ps1', 'build.ps1', 'test.ps1', 'run-local.ps1', 'stop-local.ps1', 'verify.ps1')
$missingScripts = @()
foreach ($script in $requiredScripts) {
    if (-not (Test-Path (Join-Path $PSScriptRoot $script))) { $missingScripts += $script }
}
if ($missingScripts.Count -eq 0) { Add-Result 'scripts' 'PASS' "$($requiredScripts.Count) files" }
else { Add-Result 'scripts' 'FAIL' ("missing: " + ($missingScripts -join ', ')) }

$testsDir = Join-Path $root 'tests'
$requiredTests = @('e2e.ps1', 'mcp-smoke.ps1', 'e2e-v2.ps1', 'e2e-v3.ps1', 'e2e-v4.ps1', 'e2e-v5.ps1', 'e2e-v6.ps1', 'e2e-v6b.ps1', 'e2e-v7.ps1', 'e2e-v8.ps1', 'e2e-v10.ps1', 'e2e-skill.ps1', 'mcp-smoke-v2.ps1', 'README.md')
$missingTests = @()
foreach ($test in $requiredTests) {
    if (-not (Test-Path (Join-Path $testsDir $test))) { $missingTests += $test }
}
if ($missingTests.Count -eq 0) { Add-Result 'tests' 'PASS' "$($requiredTests.Count) files" }
else { Add-Result 'tests' 'FAIL' ("missing: " + ($missingTests -join ', ')) }

$cliJar = Get-TenancyCliJar
if ($cliJar) { Add-Result 'cli jar' 'PASS' $cliJar }
else { Add-Result 'cli jar' 'SKIP' 'not built yet (scripts/build.ps1 -Module tenancy-cli)' }

$postgresInit = Join-Path $root 'deploy\postgres\init\01-databases.sql'
if ((Test-Path $postgresInit) -and ((Get-Content $postgresInit -Raw) -match 'tenant_db') -and ((Get-Content $postgresInit -Raw) -match 'approval_db') -and ((Get-Content $postgresInit -Raw) -match 'agent_db')) {
    Add-Result 'postgres init' 'PASS' 'three databases declared'
}
else { Add-Result 'postgres init' 'FAIL' 'missing/empty 01-databases.sql' }

$envExample = Join-Path $root 'deploy\.env.example'
if (Test-Path $envExample) { Add-Result '.env.example' 'PASS' 'present' }
else { Add-Result '.env.example' 'FAIL' 'missing' }

if ($StaticOnly) {
    # fall through to the summary
}
else {
    # ------------------------------------------------------------ runtime checks
    Write-Host ''
    Write-Host ("==> runtime checks against {0}" -f $BaseUrl) -ForegroundColor Cyan
    $gatewayUp = Test-GatewayUp -Url $BaseUrl
    if (-not $gatewayUp) {
        Add-Result 'gateway health' 'SKIP' ("not reachable at {0}" -f $BaseUrl)
        Add-Result 'E2E chain' 'SKIP' 'gateway not ready - run scripts/run-local.ps1 first'
        Add-Result 'MCP smoke' 'SKIP' 'gateway not ready'
        Add-Result 'E2E v2 chain' 'SKIP' 'gateway not ready'
        Add-Result 'MCP v2 smoke' 'SKIP' 'gateway not ready'
        Add-Result 'E2E v3 chain' 'SKIP' 'gateway not ready'
        Add-Result 'E2E v5 chain' 'SKIP' 'gateway not ready'
        Add-Result 'E2E skill versions' 'SKIP' 'gateway not ready'
        Add-Result 'E2E v6 chain' 'SKIP' 'gateway not ready'
        Add-Result 'E2E v6b SCIM' 'SKIP' 'gateway not ready'
        Add-Result 'E2E v7 quota' 'SKIP' 'gateway not ready'
        Add-Result 'E2E v8 export' 'SKIP' 'gateway not ready'
        Add-Result 'E2E v10 chain' 'SKIP' 'gateway not ready'
    }
    else {
        Add-Result 'gateway health' 'PASS' $BaseUrl
        $e2eScript = Join-Path $testsDir 'e2e.ps1'
        if (-not $SkipE2E -and (Test-Path $e2eScript)) {
            Write-Host ''
            Write-Host '==> tests/e2e.ps1' -ForegroundColor Cyan
            & $e2eScript -BaseUrl $BaseUrl -TimeoutSeconds $TimeoutSeconds
            if ($LASTEXITCODE -eq 0) { Add-Result 'E2E chain' 'PASS' 'login->tenant->submit->approve->MCP->CLI' }
            else { Add-Result 'E2E chain' 'FAIL' "exit $LASTEXITCODE" }
        }
        elseif ($SkipE2E) { Add-Result 'E2E chain' 'SKIP' '-SkipE2E' }

        $mcpScript = Join-Path $testsDir 'mcp-smoke.ps1'
        if (-not $SkipMCP -and (Test-Path $mcpScript)) {
            Write-Host ''
            Write-Host '==> tests/mcp-smoke.ps1' -ForegroundColor Cyan
            & $mcpScript -BaseUrl $BaseUrl -ApiKey $McpApiKey
            if ($LASTEXITCODE -eq 0) { Add-Result 'MCP smoke' 'PASS' 'initialize/tools/list/tools/call' }
            else { Add-Result 'MCP smoke' 'FAIL' "exit $LASTEXITCODE" }
        }
        elseif ($SkipMCP) { Add-Result 'MCP smoke' 'SKIP' '-SkipMCP' }

        # ------------------------------------------------ v2 identity-domain checks
        # Conditional by design: an old deployment (no ratingGrade / no org routes /
        # 14 MCP tools) is reported as SKIP with the exact missing interface instead
        # of FAIL, so scripts/verify.ps1 keeps working during the v2 rollout.
        $adminProbeToken = Get-AdminProbeToken -Url $BaseUrl
        if (-not $SkipE2E) {
            $v2Api = Test-V2ApiAvailable -Url $BaseUrl -Token $adminProbeToken
            $e2eV2Script = Join-Path $testsDir 'e2e-v2.ps1'
            if ($v2Api.Available -and (Test-Path $e2eV2Script)) {
                Write-Host ''
                Write-Host '==> tests/e2e-v2.ps1' -ForegroundColor Cyan
                & $e2eV2Script -BaseUrl $BaseUrl -TimeoutSeconds $TimeoutSeconds
                if ($LASTEXITCODE -eq 0) { Add-Result 'E2E v2 chain' 'PASS' 'org/account/rating/delete' }
                else { Add-Result 'E2E v2 chain' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                Add-Result 'E2E v2 chain' 'SKIP' $v2Api.Detail
            }
        }
        else {
            Add-Result 'E2E v2 chain' 'SKIP' '-SkipE2E'
        }

        if (-not $SkipMCP) {
            $mcpV2 = Test-McpV2Available -Url $BaseUrl -ApiKey $McpApiKey
            $mcpV2Script = Join-Path $testsDir 'mcp-smoke-v2.ps1'
            if ($mcpV2.Available -and (Test-Path $mcpV2Script)) {
                Write-Host ''
                Write-Host '==> tests/mcp-smoke-v2.ps1' -ForegroundColor Cyan
                & $mcpV2Script -BaseUrl $BaseUrl -ApiKey $McpApiKey
                if ($LASTEXITCODE -eq 0) { Add-Result 'MCP v2 smoke' 'PASS' 'tools/list + org.tree/account.list/rating.list' }
                else { Add-Result 'MCP v2 smoke' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                Add-Result 'MCP v2 smoke' 'SKIP' $mcpV2.Detail
            }
        }
        else {
            Add-Result 'MCP v2 smoke' 'SKIP' '-SkipMCP'
        }

        # ------------------------------------------------ v3 auth/RBAC checks
        # Requires the v2 identity domain (org/accounts) as a prerequisite; when only
        # part of the rollout is deployed the v3 line is a SKIP with the missing marker.
        if (-not $SkipE2E) {
            $v3 = Test-V3ApiAvailable -Url $BaseUrl
            $e2eV3Script = Join-Path $testsDir 'e2e-v3.ps1'
            if ($v3.Available -and $v2Api.Available -and (Test-Path $e2eV3Script)) {
                Write-Host ''
                Write-Host '==> tests/e2e-v3.ps1' -ForegroundColor Cyan
                & $e2eV3Script -BaseUrl $BaseUrl -TimeoutSeconds $TimeoutSeconds
                if ($LASTEXITCODE -eq 0) { Add-Result 'E2E v3 chain' 'PASS' 'tenant account login + ORG_ADMIN/MEMBER/AUDITOR RBAC' }
                else { Add-Result 'E2E v3 chain' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                $detail = if (-not $v2Api.Available) { $v2Api.Detail } else { $v3.Detail }
                Add-Result 'E2E v3 chain' 'SKIP' $detail
            }
        }
        else {
            Add-Result 'E2E v3 chain' 'SKIP' '-SkipE2E'
        }

        # ------------------------------------------------ v5 skill/MCP/onboarding/RBAC checks
        # v5 adds: skill center, agent catalog, MCP keys/audits/external servers,
        # self-service onboarding, org/account import, RBAC permission matrix and
        # maintenance purge. Probed before creation; an old deployment is a SKIP.
        if (-not $SkipE2E) {
            $v5 = Test-V5ApiAvailable -Url $BaseUrl -Token $adminProbeToken
            $e2eV5Script = Join-Path $testsDir 'e2e-v5.ps1'
            if ($v5.Available -and (Test-Path $e2eV5Script)) {
                Write-Host ''
                Write-Host '==> tests/e2e-v5.ps1' -ForegroundColor Cyan
                $v5Args = @{ BaseUrl = $BaseUrl; TimeoutSeconds = $TimeoutSeconds }
                if (-not [string]::IsNullOrWhiteSpace($TenantServiceUrl)) {
                    $v5Args['TenantServiceUrl'] = $TenantServiceUrl
                }
                & $e2eV5Script @v5Args
                if ($LASTEXITCODE -eq 0) { Add-Result 'E2E v5 chain' 'PASS' 'import/onboarding/skills/agents/mcp/rbac/purge' }
                else { Add-Result 'E2E v5 chain' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                Add-Result 'E2E v5 chain' 'SKIP' $v5.Detail
            }
        }
        else {
            Add-Result 'E2E v5 chain' 'SKIP' '-SkipE2E'
        }

        # ------------------------------------------------ v5.1 skill versions/debug checks
        if (-not $SkipE2E) {
            $skillExt = Test-SkillVersionsAvailable -Url $BaseUrl -Token $adminProbeToken
            $e2eSkillScript = Join-Path $testsDir 'e2e-skill.ps1'
            if ($skillExt.Available -and (Test-Path $e2eSkillScript)) {
                Write-Host ''
                Write-Host '==> tests/e2e-skill.ps1' -ForegroundColor Cyan
                & $e2eSkillScript -BaseUrl $BaseUrl
                if ($LASTEXITCODE -eq 0) { Add-Result 'E2E skill versions' 'PASS' 'versions + version detail + debug hit/miss + 404' }
                else { Add-Result 'E2E skill versions' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                Add-Result 'E2E skill versions' 'SKIP' $skillExt.Detail
            }
        }
        else {
            Add-Result 'E2E skill versions' 'SKIP' '-SkipE2E'
        }

        # ------------------------------------------------ v6 OIDC/MFA checks
        if (-not $SkipE2E) {
            $v6 = Test-V6ApiAvailable -Url $BaseUrl -Token $adminProbeToken
            $e2eV6Script = Join-Path $testsDir 'e2e-v6.ps1'
            if ($v6.Available -and (Test-Path $e2eV6Script)) {
                Write-Host ''
                Write-Host '==> tests/e2e-v6.ps1' -ForegroundColor Cyan
                & $e2eV6Script -BaseUrl $BaseUrl -TimeoutSeconds $TimeoutSeconds
                if ($LASTEXITCODE -eq 0) { Add-Result 'E2E v6 chain' 'PASS' 'OIDC status/authorize + MFA enroll/login/recovery/disable' }
                else { Add-Result 'E2E v6 chain' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                Add-Result 'E2E v6 chain' 'SKIP' $v6.Detail
            }
        }
        else {
            Add-Result 'E2E v6 chain' 'SKIP' '-SkipE2E'
        }

        # ------------------------------------------------ v6 phase B SCIM checks
        if (-not $SkipE2E) {
            $scim = Test-ScimAvailable -Url $BaseUrl -Token $adminProbeToken
            $e2eV6bScript = Join-Path $testsDir 'e2e-v6b.ps1'
            if ($scim.Available -and (Test-Path $e2eV6bScript)) {
                Write-Host ''
                Write-Host '==> tests/e2e-v6b.ps1' -ForegroundColor Cyan
                & $e2eV6bScript -BaseUrl $BaseUrl -TimeoutSeconds $TimeoutSeconds
                if ($LASTEXITCODE -eq 0) { Add-Result 'E2E v6b SCIM' 'PASS' 'SCIM token + ServiceProviderConfig + Users/Groups CRUD/filter/pagination/error schema' }
                else { Add-Result 'E2E v6b SCIM' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                Add-Result 'E2E v6b SCIM' 'SKIP' $scim.Detail
            }
        }
        else {
            Add-Result 'E2E v6b SCIM' 'SKIP' '-SkipE2E'
        }

        # ------------------------------------------------ v7 quota / usage checks
        if (-not $SkipE2E) {
            $quotaApi = Test-QuotaAvailable -Url $BaseUrl -Token $adminProbeToken
            $e2eV7Script = Join-Path $testsDir 'e2e-v7.ps1'
            if ($quotaApi.Available -and (Test-Path $e2eV7Script)) {
                Write-Host ''
                Write-Host '==> tests/e2e-v7.ps1' -ForegroundColor Cyan
                & $e2eV7Script -BaseUrl $BaseUrl -TimeoutSeconds $TimeoutSeconds
                if ($LASTEXITCODE -eq 0) { Add-Result 'E2E v7 quota' 'PASS' 'quota API/enforce org+seat/import update/RPM/unlimited' }
                else { Add-Result 'E2E v7 quota' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                Add-Result 'E2E v7 quota' 'SKIP' $quotaApi.Detail
            }
        }
        else {
            Add-Result 'E2E v7 quota' 'SKIP' '-SkipE2E'
        }

        # ------------------------------------------------ v8 export / offboarding checks
        if (-not $SkipE2E) {
            $exportApi = Test-ExportAvailable -Url $BaseUrl -Token $adminProbeToken
            $e2eV8Script = Join-Path $testsDir 'e2e-v8.ps1'
            if ($exportApi.Available -and (Test-Path $e2eV8Script)) {
                Write-Host ''
                Write-Host '==> tests/e2e-v8.ps1' -ForegroundColor Cyan
                & $e2eV8Script -BaseUrl $BaseUrl -TimeoutSeconds $TimeoutSeconds
                if ($LASTEXITCODE -eq 0) { Add-Result 'E2E v8 export' 'PASS' 'export masked/unmasked + permissions + offboarding + audit' }
                else { Add-Result 'E2E v8 export' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                Add-Result 'E2E v8 export' 'SKIP' $exportApi.Detail
            }
        }
        else {
            Add-Result 'E2E v8 export' 'SKIP' '-SkipE2E'
        }

        # ------------------------------------------------ v10 platform identity / workflow checks
        if (-not $SkipE2E) {
            $v10 = Test-V10Available -Url $BaseUrl -Token $adminProbeToken
            $e2eV10Script = Join-Path $testsDir 'e2e-v10.ps1'
            if ($v10.Available -and (Test-Path $e2eV10Script)) {
                Write-Host ''
                Write-Host '==> tests/e2e-v10.ps1' -ForegroundColor Cyan
                & $e2eV10Script -BaseUrl $BaseUrl -TimeoutSeconds $TimeoutSeconds
                if ($LASTEXITCODE -eq 0) { Add-Result 'E2E v10 chain' 'PASS' 'platform users + SSO providers + tenant selection/switch + workflow' }
                else { Add-Result 'E2E v10 chain' 'FAIL' "exit $LASTEXITCODE" }
            }
            else {
                Add-Result 'E2E v10 chain' 'SKIP' $v10.Detail
            }
        }
        else {
            Add-Result 'E2E v10 chain' 'SKIP' '-SkipE2E'
        }
    }
}

# ---------------------------------------------------------------- summary
Write-Host ''
Write-Host '---------------- verify summary ----------------'
$results | Format-Table -AutoSize | Out-String | Write-Host
$passed = @($results | Where-Object { $_.Status -eq 'PASS' }).Count
$failed = @($results | Where-Object { $_.Status -eq 'FAIL' }).Count
$skipped = @($results | Where-Object { $_.Status -eq 'SKIP' }).Count
Write-Host ("PASS={0}  FAIL={1}  SKIP={2}" -f $passed, $failed, $skipped)
if ($failed -gt 0) {
    Write-Host 'result: FAIL' -ForegroundColor Red
    exit 1
}
Write-Host 'result: PASS' -ForegroundColor Green
exit 0
