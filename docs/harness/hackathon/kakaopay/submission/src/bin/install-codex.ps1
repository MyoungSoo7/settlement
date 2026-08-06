# KakaoPay Invest Companion - Codex one-shot installer (idempotent)
# 1) marketplace add  2) plugin add  3) MCP approval config merge
# 4) ~/.codex/.env scaffold  5) smoke verify
# Usage: powershell -ExecutionPolicy Bypass -File src/bin/install-codex.ps1

$ErrorActionPreference = "Stop"
$srcDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$submission = Split-Path -Parent $srcDir
$codexHome = Join-Path $env:USERPROFILE ".codex"

Write-Output "== [1/5] marketplace add =="
codex plugin marketplace add $submission 2>&1 | Out-String | Write-Output
Write-Output "== [2/5] plugin add =="
codex plugin add kakaopay-invest-companion@kakaopay-invest-companion-market 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "plugin add failed"; exit 1 }

Write-Output "== [3/5] MCP approval config =="
$configPath = Join-Path $codexHome "config.toml"
# 모든 도구가 읽기 전용 조회라 approve 가 안전. 이 블록이 없으면 비대화(codex exec)에서
# MCP 호출이 자동 취소된다("user cancelled" - 실측). 과거 docs/codex-config-snippet.toml 을 인라인화.
$snippet = @'
# kakaopay-invest-companion - Codex MCP tool approval (auto-added by install-codex.ps1)
[plugins."kakaopay-invest-companion@kakaopay-invest-companion-market".mcp_servers.invest-companion-dart]
enabled = true
default_tools_approval_mode = "approve"

[plugins."kakaopay-invest-companion@kakaopay-invest-companion-market".mcp_servers.invest-companion-ecos]
enabled = true
default_tools_approval_mode = "approve"

[plugins."kakaopay-invest-companion@kakaopay-invest-companion-market".mcp_servers.invest-companion-news]
enabled = true
default_tools_approval_mode = "approve"

[plugins."kakaopay-invest-companion@kakaopay-invest-companion-market".mcp_servers.invest-companion-price]
enabled = true
default_tools_approval_mode = "approve"
'@
$marker = 'mcp_servers.invest-companion-dart'
$config = ""
if (Test-Path $configPath) { $config = Get-Content -Raw $configPath -Encoding UTF8 }
if ($config -notmatch [regex]::Escape($marker)) {
    Add-Content -Path $configPath -Value "`n$snippet" -Encoding UTF8
    Write-Output "approval config appended -> $configPath"
} else {
    Write-Output "approval config already present - skip"
}

Write-Output "== [4/5] API keys (~/.codex/.env) =="
$envPath = Join-Path $codexHome ".env"
if (-not (Test-Path $envPath)) {
    Copy-Item (Join-Path $submission ".env.example") $envPath
    Write-Output "scaffolded $envPath - fill DART/ECOS/NAVER keys (optional; price axis and sample demo work without keys)"
} else {
    Write-Output "$envPath already exists - keep"
}

Write-Output "== [5/5] smoke verify =="
node (Join-Path $srcDir "test\run-all.mjs")
if ($LASTEXITCODE -ne 0) { Write-Output "smoke FAILED"; exit 1 }

Write-Output ""
Write-Output "DONE. Try in a new codex thread:"
Write-Output '  "삼성전자 어제 5% 빠졌는데 지금이라도 팔아야 하나요? 무서워요."'
Write-Output '  "주식 처음인데 뭘 사야 할지 모르겠어요."'
