# Trusted CEO Agent — 5문항 답변

> 삼일PwC AX 인재전쟁 Round 10 제출물(`trusted-ceo-agent` Codex 플러그인)에 대한 심사 5문항 답변.
> 근거 문서: [`README.md`](../README.md), [`PoC 문서`](poc-trusted-ceo-accounting-agent.md), [`src/.codex-plugin/plugin.json`](../src/.codex-plugin/plugin.json)

---

## Q1. 무엇을, 누가, 어떤 상황에서 쓰나요?

**무엇을** — 기업의 내부 재무 데이터, DART 공시, ECOS 거시지표, 네이버 뉴스 신호를 함께 읽어 CEO가 아직 질문하지 않은 회계·재무·현금흐름·원가·공시·거시·평판 리스크를 역으로 추출하고, "왜 문제인지"의 인과 사슬을 서명용 CEO 브리핑으로 전달하는 에이전트입니다.

이 도구는 대시보드가 아니라 "무엇을 먼저 확인해야 하는가"를 제기하는 의사결정 보조 도구입니다. 내부 CSV가 있으면 시산표·채권 aging·원가배분표를 교차 대조하고, 상장사 API 모드에서는 DART 공시·재무제표, ECOS 금리·환율·물가, 네이버 뉴스 메타데이터를 결합해 외부 신호 기반 브리핑을 만듭니다.

**누가 · 어떤 상황에서**

- **사용자는 기업 CEO 또는 CEO에게 보고하는 의사결정 책임자입니다.** 분기 마감, 투자 검토, 사업 재편, 평판 리스크 점검처럼 "숫자는 있는데 무엇을 먼저 물어야 할지"가 중요한 순간에 씁니다.
- 삼일PwC 딜리버리 관점에서는 고객사별 재개발 없이 반복 실행할 수 있는 CEO 컨설팅 플러그인입니다. 산출물은 검토용 `briefing.md`와 제출용 `briefing.docx`입니다.
- 특정 기업 컨설팅은 항상 `company_name`, `business_number`를 받아 `company_identity_gate`를 먼저 수행합니다. 식별이 불명확하면 후속 재무·뉴스 판단보다 식별 리스크를 먼저 보고합니다.

---

## Q2. 왜 이 문제를 선택했나요?

세 가지 이유가 맞물립니다.

1. **대시보드로는 안 되는 추론 영역을 겨냥했습니다.** 과제가 요구한 것은 합성 데이터를 보기 좋게 보여주는 화면이 아니라, 데이터의 행간에 숨은 문제를 포착하고 논리적 근거를 자연어로 제시하는 AI 활용입니다. 이 문제는 단일 숫자가 아니라 매출·채권·현금흐름·공시·뉴스가 같은 방향을 가리키는지 보는 과제입니다.
2. **정산 MSA 도메인 경험을 CEO 리스크 분석으로 전이했습니다.** 복식부기 원장, 대사, 이벤트 정합성을 다뤄 온 경험은 "숫자 사이의 관계가 맞지 않는 지점"을 찾는 일과 맞닿아 있습니다. GL Reconciler의 break list → root-cause trace → exception report 패턴을 CEO 브리핑 도메인으로 옮겼습니다.
3. **경영진 의사결정의 실제 pain point입니다.** 경영진이 놓치는 문제는 숫자가 없어서가 아니라 숫자 사이의 관계를 늦게 읽기 때문에 발생합니다. "매출이 얼마인가?"보다 "그 매출이 현금으로 전환되는가?", "공시는 안정적으로 보이지만 뉴스와 산업 신호가 다른 이야기를 하는가?"를 먼저 묻는 것이 실제 손실을 줄입니다.

---

## Q3. 플러그인은 어떻게 작동하나요?

현재 파이프라인은 식별 게이트, 불변식 게이트, 외부 신호 수집, 전문 분석 스킬, 브리핑 생성, 자동 검증으로 구성됩니다.

```text
company_identity_gate
  -> API-only 모드 또는 내부 CSV(data-dir) 모드 선택
  -> verify-books                 # 내부 CSV가 있을 때 불변식 게이트
  -> diagnose-company             # DART / ECOS / Naver News / 내부 신호 통합
  -> ceo-risk-recon               # 리스크 가설·우선순위·확인절차 구성
  -> ceo-briefing                 # briefing.md 생성
  -> briefing-eval                # diagnostic-packet.json 기준 자동 검증
  -> documents                    # briefing.docx 변환
```

전문 스킬 경계는 다음과 같습니다.

| 스킬 | 역할 |
|---|---|
| `company-identity` | 사업자등록번호·대표자·개업일자·DART 식별자 확인 |
| `ceo-risk-recon` | 전체 리스크 오케스트레이션, 우선순위 산정 |
| `accounting-anomaly` | 매출·채권·계약부채·현금흐름 이상 신호 |
| `cashflow-bottleneck` | DSO, 채권 aging, 거래처 집중, 운전자본 병목 |
| `cost-allocation-audit` | 공통원가 배부 왜곡, 제품별 손익 재계산 |
| `macro-exposure` | ECOS 금리·환율·물가와 차입·원가 민감도 연결 |
| `disclosure-crosscheck` | DART 공시·재무제표와 내부 데이터 대사 |
| `external-signal` | 네이버 뉴스 기반 평판·산업·사업·투자·재무 동향 신호 |
| `ceo-briefing` | 결론, 근거, 왜 문제인가, 확신도, 권고 조치, 추가 확인 절차 정리 |

네이버 뉴스는 `--with-news` 옵션으로 활성화됩니다. 뉴스 제목·요약·링크·발행일 메타데이터만 사용하며, 단독 결론이 아니라 확인 신호로만 씁니다. 파생 신호는 `newsSignals`로 저장되고 다음 축에 연결됩니다.

- 기업평판·브랜드 이미지: 리콜, 소송, 품질, 평판 악화 신호
- 관심산업·시장 환경: 산업 수요, 규제, 경쟁, 공급망 변화
- 투자동향·자금소요: 증설, 인수, 신규 투자, 차입 부담 가능성
- 사업동향: 수주, 제휴, 해외 진출, 신규 제품, 고객사 변화
- 재무동향: 실적 압박, 비용 증가, 현금흐름·부채 관련 언급
- 운영·보안 리스크: 생산 차질, 사고, 보안, 제재, 규제 이슈

입력은 필수 `company_name`, `business_number`와 선택 `representative_name`, `opening_date`, `stock_code`, `dart_corp_code`, `news_query_name`, `financial_data_path`입니다. 출력은 `identity.json`, `diagnostic-packet.json`, `briefing.md`, `briefing.docx`, 실행 프롬프트와 후속 조치 문서입니다.

---

## Q4. AI를 어떻게 활용했나요?

두 층위에서 활용했습니다.

**1. 런타임: 기계 검증 신호 + LLM/에이전트 종합**

현재 구현은 "순수 규칙 매칭"도 아니고 "근거 없는 LLM 추론"도 아닙니다. 먼저 불변식, DART 재무 신호, ECOS 거시 신호, 네이버 뉴스 신호를 기계적으로 파생하고, 그 위에서 에이전트가 CEO가 이해할 수 있는 리스크 가설과 확인 절차로 종합합니다.

- 매출 증가, 채권 급증, 계약부채 감소, 영업현금흐름 악화를 하나의 수익 인식·현금전환 가설로 연결합니다.
- DART에서 차입·수익성·현금흐름 신호를 뽑고, ECOS 금리·환율·물가와 연결해 외부 환경 민감도를 해석합니다.
- 네이버 뉴스는 기업평판, 사업동향, 투자동향, 재무동향, 관심산업 신호를 만들어 공시·재무 수치와 충돌하거나 보강하는지 확인합니다.
- 결론은 단정하지 않고 "가능성", "확인 필요", "권고 조치"로 분리합니다.

**2. 빌드: AI 코딩 도구로 플러그인 제작**

Codex/Claude를 사용해 스킬 문서, CLI, 파생 신호 엔진, 뉴스 파이프라인, 자동 채점기, 샘플 데이터, 제출 문서를 구현했습니다. 특히 최근 확장에서는 `src/common/news-signals.mjs`, `--with-news`, `newsSignals`, `--signals-file` 기반 브리핑 평가를 추가해 실제 상장사 API-only 실행 결과도 검증할 수 있게 했습니다.

---

## Q5. 어떻게 검증했나요?

검증은 샘플 데이터 정답지, 불변식 게이트, 단위 테스트, 실제 기업 실행 산출물 평가를 함께 사용합니다.

**1. 샘플 데이터 정답지 기반 검증**

샘플 데이터에는 아래 4개 이상 신호를 의도적으로 심었습니다. 채점기는 문구를 하드코딩하지 않고 `src/common/signals.mjs`가 분석 대상 데이터에서 신호와 근거 수치를 다시 계산합니다.

| # | 신호 | 데이터 근거 | 기대 추론 |
|---|---|---|---|
| 1 | 수익 조기 인식 의심 | 매출 +11.3% vs 매출채권 +46.9%, 계약부채 340→180, 영업현금흐름 880→-310 | cut-off·계약 진행률 확인 |
| 2 | 특정 거래처 신용 집중 | 90일+ 채권 1,920 중 에이프릴리테일 1,750, 91.1% | 전사 문제가 아니라 거래처 1곳의 여신·담보 이슈 |
| 3 | 원가 배분 왜곡 | 제품C 매출비중 15% 배부 vs 실제 기계시간 45% | 흑자 +20이 재배부 시 -880 적자로 전환 |
| 4 | 차입 의존 성장·금리 노출 | 변동금리 차입 6,000→14,500, 이자비용 90→320, 이자보상배율 23.9→8.9배 | 영업CF 악화를 차입으로 메우는 구조, 고정금리·헤지 검토 |

**2. 불변식과 표현 안전성 검증**

`verify-books.mjs`는 내부 CSV의 대사 일치, 원가 배부 합계, 비중 합계 같은 기계 검증을 먼저 수행합니다. `briefing-eval.mjs`는 필수 섹션, 재현율, 단정 표현, 근거 언급을 확인합니다. 단정 표현은 "분식입니다", "확실합니다"처럼 감사 trail 없이 결론을 확정하는 문장을 실패로 처리합니다.

**3. 외부 신호와 실제 기업 실행 검증**

뉴스 확장 이후에는 `diagnostic-packet.json` 자체를 평가 입력으로 쓰는 `--signals-file` 경로를 추가했습니다. 따라서 내부 CSV가 없는 API-only 실행에서도 DART/ECOS/뉴스 신호가 브리핑에 반영됐는지 확인할 수 있습니다.

재현 명령 예시는 다음과 같습니다.

```powershell
node src/bin/ceo-consulting-pipeline.mjs `
  --company "<기업명>" `
  --business-number "<사업자등록번호>" `
  --corp-code "<DART고유번호>" `
  --stock-code "<종목코드>" `
  --with-news `
  --news-query "<뉴스검색명>" `
  --out-dir "outputs/<slug>"
```

```powershell
node src/test/briefing-eval.mjs `
  --signals-file "outputs/<slug>/diagnostic-packet.json" `
  "outputs/<slug>/briefing.md"
```

관련 단위 테스트는 다음 범위를 확인합니다.

```powershell
node --test `
  src/test/unit/dart-signals.test.mjs `
  src/test/unit/news-signals.test.mjs `
  src/test/unit/naver-client.test.mjs
```

실제 실행 산출물도 `outputs/samsung-ct-ceo-pipeline`, `outputs/shinsung-tongsang-ceo-pipeline`, `outputs/daesang-ceo-pipeline`, `outputs/tovis-ceo-pipeline`에 생성해 `diagnostic-packet.json`과 `briefing.md` 기준으로 평가했습니다. 검증 기준은 데이터 정합성, 신호 재현율, 근거 정합성, 표현 안전성, 뉴스 신호의 보조적 사용 여부입니다.
