# 기업 문서함 업로드 — 외부 파이프라인 산출물(CEO 브리핑 docx 등)을 company-service 에 적재한다.
#
#   .\scripts\docs\upload-company-document.ps1 -StockCode 005930 -File .\briefing.docx -Title "삼성전자 CEO 브리핑"
#
# /admin/company/** 는 gateway 미라우팅(외부 미노출) — 로컬은 8090 직결, 운영은 port-forward 로 접근한다.
#   kubectl port-forward svc/lemuel-company 8090:8090
# 시크릿은 COMPANY_INTERNAL_API_KEY 환경변수(운영 필수, 로컬 미설정 시 무게이팅 통과)를 그대로 쓴다.
# 같은 (stockCode, 파일명) 재업로드는 교체 — 재생성한 브리핑이 자연스럽게 최신본이 된다.
param(
    [Parameter(Mandatory = $true)][string]$StockCode,
    [Parameter(Mandatory = $true)][string]$File,
    [string]$Title = "",
    [string]$BaseUrl = "http://localhost:8090",
    [string]$ApiKey = $env:COMPANY_INTERNAL_API_KEY
)

if (-not (Test-Path $File)) {
    Write-Error "파일을 찾을 수 없습니다: $File"
    exit 1
}

$resolved = (Resolve-Path $File).Path
$curlArgs = @(
    "-sS", "--fail-with-body",
    "-X", "POST", "$BaseUrl/admin/company/documents",
    "-F", "stockCode=$StockCode",
    "-F", "file=@$resolved"
)
if ($Title) { $curlArgs += @("-F", "title=$Title") }
if ($ApiKey) { $curlArgs += @("-H", "X-Internal-Api-Key: $ApiKey") }

& curl.exe @curlArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "업로드 실패 (exit $LASTEXITCODE) — 서비스 기동·시크릿·종목코드를 확인하세요"
    exit $LASTEXITCODE
}
Write-Host "`n업로드 완료: $StockCode ← $(Split-Path $resolved -Leaf)"
