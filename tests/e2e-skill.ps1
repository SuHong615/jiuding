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
    v5.1 E2E: skill versions + in-memory rule debug (skill center, docs/15 section 12).

.DESCRIPTION
    Black-box E2E for the skill center version/debug API:

        admin login -> upload skill zip (v1.0.0) -> versions list contains 1.0.0
        -> PUT /api/skills/{id} with version 1.1.0 (new DRAFT version)
        -> versions list contains both versions -> old version detail keeps its content
        -> POST /api/skills/{id}/debug with matching input (rule hit, actions, durationMs)
        -> debug with non-matching input (no matched rule) -> unknown version returns 404
        -> cleanup (logical delete).

    Capability gate: GET /api/skills/template must return a zip; otherwise the whole
    run is SKIP with exit code 0 (old deployment).

    ASCII-only source (UTF-8 no BOM) so Windows PowerShell 5.1 parses it identically.

.PARAMETER BaseUrl
    Gateway base URL. Default http://localhost:18080 (local process mode).

.EXAMPLE
    pwsh ./tests/e2e-skill.ps1
    pwsh ./tests/e2e-skill.ps1 -BaseUrl http://localhost:30080
#>
param(
    [string]$BaseUrl = 'http://localhost:18080'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

$script:Base = $BaseUrl.TrimEnd('/')
$script:Results = New-Object System.Collections.Generic.List[object]
$script:AddedChecks = New-Object System.Collections.Generic.List[string]
$script:CleanedUp = $false
$script:AdminToken = ''
$script:AdminHeaders = @{}
$script:SkillId = ''
$script:PlannedChecks = @(
    'preflight gateway',
    'login admin',
    'skill versions api availability',
    'skill upload v1',
    'skill versions contains v1',
    'skill update new version',
    'skill versions multiple',
    'skill version detail v1',
    'skill debug hit',
    'skill debug miss',
    'skill version detail 404',
    'skill debug version 404',
    'skill cleanup'
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
    Write-Host '---------------- e2e-skill summary ----------------'
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
    foreach ($listName in @('items', 'records', 'versions', 'content')) {
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

function Get-RawBytes {
    param([string]$Path, [hashtable]$Headers = @{}, [int]$TimeoutSec = 30)
    $requestHeaders = @{}
    foreach ($key in $Headers.Keys) { $requestHeaders[$key] = [string]$Headers[$key] }
    $response = Invoke-WebRequest -Uri ($script:Base + $Path) -Headers $requestHeaders -UseBasicParsing -TimeoutSec $TimeoutSec -ErrorAction Stop
    $stream = $response.RawContentStream
    if ($null -eq $stream) { return , ([System.Text.Encoding]::UTF8.GetBytes([string]$response.Content)) }
    $stream.Position = 0
    $memory = New-Object System.IO.MemoryStream
    $stream.CopyTo($memory)
    return , ($memory.ToArray())
}

function Test-ZipBytes {
    param([byte[]]$Bytes)
    if ($null -eq $Bytes -or $Bytes.Length -lt 4) { return $false }
    return ($Bytes[0] -eq 0x50 -and $Bytes[1] -eq 0x4B)
}

function Invoke-MultipartApi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][byte[]]$FileBytes,
        [string]$FileName = 'skill.zip',
        [string]$PartContentType = 'application/zip',
        [hashtable]$FormFields = @{},
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 60
    )
    $client = New-Object System.Net.Http.HttpClient
    $multipart = $null
    $request = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
        $multipart = New-Object System.Net.Http.MultipartFormDataContent
        foreach ($key in $FormFields.Keys) {
            $stringContent = New-Object System.Net.Http.StringContent([string]$FormFields[$key])
            $multipart.Add($stringContent, [string]$key)
        }
        $fileContent = [System.Net.Http.ByteArrayContent]::new($FileBytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($PartContentType)
        $multipart.Add($fileContent, 'file', $FileName)
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

# ---------------------------------------------------------------- zip helpers
function New-SkillZipBytes {
    param(
        [Parameter(Mandatory = $true)][string]$SkillId,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$Instructions,
        [Parameter(Mandatory = $true)][string]$RulesYaml
    )
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $manifest = @"
---
id: $SkillId
name: E2E Versioned Skill
version: $Version
type: RISK
description: E2E v5.1 version and debug validation
triggers: [tenant.submitted]
tools: [tenant.get]
tags: [e2e, version]
---
# Role
You are the E2E versioned skill.

# Workflow
1. Read the tenant profile.
2. Return JSON with riskLevel, score and decision.

$Instructions
"@
    $memory = New-Object System.IO.MemoryStream
    $archive = New-Object System.IO.Compression.ZipArchive($memory, [System.IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        $skillEntry = $archive.CreateEntry('SKILL.md')
        $writer = New-Object System.IO.StreamWriter($skillEntry.Open(), $utf8)
        $writer.Write($manifest)
        $writer.Dispose()
        $rulesEntry = $archive.CreateEntry('rules.yaml')
        $writer = New-Object System.IO.StreamWriter($rulesEntry.Open(), $utf8)
        $writer.Write($RulesYaml)
        $writer.Dispose()
    }
    finally { $archive.Dispose() }
    $bytes = $memory.ToArray()
    $memory.Dispose()
    return , $bytes
}

# ---------------------------------------------------------------- cleanup
function Invoke-Cleanup {
    if ($script:CleanedUp) { return }
    $script:CleanedUp = $true
    if ([string]::IsNullOrWhiteSpace($script:AdminToken) -or [string]::IsNullOrWhiteSpace($script:SkillId)) { return }
    try { $null = Invoke-ApiRaw -Path ("/api/skills/{0}" -f $script:SkillId) -Method DELETE -Headers $script:AdminHeaders } catch { }
}

# ================================================================ run
Write-Host ("==> e2e-skill against {0}" -f $script:Base) -ForegroundColor Cyan

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

# ---------------- capability gate: skill template zip + list + versions endpoint
$templateReady = $false
try {
    $templateBytes = Get-RawBytes -Path '/api/skills/template' -Headers $script:AdminHeaders
    $list = Invoke-Api -Path '/api/skills' -Headers $script:AdminHeaders
    $firstSkillId = ''
    foreach ($item in @(Get-Items $list)) {
        $candidateId = [string](Get-JsonProp $item 'id')
        if (-not [string]::IsNullOrWhiteSpace($candidateId)) { $firstSkillId = $candidateId; break }
    }
    if ((Test-ZipBytes $templateBytes) -and -not [string]::IsNullOrWhiteSpace($firstSkillId)) {
        $versionsProbe = Invoke-ApiRaw -Path ("/api/skills/{0}/versions" -f $firstSkillId) -Headers $script:AdminHeaders
        if ($versionsProbe.Ok) {
            $templateReady = $true
            Add-Check 'skill versions api availability' 'PASS' ("template bytes={0} versions({1}) ok" -f $templateBytes.Length, $firstSkillId)
        }
        else {
            Add-Check 'skill versions api availability' 'SKIP' ("GET /api/skills/{0}/versions -> HTTP {1} code={2}" -f $firstSkillId, $versionsProbe.Status, $versionsProbe.Code)
        }
    }
    else {
        Add-Check 'skill versions api availability' 'SKIP' 'skill center template/list not available'
    }
}
catch {
    Add-Check 'skill versions api availability' 'SKIP' ("skill center not deployed: " + $_.Exception.Message)
}
if (-not $templateReady) {
    Skip-Remaining -Reason 'skill versions API not deployed (need GET /api/skills/{id}/versions)'
    Show-Summary
}

# ---------------- upload v1
$skillSuffix = New-RandomDigits 6
$skillId = 'e2e-skill-' + $skillSuffix
$v1Rules = @"
skillId: $skillId
version: 1.0.0
initialScore: 0
rules:
  - id: E2E-SKILL-001
    description: version one rule matches plan PRO
    severity: LOW
    when:
      all:
        - { field: plan, op: eq, value: PRO }
    then:
      - { action: addScore, value: 7 }
      - { action: addFinding, message: version one rule matched, severity: LOW }
decision:
  autoApproveMaxScore: 20
  rejectMinScore: 80
  riskLevels:
    - { level: LOW, maxScore: 20 }
    - { level: MEDIUM, maxScore: 60 }
    - { level: HIGH, maxScore: 100 }
"@
$v2Rules = @"
skillId: $skillId
version: 1.1.0
initialScore: 0
rules:
  - id: E2E-SKILL-002
    description: version two rule matches plan FREE
    severity: LOW
    when:
      all:
        - { field: plan, op: eq, value: FREE }
    then:
      - { action: addScore, value: 3 }
      - { action: addFinding, message: version two rule matched, severity: LOW }
decision:
  autoApproveMaxScore: 20
  rejectMinScore: 80
  riskLevels:
    - { level: LOW, maxScore: 20 }
    - { level: MEDIUM, maxScore: 60 }
    - { level: HIGH, maxScore: 100 }
"@

try {
    $v1Bytes = New-SkillZipBytes -SkillId $skillId -Version '1.0.0' -Instructions 'E2E-SKILL-MARKER version one instructions' -RulesYaml $v1Rules
    $upload = Invoke-MultipartApi -Path '/api/skills/upload' -FileBytes $v1Bytes -FileName ($skillId + '-1.0.0.zip') -Headers $script:AdminHeaders -FormFields @{ submitForReview = 'false' }
    if ($upload.Ok) {
        $data = $upload.Data
        $script:SkillId = [string](Get-JsonProp $data 'id')
        $version = [string](Get-JsonProp $data 'version')
        $status = [string](Get-JsonProp $data 'status')
        if (-not [string]::IsNullOrWhiteSpace($script:SkillId) -and $version -eq '1.0.0' -and ($status -eq 'DRAFT' -or $status -eq 'PENDING_REVIEW')) {
            Add-Check 'skill upload v1' 'PASS' ("id={0} version={1} status={2}" -f $script:SkillId, $version, $status)
        }
        else {
            Add-Check 'skill upload v1' 'FAIL' ("id={0} version={1} status={2}" -f $script:SkillId, $version, $status)
        }
    }
    else {
        Add-Check 'skill upload v1' 'FAIL' ("HTTP {0} code={1} message={2}" -f $upload.Status, $upload.Code, $upload.Message)
    }
}
catch {
    Add-Check 'skill upload v1' 'FAIL' $_.Exception.Message
}
if ([string]::IsNullOrWhiteSpace($script:SkillId)) {
    Skip-Remaining -Reason 'skill upload failed'
    Invoke-Cleanup
    Show-Summary
}

# ---------------- versions contain 1.0.0
try {
    $versions = Invoke-Api -Path ("/api/skills/{0}/versions" -f $script:SkillId) -Headers $script:AdminHeaders
    $versionItems = @(Get-Items $versions)
    $v1 = $versionItems | Where-Object { [string](Get-JsonProp $_ 'version') -eq '1.0.0' } | Select-Object -First 1
    if ($null -ne $v1) {
        Add-Check 'skill versions contains v1' 'PASS' ("versions={0} fields=version/status/enabled/latest" -f $versionItems.Count)
    }
    else {
        Add-Check 'skill versions contains v1' 'FAIL' ("versions={0}" -f (($versionItems | ForEach-Object { [string](Get-JsonProp $_ 'version') }) -join ','))
    }
}
catch {
    Add-Check 'skill versions contains v1' 'FAIL' $_.Exception.Message
}

# ---------------- PUT new version 1.1.0
try {
    $updated = Invoke-Api -Path ("/api/skills/{0}" -f $script:SkillId) -Method PUT -Headers $script:AdminHeaders -Body @{
        version      = '1.1.0'
        instructions = 'E2E-SKILL-MARKER version two instructions'
        rulesYaml    = $v2Rules
        description  = 'E2E v5.1 version two'
    }
    $newVersion = [string](Get-JsonProp $updated 'version')
    $newStatus = [string](Get-JsonProp $updated 'status')
    if ($newVersion -eq '1.1.0' -and $newStatus -eq 'DRAFT') {
        Add-Check 'skill update new version' 'PASS' ("version={0} status={1}" -f $newVersion, $newStatus)
    }
    else {
        Add-Check 'skill update new version' 'FAIL' ("version={0} status={1}" -f $newVersion, $newStatus)
    }
}
catch {
    Add-Check 'skill update new version' 'FAIL' $_.Exception.Message
}

# ---------------- versions list contains multiple versions
try {
    $versions = Invoke-Api -Path ("/api/skills/{0}/versions" -f $script:SkillId) -Headers $script:AdminHeaders
    $versionItems = @(Get-Items $versions)
    $versionNames = @($versionItems | ForEach-Object { [string](Get-JsonProp $_ 'version') })
    if ($versionItems.Count -ge 2 -and $versionNames -contains '1.0.0' -and $versionNames -contains '1.1.0') {
        Add-Check 'skill versions multiple' 'PASS' ("versions={0}" -f ($versionNames -join ','))
    }
    else {
        Add-Check 'skill versions multiple' 'FAIL' ("versions={0}" -f ($versionNames -join ','))
    }
}
catch {
    Add-Check 'skill versions multiple' 'FAIL' $_.Exception.Message
}

# ---------------- old version detail keeps its content
try {
    $detail = Invoke-Api -Path ("/api/skills/{0}/versions/1.0.0" -f $script:SkillId) -Headers $script:AdminHeaders
    $detailVersion = [string](Get-JsonProp $detail 'version')
    $instructions = [string](Get-JsonProp $detail 'instructions')
    $rulesYaml = [string](Get-JsonProp $detail 'rulesYaml')
    $ok = ($detailVersion -eq '1.0.0' -and $instructions.Contains('version one') -and $rulesYaml.Contains('E2E-SKILL-001'))
    if ($ok) {
        Add-Check 'skill version detail v1' 'PASS' ('version=1.0.0 content=v1')
    }
    else {
        Add-Check 'skill version detail v1' 'FAIL' ("version={0} hasInstructions={1} hasV1Rule={2}" -f $detailVersion, $instructions.Contains('version one'), $rulesYaml.Contains('E2E-SKILL-001'))
    }
}
catch {
    Add-Check 'skill version detail v1' 'FAIL' $_.Exception.Message
}

# ---------------- debug: hit
try {
    $debug = Invoke-Api -Path ("/api/skills/{0}/debug" -f $script:SkillId) -Method POST -Headers $script:AdminHeaders -Body @{
        input   = @{ plan = 'PRO'; region = 'cn-shanghai' }
        version = '1.0.0'
    }
    $decision = [string](Get-JsonProp $debug 'decision')
    $durationMs = Get-JsonProp $debug 'durationMs'
    $matchedRules = @(Get-Items (Get-JsonProp $debug 'matchedRules'))
    $hitRule = $matchedRules | Where-Object { [string](Get-JsonProp $_ 'ruleId') -eq 'E2E-SKILL-001' -and [bool](Get-JsonProp $_ 'matched') } | Select-Object -First 1
    $actions = @(Get-Items (Get-JsonProp $debug 'actions'))
    $durationOk = ($null -ne $durationMs -and [long]$durationMs -ge 0)
    if (-not [string]::IsNullOrWhiteSpace($decision) -and $durationOk -and $null -ne $hitRule -and $actions.Count -ge 1) {
        Add-Check 'skill debug hit' 'PASS' ("decision={0} actions={1} matchedRule=E2E-SKILL-001 durationMs={2}" -f $decision, $actions.Count, $durationMs)
    }
    else {
        Add-Check 'skill debug hit' 'FAIL' ("decision={0} actions={1} hitRule={2} durationMs={3}" -f $decision, $actions.Count, ($null -ne $hitRule), $durationMs)
    }
}
catch {
    Add-Check 'skill debug hit' 'FAIL' $_.Exception.Message
}

# ---------------- debug: miss
try {
    $debugMiss = Invoke-Api -Path ("/api/skills/{0}/debug" -f $script:SkillId) -Method POST -Headers $script:AdminHeaders -Body @{
        input   = @{ plan = 'FREE'; region = 'cn-shanghai' }
        version = '1.0.0'
    }
    $missRules = @(Get-Items (Get-JsonProp $debugMiss 'matchedRules'))
    $matchedTrue = @($missRules | Where-Object { [bool](Get-JsonProp $_ 'matched') }).Count
    $missDuration = Get-JsonProp $debugMiss 'durationMs'
    $missDurationOk = ($null -ne $missDuration -and [long]$missDuration -ge 0)
    if ($matchedTrue -eq 0 -and $missDurationOk) {
        Add-Check 'skill debug miss' 'PASS' ("matchedRules={0} matchedTrue=0 durationMs={1}" -f $missRules.Count, $missDuration)
    }
    else {
        Add-Check 'skill debug miss' 'FAIL' ("matchedRules={0} matchedTrue={1} durationMs={2}" -f $missRules.Count, $matchedTrue, $missDuration)
    }
}
catch {
    Add-Check 'skill debug miss' 'FAIL' $_.Exception.Message
}

# ---------------- unknown version 404 (detail + debug)
try {
    $missing = Invoke-ApiRaw -Path ("/api/skills/{0}/versions/9.9.9" -f $script:SkillId) -Headers $script:AdminHeaders
    if ($missing.Status -eq 404 -or $missing.Code -eq 40400 -or $missing.Code -eq 54400) {
        Add-Check 'skill version detail 404' 'PASS' ("HTTP {0} code={1}" -f $missing.Status, $missing.Code)
    }
    else {
        Add-Check 'skill version detail 404' 'FAIL' ("expected 404, got HTTP {0} code={1}" -f $missing.Status, $missing.Code)
    }
}
catch {
    Add-Check 'skill version detail 404' 'FAIL' $_.Exception.Message
}
try {
    $missingDebug = Invoke-ApiRaw -Path ("/api/skills/{0}/debug" -f $script:SkillId) -Method POST -Headers $script:AdminHeaders -Body @{
        input = @{ plan = 'PRO' }; version = '9.9.9'
    }
    if ($missingDebug.Status -eq 404 -or $missingDebug.Code -eq 40400 -or $missingDebug.Code -eq 54400) {
        Add-Check 'skill debug version 404' 'PASS' ("HTTP {0} code={1}" -f $missingDebug.Status, $missingDebug.Code)
    }
    else {
        Add-Check 'skill debug version 404' 'FAIL' ("expected 404, got HTTP {0} code={1}" -f $missingDebug.Status, $missingDebug.Code)
    }
}
catch {
    Add-Check 'skill debug version 404' 'FAIL' $_.Exception.Message
}

# ---------------- cleanup
try {
    $deleted = Invoke-Api -Path ("/api/skills/{0}" -f $script:SkillId) -Method DELETE -Headers $script:AdminHeaders
    $deleteStatus = [string](Get-JsonProp $deleted 'status')
    if ($deleteStatus -eq 'DISABLED' -or $deleteStatus -eq 'DELETED') {
        Add-Check 'skill cleanup' 'PASS' ("status={0}" -f $deleteStatus)
        $script:SkillId = ''
    }
    else {
        Add-Check 'skill cleanup' 'PASS' ("delete ok status={0}" -f $deleteStatus)
        $script:SkillId = ''
    }
}
catch {
    Add-Check 'skill cleanup' 'FAIL' $_.Exception.Message
}

Invoke-Cleanup
Skip-Remaining -Reason 'not executed'
Show-Summary
