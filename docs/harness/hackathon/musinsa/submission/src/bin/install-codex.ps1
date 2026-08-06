# Musinsa Fashion First - Codex one-shot installer (idempotent)
# 1) marketplace add  2) plugin add  3) MCP approval config merge
# 4) ~/.codex/.env scaffold  5) smoke verify
# Usage: powershell -ExecutionPolicy Bypass -File src/bin/install-codex.ps1

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$srcDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$submission = Split-Path -Parent $srcDir
$codexHome = Join-Path $env:USERPROFILE ".codex"

Write-Output "== [1/5] marketplace add =="
codex plugin marketplace add $submission 2>&1 | Out-String | Write-Output
Write-Output "== [2/5] plugin add =="
codex plugin add musinsa-fashion-first@musinsa-fashion-first-market 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "plugin add failed"; exit 1 }

Write-Output "== [3/5] MCP approval config =="
# 서버 블록 단위 멱등 병합 - 새 MCP 서버가 늘어도 빠진 블록만 추가된다
$configPath = Join-Path $codexHome "config.toml"
$snippet = Get-Content -Raw (Join-Path $submission "docs\codex-config-snippet.toml") -Encoding UTF8
$config = ""
if (Test-Path $configPath) { $config = Get-Content -Raw $configPath -Encoding UTF8 }
$appended = 0
foreach ($block in ($snippet -split "(\r?\n){2,}" | Where-Object { $_ -match '^\[plugins' })) {
    $header = ($block -split "\r?\n")[0].Trim()
    if ($config -notmatch [regex]::Escape($header)) {
        Add-Content -Path $configPath -Value "`n$($block.Trim())`n" -Encoding UTF8
        $config += "`n$block"
        $appended += 1
    }
}
if ($appended -gt 0) { Write-Output "approval config: $appended block(s) appended -> $configPath" }
else { Write-Output "approval config already present - skip" }

Write-Output "== [4/5] API keys (~/.codex/.env) =="
$envPath = Join-Path $codexHome ".env"
if (-not (Test-Path $envPath)) {
    Copy-Item (Join-Path $submission ".env.example") $envPath
    Write-Output "scaffolded $envPath - fill NAVER keys (optional; closet demo works without keys)"
} else {
    Write-Output "$envPath already exists - keep"
}

Write-Output "== [5/5] smoke verify =="
node (Join-Path $srcDir "test\run-all.mjs")
if ($LASTEXITCODE -ne 0) { Write-Output "smoke FAILED"; exit 1 }

Write-Output ""
Write-Output "DONE. Try in a new codex thread:"
Write-Output '  "무신사에서 쿠어라는 브랜드 봤는데 처음 들어봐. 어떤 브랜드야?"'
Write-Output '  "이 니트 세일가 41,650원(65% 할인)인데 진짜 싼 거야?"'
Write-Output '  "src/data/sample 의 옷장이랑 구매 내역 좀 복기해줘."'
