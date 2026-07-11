# MEMORY.md

## Project Memory

이 프로젝트의 핵심 포지션은 "투자 추천 플러그인"이 아니라 "CEO 경영 리스크 컨설팅 플러그인"입니다. 삼일PwC 과제 맥락에서는 회계법인/컨설팅 관점에서 신뢰 가능한 근거, 확인 절차, 산출물 품질을 보여주는 것이 중요합니다.

## Product Decisions

1. 사용자는 CEO 한 사람으로 가정합니다.
2. 산출물은 실무 워킹페이퍼가 아니라 CEO가 읽고 결정할 수 있는 브리핑으로 수렴합니다.
3. 내부 데이터가 없어도 상장사는 DART/ECOS/뉴스로 API-only 진단을 시작할 수 있습니다.
4. 내부 CSV가 들어오면 공시로 볼 수 없는 거래처 신용 집중, 원가 배분 왜곡, 현금흐름 병목까지 확장합니다.
5. 기업명만으로 분석하지 않고 사업자등록번호를 함께 받아 식별 정합성을 먼저 확인합니다.
6. 고객 원본 Excel/CSV는 `spreadsheets`로 표준화하고, 최종 보고서는 `documents`로 DOCX화합니다.

## Pipeline Memory

표준 파이프라인:

```text
spreadsheets
  -> trusted-ceo-agent
  -> compliance-review / compliance-language
  -> documents
```

역할 분리:

- `spreadsheets`: 고객 원본 Excel/CSV를 표준 CSV 3종으로 변환
- `trusted-ceo-agent`: 식별, 정합성 검증, 신호 파생, 외부 API 교차 검증, CEO 브리핑
- `compliance-review`: 민감정보, 감사 추적성, 권한/이력 관점 검수
- `compliance-language`: 투자 권유성/단정 표현 검수
- `documents`: `briefing.md`를 제출용 `briefing.docx`로 변환

## Data Memory

내부 데이터 표준 파일:

```text
trial_balance.csv
ar_aging.csv
cost_allocation.csv
```

기본 샘플:

```text
src/data/sample/
```

외부 데이터:

- 국세청/data.go.kr: 사업자등록번호 상태조회/진위확인
- DART: 기업개황, 공시 목록, 재무제표
- ECOS: 기준금리, 국고채3년, USD/KRW, CPI
- 네이버 뉴스: 기업 관련 제목, 요약, 링크, 발행일

## Reasoning Memory

분석 원칙:

- 정답지 하드코딩 금지
- 불변식 게이트 우선
- 식별 게이트 우선
- 읽기 전용 조회
- 단정 금지
- 교차 검증
- 뉴스는 보조 신호

브리핑은 PRESENT 신호만 리스크 후보로 삼습니다. ABSENT 신호를 리스크로 승격하면 오탐입니다. 데이터 품질 문제가 있으면 리스크 추론보다 데이터 품질 리스크를 먼저 보고합니다.

## Demo Signal Memory

샘플 데이터에는 아래 신호가 심어져 있습니다. 단, 채점기는 이 숫자를 하드코딩하지 않고 `detect-signals`가 매번 계산한 값을 사용합니다.

| 신호 | 의미 |
|---|---|
| S1 | 매출 증가 대비 매출채권 급증, 계약부채 감소, 영업현금흐름 악화 |
| S2 | 90일 초과 채권이 특정 거래처에 집중 |
| S3 | 제품C가 매출 기준 배부에서는 흑자지만 실제 기계시간 기준 재배부 시 적자 전환 |
| S4 | 변동금리 차입 증가와 이자보상배율 하락 |

## Output Memory

권장 산출물:

```text
identity.json
diagnostic-packet.json
pipeline-next-steps.md
briefing.md
briefing.docx
```

`briefing.md`는 검토/채점용입니다. `briefing.docx`는 CEO/파트너/고객 제출용입니다.

## Compliance Memory

이 프로젝트에서 피해야 할 표현 (금지어 원문은 채점기/가드가 소유 — 여기 직접 쓰지 않는다):

- 수익·원금을 약속하는 보장형 표현
- 주가 방향을 단정하는 표현
- 매수/매도를 지시하는 표현
- 리스크가 없다고 말하는 표현
- 회계부정을 단정하는 표현 (가설 + 확인 절차로 분리)

투자 관련 표현이 섞이면 아래 고지를 붙입니다.

```text
본 정보는 교육 목적의 정보 제공이며 투자자문·투자권유가 아닙니다.
투자 판단과 그 결과(손실 포함)에 대한 책임은 투자자 본인에게 있습니다.
```

## Implementation Memory

주요 파일:

```text
src/bin/ceo-consulting-pipeline.mjs
src/bin/diagnose-company.mjs
src/bin/verify-books.mjs
src/bin/detect-signals.mjs
src/bin/dart-to-csv.mjs
src/test/briefing-eval.mjs
CODEX.md
README.md
AGENTS.md
STATUS.md
MEMORY.md
```

주요 스킬:

```text
src/skills/company-identity/SKILL.md
src/skills/ceo-risk-recon/SKILL.md
src/skills/accounting-anomaly/SKILL.md
src/skills/cashflow-bottleneck/SKILL.md
src/skills/cost-allocation-audit/SKILL.md
src/skills/macro-exposure/SKILL.md
src/skills/disclosure-crosscheck/SKILL.md
src/skills/external-signal/SKILL.md
src/skills/ceo-briefing/SKILL.md
```

## Open Memory

나중에 보강하면 좋은 항목:

1. 실제 `documents` 플러그인으로 생성한 `briefing.docx` 샘플
2. 실제 고객 Excel을 `spreadsheets`로 변환하는 예시
3. `ceo-consulting-pipeline.mjs`가 브리핑 초안까지 자동 생성하는 단계
4. `README.md`를 제출용 소개 중심으로 축약하고 운영 규칙은 `AGENTS.md`에 위임
