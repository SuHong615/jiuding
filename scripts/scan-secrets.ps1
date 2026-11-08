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
#
# Scan tracked files and git history for high-confidence secret material.
# Demo credentials (dev-mcp-key / dev-internal-token / demo passwords) are
# intentionally allow-listed: they are documented demo values, not real secrets.
#
# Usage: pwsh ./scripts/scan-secrets.ps1 [-Root <repo>]
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
$patterns = @(
    @{ Name = 'Private key';      Regex = '-----BEGIN (RSA|EC|OPENSSH|PGP) PRIVATE KEY-----' },
    @{ Name = 'AWS access key';   Regex = 'AKIA[0-9A-Z]{16}' },
    @{ Name = 'GitHub token';     Regex = 'gh[pousr]_[A-Za-z0-9]{20,}' },
    @{ Name = 'GitLab token';     Regex = 'glpat-[A-Za-z0-9_-]{20,}' },
    @{ Name = 'Slack token';      Regex = 'xox[baprs]-[A-Za-z0-9-]{10,}' },
    @{ Name = 'OpenAI key';       Regex = 'sk-[A-Za-z0-9]{24,}' },
    @{ Name = 'Google API key';   Regex = 'AIza[0-9A-Za-z_-]{35}' },
    @{ Name = 'Stripe key';       Regex = '(sk|rk)_(live|test)_[0-9A-Za-z]{20,}' }
)

# Files that legitimately contain placeholder/example strings.
$exclude = @(
    'LICENSES/',
    'docs/assets/'
)

Push-Location $Root
try {
    $files = @(git ls-files)
    if ($files.Count -eq 0) { throw 'not a git repository or no tracked files' }

    $findings = New-Object System.Collections.Generic.List[object]
    foreach ($rel in $files) {
        if ($rel -like '*.png' -or $rel -like '*.jpg' -or $rel -like '*.gif' -or $rel -like '*.ico') { continue }
        $skip = $false
        foreach ($prefix in $exclude) { if ($rel.StartsWith($prefix)) { $skip = $true; break } }
        if ($skip) { continue }
        $path = Join-Path $Root $rel
        if (-not (Test-Path $path)) { continue }
        $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
        foreach ($p in $patterns) {
            foreach ($m in [regex]::Matches($text, $p.Regex)) {
                $start = [Math]::Max(0, $m.Index - 40)
                $len = [Math]::Min(100, $text.Length - $start)
                $findings.Add([pscustomobject]@{ File = $rel; Kind = $p.Name; Match = $m.Value.Substring(0, [Math]::Min(12, $m.Value.Length)) + '...' })
            }
        }
    }

    # History scan: catches secrets that were removed from the working tree but remain in commits.
    $history = & git log -p --all --no-color 2>$null
    foreach ($p in $patterns) {
        foreach ($m in [regex]::Matches(($history -join "`n"), $p.Regex)) {
            $findings.Add([pscustomobject]@{ File = '<git-history>'; Kind = $p.Name; Match = $m.Value.Substring(0, [Math]::Min(12, $m.Value.Length)) + '...' })
        }
    }

    if ($findings.Count -eq 0) {
        Write-Host '[PASS] no high-confidence secrets found in tracked files or git history' -ForegroundColor Green
        exit 0
    }
    Write-Host ("[FAIL] {0} potential secret(s) found:" -f $findings.Count) -ForegroundColor Red
    $findings | Format-Table -AutoSize | Out-String | Write-Host
    Write-Host 'Remove/rotate the value, then rewrite history if it was ever pushed (git filter-repo).' -ForegroundColor Yellow
    exit 1
}
finally {
    Pop-Location
}
