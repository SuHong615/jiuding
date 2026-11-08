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
    导出多租户中心的本地开发环境变量（Maven / JDK17 / Node+npm）。

.DESCRIPTION
    由其它脚本 dot-source 使用：

        . "$PSScriptRoot\env.ps1"

    导出内容（$global: 变量 + 进程环境变量）：
        $global:TenancyRoot       仓库根目录（同时写入 $env:TENANCY_HOME）
        $global:TenancyMvn        mvn.cmd 完整路径
        $global:TenancyM2Repo     本地 Maven 仓库
        $global:TenancyNode       node 可执行文件
        $global:TenancyNpm        npm.cmd 可执行文件
        $global:TenancyNpmRegistry 当前 npm registry（只读，不修改）

    设计约束：幂等（重复 dot-source 不会重复追加 PATH）、不修改全局 npm/maven 配置、
    兼容 Windows PowerShell 5.1 与 PowerShell 7。
#>

$ErrorActionPreference = 'Stop'

function Add-TenancyPathEntry {
    param([Parameter(Mandatory = $true)][string]$Directory)
    if ([string]::IsNullOrWhiteSpace($Directory)) { return }
    $existing = @($env:Path -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($existing -notcontains $Directory) {
        $env:Path = "$Directory;$env:Path"
    }
}

function Find-TenancyJavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $candidates.Add('C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot')
    $candidates.Add('C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\')
    $candidates.Add('C:\Program Files\Eclipse Adoptium\jdk-17')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) {
            return (Resolve-Path $candidate).Path
        }
    }
    $java = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if ($java) {
        $bin = Split-Path -Parent $java.Source
        return (Split-Path -Parent $bin)
    }
    return $null
}

function Find-TenancyMaven {
    $candidates = New-Object System.Collections.Generic.List[string]
    $candidates.Add('D:\deepseekHarness\env\apache-maven-3.9.9\bin\mvn.cmd')
    if ($global:TenancyRoot) {
        $candidates.Add((Join-Path $global:TenancyRoot 'env\apache-maven-3.9.9\bin\mvn.cmd'))
    }
    if ($env:USERPROFILE) {
        $candidates.Add((Join-Path $env:USERPROFILE 'apache-maven-3.9.9\bin\mvn.cmd'))
    }
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
    }
    $mvn = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
    if (-not $mvn) { $mvn = Get-Command 'mvn' -ErrorAction SilentlyContinue }
    if ($mvn) { return $mvn.Source }
    return $null
}

function Find-TenancyM2Repo {
    $settings = Join-Path $env:USERPROFILE '.m2\settings.xml'
    if (Test-Path $settings) {
        $content = Get-Content $settings -Raw
        $match = [regex]::Match($content, '<localRepository>\s*(.*?)\s*</localRepository>')
        if ($match.Success) { return $match.Groups[1].Value }
    }
    $default = Join-Path $env:USERPROFILE '.m2\repository'
    return $default
}

# ---------------------------------------------------------------- repository root
$global:TenancyRoot = Split-Path -Parent $PSScriptRoot
if (-not $global:TenancyRoot) {
    throw 'Cannot resolve repository root (scripts/env.ps1 must live in <repo>/scripts).'
}
$global:TenancyRoot = (Resolve-Path $global:TenancyRoot).Path
$env:TENANCY_HOME = $global:TenancyRoot

# ---------------------------------------------------------------- JDK 17
$javaHome = Find-TenancyJavaHome
if ($javaHome) {
    $env:JAVA_HOME = $javaHome
    Add-TenancyPathEntry (Join-Path $javaHome 'bin')
}
else {
    Write-Warning 'JDK 17 not found: set JAVA_HOME before building/running the services.'
}

# ---------------------------------------------------------------- Maven
$global:TenancyMvn = Find-TenancyMaven
if ($global:TenancyMvn) {
    Add-TenancyPathEntry (Split-Path -Parent $global:TenancyMvn)
    $global:TenancyM2Repo = Find-TenancyM2Repo
    if (-not $env:MAVEN_OPTS) {
        $env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'
    }
}
else {
    Write-Warning 'Maven not found: set TENANCY_MAVEN or put mvn.cmd on PATH.'
}

# ---------------------------------------------------------------- Node / npm
$node = Get-Command 'node.exe' -ErrorAction SilentlyContinue
if (-not $node) { $node = Get-Command 'node' -ErrorAction SilentlyContinue }
$npm = Get-Command 'npm.cmd' -ErrorAction SilentlyContinue
if (-not $npm) { $npm = Get-Command 'npm' -ErrorAction SilentlyContinue }
$global:TenancyNode = if ($node) { $node.Source } else { $null }
$global:TenancyNpm = if ($npm) { $npm.Source } else { $null }
$global:TenancyNpmRegistry = $null
if ($global:TenancyNpm) {
    try {
        $global:TenancyNpmRegistry = (& $global:TenancyNpm config get registry 2>$null | Select-Object -First 1)
    }
    catch {
        $global:TenancyNpmRegistry = $null
    }
}

# ---------------------------------------------------------------- project-wide defaults
if (-not $env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE = 'dev' }
if (-not $env:TENANCY_API_KEY) { $env:TENANCY_API_KEY = 'dev-mcp-key' }
if (-not $env:TENANCY_INTERNAL_TOKEN) { $env:TENANCY_INTERNAL_TOKEN = 'dev-internal-token' }
if (-not $env:TENANCY_JWT_SECRET) { $env:TENANCY_JWT_SECRET = 'tenancy-center-dev-secret-change-me-please' }

# ---------------------------------------------------------------- shared helpers
function Get-TenancyCliJar {
    $target = Join-Path $global:TenancyRoot 'tenancy-cli\target'
    $preferred = Join-Path $target 'tenancy-cli.jar'
    if (Test-Path $preferred) { return $preferred }
    if (-not (Test-Path $target)) { return $null }
    $jar = Get-ChildItem $target -Filter 'tenancy-cli*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike 'original-*' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($jar) { return $jar.FullName }
    return $null
}

function Get-TenancyModuleJar {
    param([Parameter(Mandatory = $true)][string]$Module)
    $target = Join-Path $global:TenancyRoot "$Module\target"
    if (-not (Test-Path $target)) { return $null }
    $jar = Get-ChildItem $target -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -notlike 'original-*' -and
            $_.Name -notlike '*-sources.jar' -and
            $_.Name -notlike '*-javadoc.jar' -and
            $_.Name -notlike '*-tests.jar'
        } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($jar) { return $jar.FullName }
    return $null
}

function Test-TenancyTcpPort {
    param(
        [string]$HostName = '127.0.0.1',
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutMs = 800
    )
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($async)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Close()
    }
}

function Wait-TenancyHttpHealth {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSeconds = 90,
        [int]$IntervalMs = 1500
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) { return 'UP' }
        }
        catch {
            $response = $_.Exception.Response
            if ($response -and [int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 300) {
                return 'UP'
            }
        }
        Start-Sleep -Milliseconds $IntervalMs
    }
    return 'TIMEOUT'
}

# ---------------------------------------------------------------- summary
if ($MyInvocation.InvocationName -ne '.') {
    Write-Host 'tenancy local environment' -ForegroundColor Cyan
    Write-Host ("  TENANCY_HOME : {0}" -f $env:TENANCY_HOME)
    Write-Host ("  JAVA_HOME    : {0}" -f $env:JAVA_HOME)
    Write-Host ("  mvn          : {0}" -f $global:TenancyMvn)
    Write-Host ("  m2 repo      : {0}" -f $global:TenancyM2Repo)
    Write-Host ("  node         : {0}" -f $global:TenancyNode)
    Write-Host ("  npm          : {0}" -f $global:TenancyNpm)
    Write-Host ("  npm registry : {0}" -f $global:TenancyNpmRegistry)
    Write-Host ''
    Write-Host 'Tip: dot-source this file to import the environment:  . .\scripts\env.ps1' -ForegroundColor DarkGray
}
