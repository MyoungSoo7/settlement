# Trusted CEO Agent — 삼일PwC 과제 제출물

기업의 최고경영책임자(CEO)가 놓치기 쉬운 회계·재무·거시 환경 리스크를
**데이터의 행간에서 먼저 찾아내고**, 그 근거와 확인 절차를 자연어 브리핑으로 제시하는
Codex/Claude 호환 플러그인입니다.

이 플러그인은 단순한 재무 대시보드가 아닙니다. CEO가 이미 알고 있는 숫자를 다시 보여주는 대신,
시산표·채권 aging·원가배분표·DART 공시·ECOS 경제지표를 교차 대조해
**"무엇을 먼저 확인해야 하는가"** 를 제안합니다.

두 가지 설계 전제가 있습니다.

- **사용자는 CEO 한 사람입니다.** 산출물은 실무 워킹페이퍼가 아니라 CEO가 읽고 결정하는
  서명용 브리핑 하나로 수렴합니다.
- **데모 검증기가 아니라 재사용 가능한 진단 프레임워크입니다.** 정답지(이상 신호·근거 수치)를
  코드에 하드코딩하지 않고 **분석 대상 데이터에서 매번 파생 계산**합니다. 동봉 샘플이 아닌
  어떤 회사의 데이터 폴더(`--data-dir`)를 지정해도 게이트 → 신호 파생 → 브리핑 → 자동 채점이
  같은 절차로 돌아갑니다.

## 두 단계 진단 모드 — 기업명만으로 시작, 내부 CSV 는 옵션

| 모드 | 입력 | 명령 | 판정 축 |
|---|---|---|---|
| **기본 (API-only)** | 기업명 또는 corp_code (코스피·코스닥 상장사) | `node src/bin/diagnose-company.mjs --company 삼성전자` | 외부 신호 E1~E5·E8 — 수익-채권 괴리 · 재고 적체 · 차입 확대·이자 부담 · 유동성 하락 · 공시 행간 · 발생액 품질(이익-현금 괴리) (+ ECOS 기준금리 컨텍스트) |
| **상세 (내부 CSV 옵션)** | + 시산표·aging·원가배분 CSV 폴더 | `… --data-dir <폴더> [--dart-unit-scale 1000000]` | + 불변식 게이트(INV-1~7) · 내부 신호 S1~S4(거래처 신용 집중·원가 배분 왜곡 포함) · 공시 대사(INV-8 자동 배선) |
| **시장 축 (옵션)** | + `KRX_API_KEY` (상장사) | `… --with-market` | + E6 시장·현금흐름 괴리(시총 급변 vs 영업현금흐름 방향 대사) · E7 공시·주가 정합(해명 공시 직전 주가·거래량 선행 변동 = 정보 관리 리스크). 주가를 밸류에이션이 아니라 **시장 장부 vs 회계 장부의 대사**로만 사용 — 투자 판단 아님 |

기본 모드는 DART 전체 재무제표(당기/전기/전전기 3개년)·최근 공시 목록·ECOS 금리처럼 **API 로
얻을 수 있는 자료만으로** 돌아갑니다 — API 키만 있으면 어떤 상장사든 설치 직후 바로 진단됩니다.
내부 CSV 를 `--data-dir` 로 제공하는 순간, 공시로는 볼 수 없는 상세 축(거래처별 신용 집중,
제품별 원가 배분 왜곡)과 내부↔공시 대사(INV-8)가 **같은 진단 패킷에 얹혀** 더 상세한
경영리스크 피드백이 됩니다. 진단 패킷(`--json`)은 `briefing-eval --signals-file` 의 채점
정답지로 그대로 사용됩니다.

## CEO에게 제공하는 기능

| 기능 | CEO가 얻는 답 |
|---|---|
| 회계처리 이상 탐지 | 매출과 이익은 좋아 보이는데 현금이 따라오지 않는 이유가 무엇인가 |
| 현금흐름 병목 분석 | 미수금 문제가 전사 문제인지, 특정 거래처 신용 리스크인지 |
| 원가 배분 왜곡 탐지 | 흑자로 보이는 제품·사업부가 실제 자원 사용량 기준으로도 이익인지 |
| DART 공시 기반 기업 확인 | 외부 공시 재무제표와 기업개황, 최근 공시에서 위험 신호가 있는지 |
| ECOS 거시지표 결합 | 금리·환율·물가 변화가 차입비용, 원가, 현금흐름에 어떤 부담을 주는지 |
| 불변식 선검증 게이트 | 브리핑의 모든 추론이 기계적으로 대사·검산이 끝난 장부 위에서 나왔다는 신뢰 |
| CEO 브리핑 생성 | 결론, 근거, 왜 문제인지, 확신도, 추가 확인 데이터, 권고 조치를 한 문서로 받음 |

최종 출력은 다음 형식을 따릅니다.

```text
리스크 제목

결론:
CEO가 먼저 알아야 할 한 문장

근거:
숫자와 출처 2~3개

왜 문제인가:
경영 의사결정 관점의 인과 설명

확신도:
확인됨 / 가능성 높음 / 추가 확인 필요

권고 조치:
담당 부서와 확인 절차
```

## 과제 요구사항 매핑

| 과제 요구 | 구현 위치 |
|---|---|
| 잘못된 회계 처리 탐지 | `src/skills/accounting-anomaly/SKILL.md` |
| 현금 흐름의 병목 탐지 | `src/skills/cashflow-bottleneck/SKILL.md` |
| 원가 배분 오류로 인한 보이지 않는 손실 탐지 | `src/skills/cost-allocation-audit/SKILL.md` |
| 거시 지표(금리·환율·물가) 노출 분석 | `src/skills/macro-exposure/SKILL.md` |
| 내부 장부 ↔ 외부 공시 대사 | `src/skills/disclosure-crosscheck/SKILL.md` |
| 뉴스·외부평판 신호 분석 | `src/skills/external-signal/SKILL.md` |
| 기업명·사업자등록번호 식별 게이트 | `src/skills/company-identity/SKILL.md` |
| 논리적 근거를 자연어로 제시 | `src/skills/ceo-briefing/SKILL.md` |
| 종합 오케스트레이션 | `src/skills/ceo-risk-recon/SKILL.md` |
| 추론 전 기계적 정합성 확정 (불변식 게이트) | `src/bin/verify-books.mjs` (+ `src/common/books.mjs`) |
| DART 공시 기반 재무 CSV 생성 | `src/bin/dart-to-csv.mjs` |
| 리스크 신호 파생 계산 (데이터 → 정답지) | `src/bin/detect-signals.mjs` (+ `src/common/signals.mjs`) |
| API-only 기업 진단 (외부 신호 E1~E5·E8) | `src/bin/diagnose-company.mjs` (+ `src/common/dart-signals.mjs`) |
| 재현율·표현 안전성 자동 채점 | `src/test/briefing-eval.mjs` (`--data-dir` / `--signals-file`) |
| 외부 공시 재무 데이터 연결 | `src/mcp/dart-server.mjs` |
| 거시 경제지표 연결 | `src/mcp/ecos-server.mjs` |
| 네이버 뉴스 검색 연결 | `src/mcp/news-server.mjs` |
| 국세청 사업자등록정보 연결 | `src/mcp/registry-server.mjs` |

## 작동 구조

```text
사용자 요청
  "우리 회사 데이터에서 CEO가 놓친 리스크를 찾아줘"
        |
        v
기업 식별 게이트 (company_identity_gate)
  - company_name + business_number 필수
  - 사업자번호 체크섬, 국세청 상태조회, 선택적 진위확인
  - 실패/휴폐업/미등록이면 후속 분석 중단 또는 식별 리스크 최상단 보고
        |
        v
불변식 게이트 (verify-books)
  - 시산표 파싱, aging 대사, 원가 배부 검산을 기계적으로 확정
  - GATE FAIL 이면 추론 진입 금지 — 실패 자체를 데이터 품질 리스크로 보고
        |
        v
신호 파생 (detect-signals)
  - 수익-현금 괴리 / 거래처 집중 / 원가 배분 왜곡 / 차입·금리 노출을
    임계값 기반으로 판정하고 근거 수치(증가율·집중도·재배부 손익)를 계산
  - PRESENT 신호만 리스크 후보 — absent 신호를 지어내면 채점기가 오탐으로 검출
        |
        v
ceo-risk-recon
  - 데이터 인벤토리 확인
  - 파일·도구별 grain 파악
  - 분석 스킬 실행 순서 결정
        |
        v
전문 스킬
  - accounting-anomaly
  - cashflow-bottleneck
  - cost-allocation-audit
  - macro-exposure (ECOS 결합)
  - disclosure-crosscheck (DART 대사)
        |
        v
외부 읽기 전용 MCP
  - NTS/data.go.kr: 사업자등록번호 형식·상태·진위확인
  - DART: 기업개황, 공시 목록, 재무제표
  - ECOS: 기준금리, 국고채3년, USD/KRW, CPI
  - Naver News: 기업 뉴스 제목, 요약, 링크, 발행일
        |
        v
교차 검증
  - 여러 데이터에서 같은 방향 신호가 나오는지 확인
  - 금액 영향, 확신도, 시급성으로 우선순위 산정
        |
        v
ceo-briefing
  - CEO 서명용 자연어 보고서 생성
```

핵심 원칙은 일곱 가지입니다.

0. **정답지 하드코딩 금지**: 신호 판정 기준과 근거 수치는 코드가 아니라 데이터에서 나옵니다. 에이전트와 채점기가 같은 파생 엔진(`src/common/signals.mjs`)을 공유하므로, 어떤 회사 데이터에도 동일한 기준이 적용됩니다.
1. **불변식 먼저**: 대사 일치·검산처럼 기계로 확정 가능한 정합성을 `verify-books` 게이트로 먼저 확정하고, AI 추론은 그 위에서만 시작합니다.
2. **식별 먼저**: 특정 기업 CEO 컨설팅은 `company_name` 과 `business_number` 없이는 시작하지 않습니다. 분석 대상이 틀리면 모든 후속 판단이 무의미하기 때문입니다.
3. **읽기 전용**: 내부 장부, DART, ECOS는 분석 입력으로만 사용합니다. 플러그인이 원천 데이터를 수정하지 않습니다.
4. **단정 금지**: "분식입니다"가 아니라 "수익 인식 시점 확인이 필요한 가능성"처럼 가설과 확인 절차를 분리합니다.
5. **교차 검증**: 단일 숫자로 판단하지 않고 손익, 채권, 현금흐름, 공시, 경제지표를 함께 봅니다.
6. **정성 신호 보조**: 뉴스와 보도자료는 결론이 아니라 확인할 신호로만 사용합니다. 기사 전문을 저장하지 않고 제목·요약·링크·발행일 메타데이터를 재무 데이터와 교차 검증합니다.

## 데이터 입출력 구조

### CEO 컨설팅 표준 입력값

특정 기업에 대해 CEO 컨설팅 브리핑을 만들 때는 먼저 분석 대상을 고정합니다.

필수 입력:

| 입력값 | 설명 |
|---|---|
| `company_name` | 분석 대상 기업명 |
| `business_number` | 사업자등록번호. 하이픈 포함/미포함 모두 가능 |
| `financial_data_path` 또는 `dart_corp_code`/`stock_code` | 내부 재무 CSV 경로 또는 DART 조회 식별자 |

선택 입력:

| 입력값 | 설명 |
|---|---|
| `representative_name` | 국세청 진위확인에 사용 |
| `opening_date` | 개업일자 YYYYMMDD. 국세청 진위확인에 사용 |
| `news_query_name` | 네이버 뉴스 검색에 사용할 브랜드명/약칭 |

표준 파이프라인:

```text
company_name + business_number 입력
        |
        v
company_identity_gate
  - 사업자번호 형식/체크섬 검증
  - 국세청 상태조회
  - 대표자명/개업일자가 있으면 진위확인
        |
        v
분석 대상 식별자 확정
  - DART: dart_corp_code 또는 stock_code/company_name
  - 뉴스: news_query_name 또는 company_name
  - 내부 재무 CSV: 해당 법인 데이터인지 확인
        |
        v
재무·공시·거시·뉴스 분석
```

`company_identity_gate` 결과가 `analysisAllowed=false` 이면 후속 분석보다 식별 리스크를 먼저 보고합니다.

### 내부 회계·재무 데이터 입력 위치

내부 데이터는 기본적으로 `src/data/sample/` 아래 CSV 파일로 둡니다. 데모와 불변식 게이트가 기대하는 기본 파일명은 다음 3개입니다.

```text
src/data/sample/
|-- trial_balance.csv      # 분기별 시산표·손익·현금흐름·차입금·이자비용
|-- ar_aging.csv           # 분기별·거래처별 매출채권 aging
`-- cost_allocation.csv    # 제품별 매출·직접원가·공통원가 배부·기계시간 비중
```

각 파일의 의미는 다음과 같습니다.

| 파일 | 플러그인이 읽는 내용 | 주로 쓰는 스킬 |
|---|---|---|
| `trial_balance.csv` | 매출, 매출채권, 재고, 계약부채, 영업이익, 영업현금흐름, 변동금리 차입금, 이자비용 | `accounting-anomaly`, `cashflow-bottleneck`, `macro-exposure` |
| `ar_aging.csv` | 거래처별 current / 31-60일 / 61-90일 / 90일 초과 채권 | `cashflow-bottleneck` |
| `cost_allocation.csv` | 제품별 매출, 직접원가, 공통원가 배부액, 매출 기준 배부율, 실제 기계시간 비중 | `cost-allocation-audit` |

샘플이 아닌 실제 회사 데이터도 같은 3개 파일명으로 폴더에 넣으면 바로 사용할 수 있습니다.
컬럼 헤더는 영어 canonical 이름 외에 **한국어 별칭도 인식**합니다
(예: `분기/매출/매출채권/계약부채/영업현금흐름/거래처/90일초과/직접원가/기계시간비중` — 전체 별칭은 `src/common/books.mjs`).
따옴표 필드·천단위 콤마·회계 괄호 표기 `(1,000)`·BOM 이 있는 엑셀 내보내기 CSV 도 파싱됩니다.

### 고객 제공 Excel/CSV 전처리 권장 플러그인

고객이 내부 재무 데이터를 Excel 또는 비표준 CSV로 제공하면, Codex의 `spreadsheets` 플러그인을 먼저 사용해 표준 입력 파일로 정리하는 흐름을 권장합니다. 분석이 끝나면 CEO/파트너 제출용 Word 보고서(`.docx`)는 **플러그인 내장 렌더러가 자동 생성**합니다 (표지·핵심 리스크 요약표·확신도 배지·면책 푸터, UTF-8/한글 폰트 보장 — zero-dependency).

```text
고객 원본 파일
  - trial balance.xlsx
  - 매출채권 aging.xlsx
  - 원가배분표.xlsx
  - 여러 시트가 섞인 CSV/엑셀
        |
        v
spreadsheets 플러그인
  - 시트 구조 확인
  - 헤더/단위/빈 셀/천단위 콤마/회계 괄호 표기 점검
  - 필요한 컬럼만 표준화
        |
        v
Trusted CEO Agent 표준 입력
  - trial_balance.csv
  - ar_aging.csv
  - cost_allocation.csv
        |
        v
verify-books → detect-signals → CEO briefing → Markdown/DOCX report
```

역할 분담은 다음과 같습니다.

| 단계 | 담당 | 목적 |
|---|---|---|
| 고객 원본 Excel/CSV 확인 | `spreadsheets` | 시트 구조, 컬럼명, 단위, 숫자 형식 점검 |
| 표준 CSV 3종 생성 | `spreadsheets` | 이 플러그인이 읽는 입력 계약으로 변환 |
| 정합성 검증 | Trusted CEO Agent | `verify-books` 불변식 게이트 |
| 리스크 분석 | Trusted CEO Agent | 내부 데이터 + DART/ECOS/뉴스/국세청 교차 검증 |
| Word 보고서 생성 | Trusted CEO Agent (내장 `render-briefing-docx`) | `briefing.md` + `diagnostic-packet.json` → 표지·요약표·스냅샷 PNG·서식을 갖춘 `briefing.docx` 자동 렌더 (브랜드 템플릿이 필요하면 `documents` 플러그인으로 추가 변환 가능) |

즉 `spreadsheets` 는 **입력 데이터 정제/표준화 담당**, Trusted CEO Agent 는 **검증/분석/브리핑/Word 산출 담당**입니다.

### 다른 데이터 폴더를 쓰는 방법 (실제 회사 데이터)

CSV 3개가 들어 있는 폴더를 `--data-dir` 로 지정하면 됩니다. 게이트·신호 파생·채점 모두 같은 플래그를 받습니다.

```powershell
node src/bin/verify-books.mjs   --data-dir "C:\path\to\company-data"   # 1) 정합성 게이트
node src/bin/detect-signals.mjs --data-dir "C:\path\to\company-data"   # 2) 신호 파생 (근거 수치 계산)
node src/test/briefing-eval.mjs --data-dir "C:\path\to\company-data" briefing.md   # 3) 브리핑 자동 채점
```

(환경변수 `VERIFY_BOOKS_DATA_DIR` 도 계속 지원하며, `--data-dir` 플래그가 우선합니다.)

### DART에서 공시 기반 CSV를 생성하는 방법

상장사는 DART 재무제표 API로 공시 기반 요약 CSV를 만들 수 있습니다.

```powershell
node src/bin/dart-to-csv.mjs `
  --corp-code 00164779 `
  --stock-code 000660 `
  --company-name SK하이닉스 `
  --years 2025,2026 `
  --reports 11011,11013 `
  --out-dir src/data/generated/sk-hynix
```

생성 파일:

```text
src/data/generated/sk-hynix/trial_balance_public.csv
```

이 파일은 DART 공시 기반 요약 재무 데이터입니다. 내부 장부의 `trial_balance.csv` 를 완전히 대체하지는 않습니다.

| 가능 | 불가능 |
|---|---|
| 매출, 영업이익, 순이익, 자산, 부채, 자본 추세 분석 | 거래처별 매출채권 aging |
| DART 공시 이벤트와 재무 추세 교차 검증 | 제품별 원가 배분 왜곡 |
| ECOS 금리·환율·물가와 공시 재무의 거시 노출 가설 | 내부 원장/전표 수준 break 분석 |

따라서 `trial_balance_public.csv` 는 상장사 예비 분석과 CEO 브리핑의 정량 근거로 쓰고, `ar_aging.csv` 와 `cost_allocation.csv` 는 내부 자료가 있을 때 별도로 넣습니다.

신호 판정 임계값을 회사 규모·업종에 맞게 조정하려면 데이터 폴더에 `analysis-config.json` 을 둡니다.
분석 대상이 **코스피·코스닥 상장사**면 `crosscheck` 섹션으로 INV-8 외부 대사(아래)를 상시 켤 수 있습니다.

```json
{
  "thresholds": {
    "arGrowthGapPp": 15,
    "concentrationSharePct": 60,
    "allocationGapPp": 15,
    "debtGrowthPct": 50,
    "coverageFloor": 10
  },
  "crosscheck": {
    "corpCode": "00126380",
    "fsDiv": "OFS",
    "unitScale": 1000000,
    "tolerancePct": 1
  }
}
```

### 상장사 외부 대사 — INV-8 (코스피·코스닥 전제 시 정합성 보강)

불변식 게이트 7종은 내부 장부의 **내부 일관성**만 확정합니다. 분석 대상이 DART 공시 법인이면
INV-8 이 **내부 시산표 연간 합계(매출·영업이익) ↔ 감사받은 사업보고서**를 대조해,
"내부적으로는 일관되게 틀린 데이터"를 잡는 두 번째 방어선을 추가합니다.

```powershell
node src/bin/verify-books.mjs --data-dir <폴더> --dart-corp-code 00126380 --dart-unit-scale 1000000
# (--dart-year 2025 / --dart-fs-div OFS|CFS / --dart-tolerance-pct 1 선택)
```

- 내부 장부는 개별 법인 장부이므로 기본 비교 대상은 **별도(OFS)** 재무제표입니다 (연결 CFS 와 혼동 금지).
- 연간 **감사 수치(사업보고서)**와만 대조합니다 — 분기보고서(검토)·잠정실적(감사 전)은 앵커 강도가 달라 자동 대사에 쓰지 않습니다.
- `unitScale` 로 내부 장부 단위를 명시합니다 (백만원 단위 장부 → 1000000).
- 내부 데이터에 완결 회계연도(Q1~Q4)가 없으면 판정을 생략(skip)하고, 내부 불변식(INV-1~7)이 FAIL 이면 실행하지 않습니다.
- corp_code 미설정(비상장 고객사)이면 INV-8 자체가 나타나지 않습니다 — 범용성은 그대로입니다.

`ceo-risk-recon`에 분석을 요청할 때도 같은 폴더를 명시하면 됩니다.

```text
C:\path\to\company-data 의 trial_balance.csv, ar_aging.csv, cost_allocation.csv 를 기준으로
CEO가 놓치고 있는 회계·현금흐름·원가 리스크를 찾아서 브리핑으로 정리해줘.
```

### 외부 데이터 입력 위치

DART, ECOS, 네이버 뉴스 데이터는 로컬 CSV로 저장하지 않고 MCP 도구가 읽기 전용으로 조회합니다.

| 외부 데이터 | 입력 방식 | 설정 |
|---|---|---|
| 국세청 사업자등록정보 | `src/mcp/registry-server.mjs` MCP 도구가 API 조회 | `DATA_GO_KR_API_KEY` |
| DART 공시·재무제표 | `src/mcp/dart-server.mjs` MCP 도구가 API 조회 | `DART_API_KEY` |
| ECOS 금리·환율·물가 | `src/mcp/ecos-server.mjs` MCP 도구가 API 조회 | `ECOS_API_KEY` |
| 네이버 뉴스 | `src/mcp/news-server.mjs` MCP 도구가 API 조회 | `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` |

즉 내부 장부는 `src/data/sample/` 또는 사용자가 지정한 CSV 폴더에 두고, 외부 공시·거시·뉴스 데이터는 API 키를 통해 MCP가 가져오는 구조입니다.

키는 [`.env.example`](./.env.example) 을 `.env` 로 복사해 한 곳에서 관리할 수 있습니다 —
모든 CLI/MCP 가 상위 폴더의 `.env` 를 자동 탐색하며, 키가 없는 축은 그 축만 생략되고
나머지 진단은 그대로 동작합니다 (LLM Judge 키가 없으면 규칙 채점만 수행).

### 공공데이터포털(data.go.kr)에서 활용신청해야 하는 서비스

공공데이터포털 인증키는 **계정당 하나지만, API 마다 개별 "활용신청"이 필요**합니다.
신청이 안 된 API 를 호출하면 XML 오류가 아니라 게이트웨이가 **맨몸 `403 Forbidden`** 을
돌려주므로(디버깅 함정), 아래 서비스들을 미리 신청해 두십시오. 둘 다 자동승인이며,
승인·키 발급 후 게이트웨이 반영까지 **최대 1시간** 걸릴 수 있습니다.

| 검색해서 신청할 서비스명 | 용도 | env 키 | 호출 엔드포인트 |
|---|---|---|---|
| **국세청_사업자등록정보 진위확인 및 상태조회 서비스** | 기업 식별 게이트 (`company_identity_gate` — 상태조회/진위확인) | `DATA_GO_KR_API_KEY` | `api.odcloud.kr/api/nts-businessman/v1` |
| **금융위원회_주식시세정보** | 시장 축 E6·E7 (`--with-market` — 시총·주가·거래량 대사) | `KRX_API_KEY` (비우면 `DATA_GO_KR_API_KEY` 폴백) | `apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService` |

- 키는 마이페이지 → 오픈API → 해당 신청 상세의 **일반 인증키 (Decoding)** 값을 사용합니다.
- 신청·키가 유효한지 확실히 확인하려면 같은 상세 페이지의 **미리보기(테스트) 버튼**으로
  웹에서 먼저 호출해 보십시오 — 거기서 성공하는데 CLI 가 403 이면 반영 지연입니다.
- 참고로 DART(`opendart.fss.or.kr`)·ECOS(`ecos.bok.or.kr`)·네이버(`developers.naver.com`)는
  공공데이터포털이 아니라 각자의 포털에서 키를 발급받습니다.

### 출력 위치와 노출 방식

분석 결과는 기본적으로 Codex/Claude 대화 응답으로 노출됩니다. 파일로 남기고 싶으면 검토/채점용 Markdown 파일과 제출/공유용 Word 파일을 분리해 생성하도록 요청합니다.

```text
분석 결과를 briefing.md 파일로 저장해줘.
node src/bin/render-briefing-docx.mjs briefing.md   # Word 보고서 생성 (통합 파이프라인은 자동)
```

| 산출물 | 용도 | 생성 주체 |
|---|---|---|
| `briefing.md` | Codex/Claude 대화에서 검토하기 쉬운 원본 분석 결과, 평가·수정·버전 관리용 | Trusted CEO Agent |
| `executive-summary.png` | 진단 패킷의 DART/뉴스/거시 신호를 한 장으로 요약한 CEO용 시각 스냅샷, DOCX에 자동 삽입 | `render-executive-snapshot.py` |
| `briefing.docx` | CEO/파트너/고객에게 전달하기 쉬운 Word 형식 최종 보고서 (표지·핵심 리스크 요약표·스냅샷 PNG·확신도 배지·면책 푸터) | 내장 렌더러 `render-briefing-docx` (zero-dependency, UTF-8/한글 폰트 보장) |

생성된 브리핑은 다음 명령으로 자동 채점할 수 있습니다. 채점 기준(신호·마커)은 채점 시점에
`--data-dir` 의 데이터에서 파생됩니다 — 브리핑이 근거로 삼은 바로 그 데이터가 정답지입니다.

```powershell
node src/test/briefing-eval.mjs --data-dir <데이터폴더> briefing.md
```

## CEO 관점 사용 예시

### 0. 설치 확인 — doctor 한 명령 (네트워크 0)

설치 직후(또는 심사 시작 전) 이 한 명령으로 "지금 무엇이 되고, 키가 없으면 무엇이 빠지는지"를
30초 안에 확인합니다. Node 버전 → 오프라인 셀프테스트(동봉 샘플 게이트+신호) → MCP 배선 →
에이전트 CLI 감지 → API 키 축별 상태 → 지금 바로 실행 가능한 다음 명령까지 안내합니다.

```powershell
node src/bin/doctor.mjs          # 사람용 리포트 (키가 없어도 어디까지 동작하는지 명시)
node src/bin/doctor.mjs --json   # 기계용 (CI/스크립트)
```

### 0-a. 컨설팅 전 과정을 한 명령으로 (통합 파이프라인)

기업명과 사업자등록번호만 주면 식별 게이트 → 진단 → 브리핑 생성 → 자동 채점까지
한 명령으로 완주합니다. 에이전트 CLI(claude/codex)가 설치되어 있으면 브리핑을 자동
생성·채점하고, 없으면 프롬프트 파일과 수동 절차 안내(`pipeline-next-steps.md`)를 남깁니다.

```powershell
node src/bin/ceo-consulting-pipeline.mjs --company 삼성전자 --business-number 124-81-00998
# 옵션: --data-dir <내부CSV> --judge (LLM 인과 채점) --agent none (에이전트 없이 준비만)
```

라이브 실행 산출물 두 벌이 동봉되어 있으며, 각각 채점 명령 한 줄로 지금 바로 재검증할 수 있습니다.

| 예시 | 모드 | 결과 |
|---|---|---|
| [`outputs/samsung-ct-ceo-pipeline/`](./outputs/samsung-ct-ceo-pipeline/README.md) | 실기업(삼성물산) API-only — identity → 패킷 → 브리핑 → Word | 재현율 1/1 · EVAL PASS |
| [`outputs/sample-internal-e2e/`](./outputs/sample-internal-e2e/README.md) | 상세 모드(내부 CSV S1~S4) 원커맨드 E2E — claude 라이브 생성 | 재현율 4/4 · EVAL PASS · Judge 전 신호 2/2/2 "우수" |

### 0-b. 기업명만으로 시작 (기본 모드 — 내부 데이터 불필요)

```powershell
node src/bin/diagnose-company.mjs --company 삼성전자                      # 사람용 진단
node src/bin/diagnose-company.mjs --company 삼성전자 --json > packet.json  # 브리핑 채점용 정답지
node src/test/briefing-eval.mjs --signals-file packet.json briefing.md    # 패킷 기준 채점
```

내부 CSV 가 준비되면 같은 명령에 `--data-dir` 만 붙입니다 (상세 모드 — 내부 신호 S1~S4 와
INV-8 공시 대사가 같은 패킷에 얹힙니다).

```powershell
node src/bin/diagnose-company.mjs --company 삼성전자 --data-dir C:\path\to\company-data --dart-unit-scale 1000000
```

### 1. 분기 마감 직후 리스크 점검

```text
company_name: 한빛커머스
business_number: 124-81-00998
financial_data_path: src/data/sample

고객 원본이 Excel이면 먼저 spreadsheets 플러그인으로
trial_balance.csv, ar_aging.csv, cost_allocation.csv 3개 파일로 정리해줘.

먼저 company_identity_gate 로 기업 식별을 확인한 뒤,
src/data/sample 데이터를 기준으로 CEO가 놓치고 있는 회계·현금흐름·원가 리스크를 찾아줘.
근거 숫자와 확인 절차까지 CEO 브리핑 형식으로 정리해줘.
검토용 briefing.md와 제출용 briefing.docx를 함께 만들어줘.
```

### 2. 특정 기업 외부 공시 확인

```text
DART에서 삼성전자를 찾아 최근 공시와 연간 재무제표를 확인하고,
CEO 관점에서 재무 안정성·수익성·공시 리스크를 요약해줘.
```

### 3. 거시 환경까지 포함한 경영 브리핑

```text
ECOS 기준금리, 국고채3년, USD/KRW, CPI 흐름을 확인하고,
금리·환율·물가 변화가 우리 회사 현금흐름과 원가에 줄 수 있는 부담을 설명해줘.
```

## 데모 데이터에 심어둔 이상 신호

샘플 데이터는 단일 파일만 보면 정상처럼 보이지만, 파일 간 교차 대조를 해야 리스크가 드러나도록 설계했습니다.
아래 표의 수치는 문서용 참고값일 뿐 채점기의 정답지가 아닙니다 — 채점기·에이전트 모두
`detect-signals` 파생 엔진이 샘플 CSV 에서 매번 다시 계산한 값을 사용합니다.

| # | 신호 | 데이터 근거 | 기대 추론 |
|---|---|---|---|
| 1 | 수익 조기 인식 의심 | 매출 +11.3%(2026Q1→2026Q2)인데 매출채권 +46.9%, 계약부채 340→180 감소, 영업현금흐름 880→-310 | "팔았다고 기록했지만 현금이 들어오지 않는 매출" 가능성. cut-off, 계약 진행률, 기말 전후 전표 확인 권고 |
| 2 | 특정 거래처 신용 리스크 | 90일 초과 채권 1,920 중 에이프릴리테일 1,750, 비중 91.1% | 전사 회수 프로세스 문제가 아니라 특정 거래처 한 곳의 여신·담보·회수 계획 문제일 가능성 |
| 3 | 원가 배분 왜곡 | 제품C는 매출비중 15%로 공통원가 450만 배부받지만 실제 기계시간은 45% 사용 | 현재 흑자 +20이 실제 기계시간 기준 재배부 시 -880 적자로 전환. 가격·생산·철수 검토 필요 |
| 4 | 차입 의존 성장·금리 노출 | 변동금리 차입 6,000→14,500(4분기 2.4배), 이자비용 90→320, 이자보상배율 23.9→8.9배 하락 | 영업현금흐름 악화(신호 1)를 차입으로 메우는 구조. 금리 상승 구간에서 이자 부담 가속 — 고정금리 전환·헤지 여부 확인 권고 (ECOS 기준금리 추세와 결합) |

## 플러그인 구조

```text
submission/
|-- README.md
|-- question5.md
|-- document/
|   |-- question5.md
|   `-- poc-trusted-ceo-accounting-agent.md
|-- src/
|   |-- .codex-plugin/plugin.json
|   |-- .claude-plugin/plugin.json
|   |-- .mcp.json
|   |-- README-src.md
|   |-- common/
|   |   |-- csv.mjs                 # 견고한 CSV 파서 (따옴표·BOM·회계 표기)
|   |   |-- books.mjs               # 장부 로더(컬럼 별칭) + 불변식 7종 엔진
|   |   |-- signals.mjs             # 신호 파생 엔진 (데이터 → 정답지, 임계값 설정 가능)
|   |   `-- env.mjs
|   |-- bin/
|   |   |-- verify-books.mjs        # 불변식 게이트 CLI (추론 전 기계 검증)
|   |   |-- detect-signals.mjs      # 신호 파생 CLI (근거 수치 계산 — 에이전트/채점기 공용 엔진)
|   |   |-- run-sample.ps1
|   |   |-- dart-cli.mjs
|   |   `-- dart-to-csv.mjs       # DART 공시 기반 trial_balance_public.csv 생성
|   |-- skills/
|   |   |-- ceo-risk-recon/SKILL.md
|   |   |-- company-identity/SKILL.md
|   |   |-- accounting-anomaly/SKILL.md
|   |   |-- cashflow-bottleneck/SKILL.md
|   |   |-- cost-allocation-audit/SKILL.md
|   |   |-- macro-exposure/SKILL.md
|   |   |-- disclosure-crosscheck/SKILL.md
|   |   |-- external-signal/SKILL.md
|   |   `-- ceo-briefing/SKILL.md
|   |-- data/sample/                # 데모 데이터 (이상 신호 4종)
|   |   |-- trial_balance.csv
|   |   |-- ar_aging.csv
|   |   `-- cost_allocation.csv
|   |-- data/fixtures/clean/        # 음성 픽스처 (이상 없는 건강한 회사)
|   |-- dart/
|   |   |-- client.mjs
|   |   `-- corp-codes.mjs
|   |-- ecos/
|   |   `-- client.mjs
|   |-- naver/
|   |   `-- client.mjs
|   |-- registry/
|   |   `-- client.mjs
|   |-- mcp/
|   |   |-- dart-server.mjs
|   |   |-- ecos-server.mjs
|   |   |-- news-server.mjs
|   |   `-- registry-server.mjs
|   `-- test/
|       |-- dart-smoke.mjs
|       |-- ecos-smoke.mjs
|       `-- briefing-eval.mjs       # 재현율·표현 안전성 자동 채점
`-- logs/
```

## Codex 설치 — 실검증 완료

이 submission 폴더 자체가 마켓플레이스입니다 (`.agents/plugins/marketplace.json` 내장):

```bash
codex plugin marketplace add <이 submission 폴더 경로 또는 repo>
codex plugin add trusted-ceo-agent@trusted-ceo-agent-market
```

이후 2개 파일만 복사하면 MCP 4종(dart/ecos/news/registry)까지 Codex 세션에 뜹니다:

1. **MCP 도구 승인**: `docs/codex-config-snippet.toml` 내용을 `~/.codex/config.toml` 끝에
   붙여넣기 — 플러그인 id 의 `@마켓플레이스명` 은 설치한 이름에 맞춥니다. 모든 도구가
   읽기 전용 조회라 approve 가 안전하며, 없으면 비대화 모드에서 MCP 호출이 자동
   취소됩니다 (실측).
2. **API 키**: `.env.example` 을 `~/.codex/.env` 로 복사해 값 채우기 — Codex 플러그인
   MCP 서버는 셸 env 를 상속하지 않으므로 이 위치가 정답입니다 (실측).

> `.mcp.json` 은 Codex 실측 규격(`"cwd": "."` + 상대경로 args)을 따릅니다 —
> `${CLAUDE_PLUGIN_ROOT}` 치환은 codex-cli 0.142.5 에서 동작하지 않음을 실측 확인.
> Codex 실세션 E2E 에서 4개 서버 status 도구 실호출까지 검증했습니다.

설치 직후 다음 두 명령이 그대로 돌면 정상입니다 (플러그인 캐시 루트 기준 경로 —
캐시 위치는 `codex plugin list` 로 확인).

```bash
node <플러그인캐시루트>/bin/verify-books.mjs     # GATE PASS
node <플러그인캐시루트>/bin/detect-signals.mjs   # PRESENT 4건 (동봉 데모)
```

CEO 데이터로 쓸 때는 두 명령에 `--data-dir <회사데이터폴더>` 만 붙입니다. 스킬(`ceo-risk-recon`)도
같은 규칙으로 플러그인 루트의 `bin/` 스크립트를 호출하도록 작성되어 있어 설치 후 바로 동작합니다.

## Claude Code에서 사용

Claude Code는 `.claude-plugin/plugin.json` 이 포함된 `src/` 전체를 플러그인으로 설치하는 것이
표준입니다 (스킬이 `${CLAUDE_PLUGIN_ROOT}/bin` 스크립트를 호출하므로 스킬만 복사하면 안 됩니다).
저장소에서 바로 쓸 때는 스킬 디렉터리에 복사해도 되지만, 이때 스크립트 경로는 `src/bin/...` 을 사용합니다.

```powershell
New-Item -ItemType Directory -Force .claude\skills | Out-Null
Copy-Item -Recurse -Force src\skills\* .claude\skills\
```

Claude에서 다음처럼 요청하면 됩니다.

```text
ceo-risk-recon 스킬을 사용해서 src/data/sample 데이터를 분석해줘.
CEO가 놓친 리스크를 찾고, ceo-briefing 형식으로 정리해줘.
```

## 사업자등록 / DART / ECOS / 뉴스 MCP 도구

이 플러그인은 실제 기업 분석으로 확장하기 위해 읽기 전용 MCP 서버를 포함합니다.

### 사업자등록 MCP

도구:

- `business_number_validate`: 사업자등록번호 10자리 형식과 체크섬 로컬 검증
- `business_status_check`: 국세청 사업자등록 상태조회
- `business_auth_check`: 국세청 사업자등록 진위확인
- `company_identity_gate`: 기업명 + 사업자등록번호 + DART/뉴스 식별자 분석 게이트
- `registry_status`: API 키 설정 상태 확인

환경변수:

```powershell
$env:DATA_GO_KR_API_KEY="발급받은_DATA_GO_KR_API_KEY"
```

스모크 테스트:

```powershell
node src/test/registry-smoke.mjs
```

분석 전에 `company_identity_gate` 를 먼저 호출해 기업명과 사업자등록번호를 확인합니다. 체크섬 실패, 휴업/폐업/미등록, 진위확인 불일치가 있으면 재무·뉴스 분석보다 식별 리스크를 먼저 보고합니다.

### DART MCP

도구:

- `dart_corp_search`: 기업명, 종목코드, 고유번호로 DART `corp_code` 검색
- `dart_company`: 기업개황 조회
- `dart_disclosures`: 최근 공시 목록 조회
- `dart_financial_summary`: 주요 재무계정 요약
- `dart_financial_full`: 전체 재무제표 조회
- `dart_status`: API 키와 캐시 상태 확인

환경변수:

```powershell
$env:DART_API_KEY="발급받은_DART_키"
```

스모크 테스트:

```powershell
node src/test/dart-smoke.mjs
```

### ECOS MCP

도구:

- `ecos_indicator`: 기준금리, 국고채3년, USD/KRW, CPI 핵심 지표 조회
- `ecos_series`: 임의 ECOS 통계 원시 조회
- `ecos_key_stats`: 100대 통계지표 스냅숏
- `ecos_status`: API 키와 지표 카탈로그 상태 확인

환경변수:

```powershell
$env:ECOS_API_KEY="발급받은_ECOS_키"
```

스모크 테스트:

```powershell
node src/test/ecos-smoke.mjs
```

API 키가 없으면 MCP 왕복과 도구 등록만 검증하고, 라이브 조회는 생략합니다.

### 네이버 뉴스 MCP

도구:

- `news_search_company`: 기업명 기준 최근 뉴스 검색
- `news_search_risk`: 기업명 + 리스크 키워드 검색
- `news_status`: API 키 설정 상태 확인

환경변수:

```powershell
$env:NAVER_CLIENT_ID="발급받은_NAVER_CLIENT_ID"
$env:NAVER_CLIENT_SECRET="발급받은_NAVER_CLIENT_SECRET"
```

스모크 테스트:

```powershell
node src/test/news-smoke.mjs
```

네이버 뉴스 검색은 기사 본문 전문을 저장하지 않고, 공식 API가 반환하는 제목·요약·링크·발행일 메타데이터만 사용합니다. 브리핑에서는 "뉴스 신호상 확인 필요"로 표현하고, 정량 재무 데이터·DART·ECOS 결과와 교차 검증합니다.

## 데모 실행

```powershell
powershell -ExecutionPolicy Bypass -File src/bin/run-sample.ps1
```

추천 프롬프트:

```text
src/data/sample 의 데이터에서 CEO가 놓치고 있는 리스크를 추출하고,
근거를 설명한 뒤 서명용 브리핑으로 정리해줘.
```

기대 결과:

- 수익 조기 인식 가능성
- 특정 거래처 신용 리스크
- 원가 배분 왜곡으로 인한 제품C의 보이지 않는 손실
- 차입 의존 성장과 금리 노출 (이자보상배율 하락)

위 네 신호를 모두 포착하고, 각 신호를 **결론이 아니라 확인 가능한 가설**로 제시해야 합니다.

생성된 브리핑은 자동 채점할 수 있습니다:

```powershell
node src/bin/verify-books.mjs                        # 1) 불변식 게이트 (데이터 정합성)
node src/bin/detect-signals.mjs                      # 2) 신호 파생 (에이전트가 인용할 근거 수치)
node src/test/briefing-eval.mjs briefing.md          # 3) normal: 파생 신호 재현율 + 근접성 + 확신도/판별 + 과잉확신
node src/test/briefing-eval.mjs --clean briefing.md  #    clean(음성): 이상 없는 데이터에 리스크를 지어내지 않았는가
node src/test/briefing-eval.mjs --self-test          #    채점기 자체 회귀 테스트
```

임의 회사 데이터는 세 명령 모두에 `--data-dir <폴더>` 를 붙이면 됩니다 — 정답지도 그 폴더에서 파생됩니다.

채점기는 "정답 숫자가 문서 어딘가에 있는가"가 아니라 **추론이 성립했는가**를 봅니다. 특히 한 신호의 마커 2개 이상이 **같은 섹션 안**에서 함께 나와야 포착으로 인정하므로(근접성), 숫자만 흩뿌려서는 통과하지 못합니다.

**음성(clean) 검증**: `src/data/fixtures/clean/` 은 불변식 게이트는 GATE PASS 하지만 심어둔 이상 신호가 없는 데이터입니다. 이 데이터 기준 브리핑을 `--clean` 으로 채점하면 에이전트가 **없는 리스크를 지어내는지(오탐)** 를 검출합니다 — 재현율만으로는 못 보는 정밀도 축입니다.

```powershell
node src/bin/verify-books.mjs   --data-dir src/data/fixtures/clean   # GATE PASS (건강한 회사)
node src/bin/detect-signals.mjs --data-dir src/data/fixtures/clean   # 판정 신호: 0건
```

채점기는 데이터에 PRESENT 신호가 0건이면 normal 모드에서도 자동으로 음성 채점으로 전환됩니다 —
"이상 없음"을 확인 범위와 함께 보고한 브리핑만 PASS 합니다.

## 검증 기준

| 기준 | 확인 방법 |
|---|---|
| 데이터 정합성 | `verify-books.mjs` 불변식 게이트 7종(aging 대사·원가 검산·비중 합 100% 등)이 GATE PASS 인가 |
| 외부 정합성 (상장사) | INV-8: 내부 시산표 연간 합계가 DART 감사 공시(별도 OFS)와 허용오차 내 일치하는가 — 삼성전자 2025 실공시로 라이브 검증(일치 0% PASS / 매출 3조 부풀림 1.26% FAIL) |
| API-only 진단 | 내부 CSV 없이 외부 신호 E1~E5·E8 이 3개년 공시·공시 목록에서 파생되는가 — 삼성전자 라이브(E5 만 PRESENT, 건전 지표는 근거 수치와 함께 absent), 스트레스/건전 픽스처 회귀(`dart-signals.test.mjs`) |
| 일반화(재사용성) | 임의 데이터 폴더에 `--data-dir` 만 바꿔 지정해도 게이트·신호 파생·채점이 같은 절차로 도는가 — 특정 분기/기업 하드코딩 0 (`engine.test.mjs` 가 회귀 검증) |
| 재현율(+근접성) | 데이터에서 파생된 PRESENT 신호를 모두 포착하되, 신호별 마커 2개 이상이 같은 섹션에서 수렴하는가 — `briefing-eval.mjs` |
| 정밀도(오탐·과잉 확신) | absent 신호를 리스크로 주장하거나, 근거 마커 0개인데 "확인됨"으로 단정한 리스크가 없는가 — `briefing-eval.mjs` |
| 음성(clean) | 이상 없는 데이터(`fixtures/clean/`, 파생 신호 0건)에 리스크를 지어내지 않는가 — 자동 음성 전환 채점 |
| 확신도 보정 | 포착한 신호마다 확신도 태그(확인됨/가설)를 달았는가 — `briefing-eval.mjs` |
| 판별 테스트 | 포착한 신호마다 확인해야 할 데이터/절차를 명시했는가 — `briefing-eval.mjs` |
| 근거 정합성 | 브리핑 수치가 `detect-signals` 파생 수치(샘플 기준 11.3%, 46.9%, 91.1%, -880, 이자보상 8.9배)와 일치하는가 — 파생 엔진 계산은 단위 테스트가 손계산과 대조 |
| 표현 안전성 | 단정 대신 가설, 확신도, 추가 확인 절차로 분리하는가 — `briefing-eval.mjs` 가 단정 표현을 기계 검출 |
| 도구 연결성 | `dart-smoke.mjs`, `ecos-smoke.mjs`에서 MCP initialize/tools/list/status가 통과하는가 |
| CEO 적합성 | CEO가 읽어도 "무엇을 결정해야 하는지"가 보이는가 — 사용자는 CEO 한 사람 |
| **실사례 백테스트** | 신호 정의가 실세계 위기를 사전 포착하는가 — 태영건설 워크아웃(2023-12)을 **사건 9개월 전 공시(FY2022)만으로** 진단해 E1(채권 +73.5% vs 매출 −5.3%)·E2·E4 동시 발화 실측. 건전 코호트 15사에서 재무신호 3종 동시 발화 0건 — [`outputs/taeyoung-backtest-2022/`](./outputs/taeyoung-backtest-2022/README.md) |
| 이익 품질(발생액) | E8: 총자산 대비 발생액(순이익−영업CF) 비율·지속 괴리를 파생 — Sloan(1996) 발생액 문헌 기반 임계값, 건전 코호트 발화율 6.7% 실측(`bin/calibrate.mjs`) |

## 관련 문서

- [`question5.md`](document/question5.md): 심사 5문항 답변
- [`doc/poc-trusted-ceo-accounting-agent.md`](document/poc-trusted-ceo-accounting-agent.md): PoC 제안서
- [`src/README-src.md`](./src/README-src.md): 런타임 구성 메모
