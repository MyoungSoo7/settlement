# Trusted CEO Agent — 삼일PwC 과제 제출물

기업의 최고경영책임자(CEO)가 놓치기 쉬운 회계·재무·거시 환경 리스크를
**데이터의 행간에서 먼저 찾아내고**, 그 근거와 확인 절차를 자연어 브리핑으로 제시하는
Codex/Claude 호환 플러그인입니다.

이 플러그인은 단순한 재무 대시보드가 아닙니다. CEO가 이미 알고 있는 숫자를 다시 보여주는 대신,
시산표·채권 aging·원가배분표·DART 공시·ECOS 경제지표를 교차 대조해
**"무엇을 먼저 확인해야 하는가"** 를 제안합니다.

## CEO에게 제공하는 기능

| 기능 | CEO가 얻는 답 |
|---|---|
| 회계처리 이상 탐지 | 매출과 이익은 좋아 보이는데 현금이 따라오지 않는 이유가 무엇인가 |
| 현금흐름 병목 분석 | 미수금 문제가 전사 문제인지, 특정 거래처 신용 리스크인지 |
| 원가 배분 왜곡 탐지 | 흑자로 보이는 제품·사업부가 실제 자원 사용량 기준으로도 이익인지 |
| DART 공시 기반 기업 확인 | 외부 공시 재무제표와 기업개황, 최근 공시에서 위험 신호가 있는지 |
| ECOS 거시지표 결합 | 금리·환율·물가 변화가 차입비용, 원가, 현금흐름에 어떤 부담을 주는지 |
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
| 논리적 근거를 자연어로 제시 | `src/skills/ceo-briefing/SKILL.md` |
| 종합 오케스트레이션 | `src/skills/ceo-risk-recon/SKILL.md` |
| 외부 공시 재무 데이터 연결 | `src/mcp/dart-server.mjs` |
| 거시 경제지표 연결 | `src/mcp/ecos-server.mjs` |

## 작동 구조

```text
사용자 요청
  "우리 회사 데이터에서 CEO가 놓친 리스크를 찾아줘"
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
        |
        v
외부 읽기 전용 MCP
  - DART: 기업개황, 공시 목록, 재무제표
  - ECOS: 기준금리, 국고채3년, USD/KRW, CPI
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

핵심 원칙은 세 가지입니다.

1. **읽기 전용**: 내부 장부, DART, ECOS는 분석 입력으로만 사용합니다. 플러그인이 원천 데이터를 수정하지 않습니다.
2. **단정 금지**: "분식입니다"가 아니라 "수익 인식 시점 확인이 필요한 가능성"처럼 가설과 확인 절차를 분리합니다.
3. **교차 검증**: 단일 숫자로 판단하지 않고 손익, 채권, 현금흐름, 공시, 경제지표를 함께 봅니다.

## CEO 관점 사용 예시

### 1. 분기 마감 직후 리스크 점검

```text
src/data/sample 데이터를 기준으로 CEO가 놓치고 있는 회계·현금흐름·원가 리스크를 찾아줘.
근거 숫자와 확인 절차까지 CEO 브리핑 형식으로 정리해줘.
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

| # | 신호 | 데이터 근거 | 기대 추론 |
|---|---|---|---|
| 1 | 수익 조기 인식 의심 | 매출 +11.3%(2026Q1→2026Q2)인데 매출채권 +46.9%, 계약부채 340→180 감소, 영업현금흐름 880→-310 | "팔았다고 기록했지만 현금이 들어오지 않는 매출" 가능성. cut-off, 계약 진행률, 기말 전후 전표 확인 권고 |
| 2 | 특정 거래처 신용 리스크 | 90일 초과 채권 1,920 중 에이프릴리테일 1,750, 비중 91.1% | 전사 회수 프로세스 문제가 아니라 특정 거래처 한 곳의 여신·담보·회수 계획 문제일 가능성 |
| 3 | 원가 배분 왜곡 | 제품C는 매출비중 15%로 공통원가 450만 배부받지만 실제 기계시간은 45% 사용 | 현재 흑자 +20이 실제 기계시간 기준 재배부 시 -880 적자로 전환. 가격·생산·철수 검토 필요 |

## 플러그인 구조

```text
submission/
|-- README.md
|-- question5.md
|-- doc/
|   `-- poc-trusted-ceo-accounting-agent.md
|-- src/
|   |-- .codex-plugin/plugin.json
|   |-- .mcp.json
|   |-- README-src.md
|   |-- skills/
|   |   |-- ceo-risk-recon/SKILL.md
|   |   |-- accounting-anomaly/SKILL.md
|   |   |-- cashflow-bottleneck/SKILL.md
|   |   |-- cost-allocation-audit/SKILL.md
|   |   `-- ceo-briefing/SKILL.md
|   |-- data/sample/
|   |   |-- trial_balance.csv
|   |   |-- ar_aging.csv
|   |   `-- cost_allocation.csv
|   |-- dart/
|   |   |-- client.mjs
|   |   `-- corp-codes.mjs
|   |-- ecos/
|   |   `-- client.mjs
|   |-- mcp/
|   |   |-- dart-server.mjs
|   |   `-- ecos-server.mjs
|   `-- test/
|       |-- dart-smoke.mjs
|       `-- ecos-smoke.mjs
`-- logs/
```

## Codex 설치

개인 마켓플레이스에 로컬 플러그인으로 등록합니다.

```bash
mkdir -p ~/.codex/plugins
cp -R src ~/.codex/plugins/trusted-ceo-agent
```

`~/.agents/plugins/marketplace.json` 예시:

```json
{
  "name": "personal-plugins",
  "plugins": [
    {
      "name": "trusted-ceo-agent",
      "source": { "source": "local", "path": "./.codex/plugins/trusted-ceo-agent" },
      "policy": { "installation": "AVAILABLE", "authentication": "ON_INSTALL" },
      "category": "Productivity"
    }
  ]
}
```

## Claude Code에서 사용

Claude Code는 `SKILL.md` 형식을 그대로 읽을 수 있으므로, 스킬을 Claude 스킬 디렉터리에 복사해 사용할 수 있습니다.

```powershell
New-Item -ItemType Directory -Force .claude\skills | Out-Null
Copy-Item -Recurse -Force src\skills\* .claude\skills\
```

Claude에서 다음처럼 요청하면 됩니다.

```text
ceo-risk-recon 스킬을 사용해서 src/data/sample 데이터를 분석해줘.
CEO가 놓친 리스크를 찾고, ceo-briefing 형식으로 정리해줘.
```

## DART / ECOS MCP 도구

이 플러그인은 실제 기업 분석으로 확장하기 위해 읽기 전용 MCP 서버를 포함합니다.

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

위 세 신호를 모두 포착하고, 각 신호를 **결론이 아니라 확인 가능한 가설**로 제시해야 합니다.

## 검증 기준

| 기준 | 확인 방법 |
|---|---|
| 재현율 | 심어둔 3개 이상 신호를 모두 포착하는가 |
| 근거 정합성 | 11.3%, 46.9%, 91.1%, -880 등 핵심 수치가 손계산과 일치하는가 |
| 표현 안전성 | 단정 대신 가설, 확신도, 추가 확인 절차로 분리하는가 |
| 도구 연결성 | `dart-smoke.mjs`, `ecos-smoke.mjs`에서 MCP initialize/tools/list/status가 통과하는가 |
| CEO 적합성 | 실무자가 아닌 CEO가 읽어도 "무엇을 결정해야 하는지"가 보이는가 |

## 관련 문서

- [`question5.md`](./question5.md): 심사 5문항 답변
- [`doc/poc-trusted-ceo-accounting-agent.md`](./doc/poc-trusted-ceo-accounting-agent.md): PoC 제안서
- [`src/README-src.md`](./src/README-src.md): 런타임 구성 메모
