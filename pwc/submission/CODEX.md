# Trusted CEO Agent Pipeline

## Goal

이 프로젝트는 삼일PwC 컨설팅 시나리오에서 CEO에게 제공할 경영 리스크 브리핑을 만드는 플러그인입니다. 핵심 목표는 내부 재무 데이터, DART 공시, ECOS 거시지표, 네이버 뉴스, 사업자등록 확인을 결합해 정량/정성 근거가 있는 CEO 보고서를 만드는 것입니다.

## Recommended Pipeline

```text
spreadsheets
  -> trusted-ceo-agent
  -> compliance-review / compliance-language
  -> documents
```

| 단계 | 담당 | 역할 |
|---|---|---|
| 1. 입력 표준화 | `spreadsheets` | 고객이 제공한 Excel/CSV를 표준 CSV 3종으로 정리 |
| 2. 식별/분석 | `trusted-ceo-agent` | 사업자등록번호 게이트, DART/ECOS/뉴스/내부 CSV 분석 |
| 3. 컴플라이언스 검수 | `compliance-review`, `compliance-language` | 민감정보, 감사 추적성, 투자 권유성 표현, 단정 표현 점검 |
| 4. 최종 문서화 | `documents` | Markdown 브리핑을 Word 보고서(`.docx`)로 변환 |

## Required Inputs

분석은 최소한 아래 입력을 요구합니다.

```text
company_name        # 기업명
business_number    # 사업자등록번호
```

상장사 또는 외부 공시 분석을 더 정확히 하려면 아래 값도 함께 사용합니다.

```text
representative_name # 대표자명, 국세청 진위확인 선택 입력
opening_date        # 개업일자 YYYYMMDD, 국세청 진위확인 선택 입력
stock_code          # 종목코드
dart_corp_code      # DART 고유번호
```

내부 재무 데이터가 있으면 `spreadsheets`로 다음 표준 CSV를 준비합니다.

```text
trial_balance.csv
ar_aging.csv
cost_allocation.csv
```

기본 샘플 위치는 `src/data/sample/` 입니다. 실제 고객 데이터는 별도 폴더에 같은 파일명으로 두고 `--data-dir`로 넘깁니다.

## Executable Pipeline

파이프라인 CLI는 식별 게이트 → 진단 → 브리핑 생성 → 자동 채점을 한 명령으로 잇습니다.
에이전트 CLI(claude/codex)가 감지되면 브리핑까지 자동 생성·채점하고, 없으면(또는
`--agent none`) 프롬프트 파일과 수동 절차 안내로 폴백합니다.

```powershell
node src/bin/ceo-consulting-pipeline.mjs `
  --company "삼성전자" `
  --business-number "124-81-00998" `
  --data-dir "src/data/sample" `
  --out-dir "outputs/samsung-ceo-pipeline"
# 옵션: --agent none|"<cmd>" (에이전트 강제/비활성), --judge (LLM 인과 채점 추가)
```

생성 산출물:

| 파일 | 의미 |
|---|---|
| `identity.json` | 사업자등록번호 로컬 검증, 국세청 상태조회/진위확인 결과 |
| `diagnostic-packet.json` | DART/ECOS/내부 CSV 기반 진단 패킷 |
| `briefing.md` | 에이전트가 생성한 CEO 브리핑 — 규칙 채점 통과 여부가 exit code |
| `prompt.txt` | 브리핑 생성 프롬프트 (에이전트 미감지 시 수동 전달용) |
| `pipeline-next-steps.md` | 컴플라이언스 검수, `documents` DOCX 생성 등 후속 단계 안내 |

`company_identity_gate`가 실패하면 후속 분석을 중단합니다. 이 경우 실패 자체를 데이터 품질 또는 식별 리스크로 보고합니다.

## Stage Details

### 1. Spreadsheets

고객이 제공하는 원본 파일이 Excel이거나 비표준 CSV이면 먼저 `spreadsheets` 플러그인으로 정리합니다.

```text
고객 원본 Excel/CSV
  -> 컬럼명/단위/숫자 형식/빈 셀 점검
  -> trial_balance.csv
  -> ar_aging.csv
  -> cost_allocation.csv
```

이 단계는 데이터를 분석하지 않고, Trusted CEO Agent가 읽을 수 있는 입력 계약으로 맞추는 역할입니다.

### 2. Trusted CEO Agent

Trusted CEO Agent의 분석 순서는 다음과 같습니다.

```text
company_identity_gate
  -> verify-books
  -> detect-signals
  -> DART cross-check
  -> ECOS macro exposure
  -> Naver news external signal
  -> CEO briefing
```

주요 CLI:

```powershell
node src/bin/verify-books.mjs --data-dir "<데이터폴더>"
node src/bin/detect-signals.mjs --data-dir "<데이터폴더>" --json
node src/bin/diagnose-company.mjs --company "<기업명>" --data-dir "<데이터폴더>" --json
```

### 3. Compliance Review / Language

브리핑 확정 전 아래 관점을 검수합니다.

| 관점 | 기준 |
|---|---|
| 민감정보 | 계좌번호, 주민번호, 카드번호, 실명+연락처 조합 노출 금지 |
| 감사 추적성 | 수치별 출처, 파일/API, 기준일을 추적 가능하게 표시 |
| 표현 안전성 | 수익 보장, 원금 보장, 확실한 상승/하락, 매수/매도 지시 금지 |
| 경계 표시 | CEO 경영 판단 보조 자료이며 투자자문/투자권유가 아님을 명시 |

투자 관련 표현이 포함될 수 있는 경우 아래 고지를 유지합니다.

```text
본 정보는 교육 목적의 정보 제공이며 투자자문·투자권유가 아닙니다.
투자 판단과 그 결과(손실 포함)에 대한 책임은 투자자 본인에게 있습니다.
```

### 4. Documents

최종 제출본은 `documents` 플러그인으로 Word 문서로 만듭니다.

```text
briefing.md
  -> documents
  -> briefing.docx
```

권장 문서 구조:

```text
표지
요약 결론
핵심 리스크
근거 수치와 출처
확신도와 추가 확인 절차
권고 조치
부록: 데이터 출처, API 조회 기준, 한계
```

## Output Contract

| 산출물 | 용도 |
|---|---|
| `briefing.md` | 검토, 채점, 버전 관리용 원본 분석 결과 |
| `briefing.docx` | CEO/파트너/고객 제출용 Word 보고서 |
| `diagnostic-packet.json` | 브리핑 근거와 채점 정답지 |
| `identity.json` | 기업 식별 게이트 감사 증적 |

## Verification

브리핑 Markdown은 채점기로 검증합니다.

```powershell
node src/test/briefing-eval.mjs --signals-file "outputs/<기업>/diagnostic-packet.json" "outputs/<기업>/briefing.md"
```

검증 기준:

- PRESENT 신호를 근거 숫자와 함께 포착했는가
- ABSENT 신호를 리스크로 지어내지 않았는가
- 확신도와 추가 확인 절차가 있는가
- 단정적 표현과 투자 권유성 표현이 없는가
- 결론, 근거, 확신도, 권고 조치 구조를 갖췄는가

## Boundary

이 파이프라인은 CEO 경영 리스크 컨설팅용입니다. 주가 전망, 목표주가, 매수/매도 지시, 수익률 보장은 제공하지 않습니다. DART/ECOS/뉴스/API 데이터는 조회 시점 기준이며, 내부 CSV의 품질과 범위가 분석 신뢰도를 결정합니다.
