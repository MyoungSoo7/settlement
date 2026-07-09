# KakaoPay Invest Companion — 데모 부트스트랩
# 샘플 데이터 무결성을 확인하고 데모 프롬프트를 출력한다.

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$dataDir = Join-Path $root "data\sample"
$files = @("trades.csv", "holdings.csv")

$ok = $true
foreach ($f in $files) {
    $path = Join-Path $dataDir $f
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
Write-Output "=== KakaoPay Invest Companion demo ready ==="
Write-Output "Codex/Claude Code 에서 아래 프롬프트로 시작하세요:"
Write-Output ""
Write-Output '  [복기 데모] "src/data/sample 의 매매 내역과 보유 현황을 복기해서,'
Write-Output '   반복되는 행동 패턴을 찾아 근거와 함께 설명하고 다음 매매 규칙을 제안해줘."'
Write-Output ""
Write-Output '  [불안 데모] "한빛전자 물렸는데 더 사서 물타기하면 될까요?"'
Write-Output ""
Write-Output '  [탐색 데모] "주식 처음인데 뭘 사야 할지 모르겠어요."'
Write-Output ""
Write-Output "(스킬 명시 호출: `$trade-retrospective, `$anxiety-triage, `$stock-explorer)"
Write-Output "(사전 점검: node src/test/run-all.mjs — 4개 스위트 ALL GREEN 확인)"
