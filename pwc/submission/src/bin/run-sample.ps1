# Trusted CEO Agent — 데모 부트스트랩
# 데이터 존재 확인 → 불변식 게이트(verify-books) → 신호 파생(detect-signals) → 데모 프롬프트 출력.
# 임의 회사 데이터: -DataDir <폴더> (기본: 동봉 샘플)

param([string]$DataDir = "")

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if ([string]::IsNullOrEmpty($DataDir)) { $DataDir = Join-Path $root "data\sample" }
$files = @("trial_balance.csv", "ar_aging.csv", "cost_allocation.csv")

$ok = $true
foreach ($f in $files) {
    $path = Join-Path $DataDir $f
    if (Test-Path $path) {
        $lines = (Get-Content $path | Measure-Object -Line).Lines
        Write-Output "[OK] $f ($lines lines)"
    } else {
        Write-Output "[MISSING] $f"
        $ok = $false
    }
}

if (-not $ok) { exit 1 }

Write-Output ""
$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    node (Join-Path $root "bin\verify-books.mjs") --data-dir $DataDir
    if ($LASTEXITCODE -ne 0) {
        Write-Output ""
        Write-Output "[FAIL] 불변식 게이트 실패 — 추론 데모 전에 데이터 정합성부터 복구하세요."
        exit 1
    }
    Write-Output ""
    node (Join-Path $root "bin\detect-signals.mjs") --data-dir $DataDir
    if ($LASTEXITCODE -ne 0) {
        Write-Output ""
        Write-Output "[FAIL] 신호 파생 실패 — 게이트/데이터 상태를 확인하세요."
        exit 1
    }
} else {
    Write-Output "[SKIP] node 를 찾지 못해 게이트/신호 파생을 생략합니다 (MCP 도구 사용에도 node 필요)."
}

Write-Output ""
Write-Output "=== Trusted CEO Agent demo ready ==="
Write-Output "Codex/Claude Code 에서 아래 프롬프트로 시작하세요:"
Write-Output ""
Write-Output "  `"$DataDir 의 데이터에서 CEO가 놓치고 있는 리스크를 추출하고,"
Write-Output "   각 리스크의 근거를 자연어로 설명한 뒤 서명용 브리핑으로 정리해줘.`""
Write-Output ""
Write-Output "(스킬 명시 호출: `$ceo-risk-recon)"
Write-Output "(브리핑 채점:   node test/briefing-eval.mjs --data-dir `"$DataDir`" <briefing.md>)"
