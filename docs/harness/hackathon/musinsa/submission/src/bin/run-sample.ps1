# Musinsa Fashion First - demo bootstrap
# 옷장 복기 데모: 키 없이 완전 동작 (src/data/sample 은 정답지 내장 합성 데이터)
# Usage: powershell -ExecutionPolicy Bypass -File src/bin/run-sample.ps1

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$srcDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Output "== smoke (data + MCP protocol) =="
node (Join-Path $srcDir "test\run-all.mjs")
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Output ""
Write-Output "== demo prompt (codex 새 스레드에 붙여넣기) =="
Write-Output '"src/data/sample 의 옷장(closet.csv)과 구매 내역(purchases.csv, 반품 포함)을 복기해서,'
Write-Output ' 반복되는 구매 패턴을 근거 수치와 함께 찾아내고 다음 구매 규칙을 제안해줘.'
Write-Output ' 옷은 많은데 입을 게 없는 이유가 궁금해."'
Write-Output ""
Write-Output "기대 결과: 정답지(src/data/sample/README-data.md)의 3개 패턴 -"
Write-Output "  1) 세일 충동구매 4건 161,000원, 착용 2회"
Write-Output "  2) 블랙 상의 7/12벌 편중 (충동 구매 3건도 블랙)"
Write-Output "  3) 테일러독 L 사이즈 3연속 실패 (반품 2회 + 방치 1벌)"
