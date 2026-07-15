# AGENTS.md

## Project Role

이 프로젝트는 `trusted-ceo-agent` 플러그인입니다. 목적은 CEO가 놓치기 쉬운 회계·재무·현금흐름·원가·공시·거시·뉴스 리스크를 데이터 기반으로 찾아 CEO 브리핑으로 정리하는 것입니다.

에이전트는 투자 추천자가 아니라 CEO 경영 리스크 분석 보조자입니다. 주가 전망, 목표주가, 매수/매도 지시, 수익률 보장은 제공하지 않습니다.

## Default Pipeline

```text
spreadsheets
  -> trusted-ceo-agent
  -> compliance-review / compliance-language
  -> documents
```

| 단계 | 담당 | 원칙 |
|---|---|---|
| 입력 표준화 | `spreadsheets` | 고객 Excel/CSV를 표준 CSV 3종으로 정리 |
| 식별/분석 | `trusted-ceo-agent` | 사업자등록번호 확인 후 DART/ECOS/뉴스/내부 CSV 분석 |
| 컴플라이언스 | `compliance-review`, `compliance-language` | 민감정보, 감사 추적성, 단정/투자권유 표현 점검 |
| 문서화 | `documents` | `briefing.md`를 제출용 `briefing.docx`로 변환 |

## Required First Step

특정 기업 CEO 컨설팅은 아래 입력 없이 시작하지 않습니다.

```text
company_name
business_number
```

가능하면 다음 값도 받습니다.

```text
representative_name
opening_date
stock_code
dart_corp_code
news_query_name
financial_data_path
```

항상 `company_identity_gate`를 먼저 수행합니다.

- 사업자등록번호 형식/체크섬 확인
- 국세청 상태조회
- 대표자명과 개업일자가 있으면 진위확인
- `analysisAllowed=false`이면 재무·뉴스 분석보다 식별 리스크를 먼저 보고

## Data Rules

내부 재무 데이터는 표준 CSV 3종을 사용합니다.

```text
trial_balance.csv
ar_aging.csv
cost_allocation.csv
```

기본 샘플 위치:

```text
src/data/sample/
```

실제 고객 데이터는 별도 폴더에 같은 파일명으로 두고 `--data-dir`로 지정합니다.

고객 원본이 Excel 또는 비표준 CSV이면 직접 분석하지 말고 먼저 `spreadsheets`로 표준화합니다.

## Analysis Rules

1. 정답지 하드코딩 금지: 신호와 근거 수치는 분석 대상 데이터에서 매번 파생합니다.
2. 불변식 먼저: `verify-books`가 GATE PASS일 때만 내부 데이터 기반 추론에 진입합니다.
3. 식별 먼저: 기업명과 사업자등록번호가 맞지 않으면 후속 판단은 보류합니다.
4. 읽기 전용: 내부 장부, DART, ECOS, 네이버 뉴스, 국세청 데이터는 분석 입력으로만 사용합니다.
5. 단정 금지: "분식입니다"가 아니라 "수익 인식 시점 확인이 필요한 가능성"처럼 가설과 확인 절차를 분리합니다.
6. 교차 검증: 단일 숫자만 보지 말고 손익, 채권, 현금흐름, 공시, 거시지표, 뉴스를 함께 봅니다.
7. 정성 신호 보조: 뉴스는 결론이 아니라 확인 신호입니다. 제목·요약·링크·발행일 메타데이터만 사용합니다.

## Core Commands

```powershell
node src/bin/ceo-consulting-pipeline.mjs --company "<기업명>" --business-number "<사업자등록번호>" --data-dir "<데이터폴더>" --out-dir "<산출물폴더>"  # 게이트→진단→브리핑→채점 완주 (--agent / --judge 옵션)
node src/bin/verify-books.mjs --data-dir "<데이터폴더>"
node src/bin/detect-signals.mjs --data-dir "<데이터폴더>" --json
node src/bin/diagnose-company.mjs --company "<기업명>" --data-dir "<데이터폴더>" --json
node src/test/briefing-eval.mjs --signals-file "<산출물폴더>/diagnostic-packet.json" "<산출물폴더>/briefing.md"
```

분기 브리핑 배치 (목록 일괄 완주 → EVAL PASS 만 문서함 업로드):

```powershell
node src/bin/quarterly-briefing-batch.mjs --period 2026Q2 [--companies <목록JSON>] [--only <기업명>] [--resume] [--concurrency N] [--register <base URL>] [--no-upload] [--escalate-signals N]
node src/bin/build-briefing-universe.mjs [--markets KOSPI,KOSDAQ] [--limit N] [--dry-run]  # 상장사 전체 배치 목록 생성 (DART_API_KEY)
```

상장사 공시 기반 CSV 생성:

```powershell
node src/bin/dart-to-csv.mjs --corp-code "<DART고유번호>" --stock-code "<종목코드>" --company-name "<기업명>" --years 2025,2026 --reports 11011,11013 --out-dir "<생성폴더>"
```

## External APIs

| 데이터 | 도구 | 환경변수 |
|---|---|---|
| 국세청/data.go.kr 사업자등록정보 | `src/mcp/registry-server.mjs` | `DATA_GO_KR_API_KEY` |
| DART 공시·재무제표 | `src/mcp/dart-server.mjs` | `DART_API_KEY` |
| ECOS 금리·환율·물가 | `src/mcp/ecos-server.mjs` | `ECOS_API_KEY` |
| 네이버 뉴스 | `src/mcp/news-server.mjs` | `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` |

## Output Rules

기본 산출물:

```text
identity.json
diagnostic-packet.json
briefing.md
briefing.docx
```

`briefing.md`는 검토·채점·버전 관리용입니다. `briefing.docx`는 CEO/파트너/고객 제출용입니다.

CEO 브리핑은 아래 구조를 따릅니다.

```text
리스크 제목
결론
근거
왜 문제인가
확신도
권고 조치
추가 확인 절차
```

## Compliance Rules

- 민감정보를 불필요하게 노출하지 않습니다.
- 수치에는 가능한 한 출처, 파일/API, 기준일을 붙입니다.
- 수익 보장, 원금 보장, 확실한 상승/하락, 매수/매도 지시형 표현을 금지합니다.
- 투자 관련 해석이 섞이면 아래 고지를 포함합니다.

```text
본 정보는 교육 목적의 정보 제공이며 투자자문·투자권유가 아닙니다.
투자 판단과 그 결과(손실 포함)에 대한 책임은 투자자 본인에게 있습니다.
```

## Verification Before Completion

작업 완료 전 변경 범위에 맞게 검증합니다.

문서만 바꿨으면 링크/문구 확인으로 충분합니다. 코드 또는 파이프라인을 바꿨으면 최소한 관련 CLI 도움말이나 단위 테스트를 실행합니다.

권장 전체 테스트:

```powershell
node --test --experimental-test-coverage --test-coverage-lines=90 `
  --test-coverage-include='src/common/**' `
  --test-coverage-include='src/dart/**' `
  --test-coverage-include='src/ecos/**' `
  --test-coverage-include='src/krx/**' `
  --test-coverage-include='src/mcp/**' `
  --test-coverage-include='src/naver/**' `
  --test-coverage-include='src/registry/**' `
  --test-coverage-include='src/bin/**' `
  --test-coverage-include='src/test/briefing-eval.mjs' `
  src/test/unit/*.test.mjs
```
