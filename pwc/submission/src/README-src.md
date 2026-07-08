# Source Notes — trusted-ceo-agent

플러그인 런타임 구성:

- `.codex-plugin/plugin.json` — manifest. `skills` 는 공식 문서 형식(`"./skills/"` 디렉토리 참조)을 따른다.
- `skills/` — 스킬 9개. 진입점은 `ceo-risk-recon` (오케스트레이터), 나머지는 식별/도메인/출력 스킬.
  스킬이 호출하는 스크립트 경로는 플러그인 루트 기준 `bin/` — 설치 후 바로 동작한다.
- `data/sample/` — 합성 데모 데이터. 이상 신호 4종의 설명은 상위 `README.md` 참조
  (설명일 뿐 정답지 아님 — 정답지는 아래 파생 엔진이 매번 계산).
- `common/csv.mjs` — 견고한 CSV 파서: 따옴표 필드(콤마 포함)·이스케이프 따옴표·BOM·CRLF·
  천단위 콤마·회계 괄호 `(1,000)`·전각 마이너스. ERP/엑셀 내보내기를 그대로 받기 위한 층.
- `common/books.mjs` — **장부 로더 + 불변식 엔진**. 컬럼 별칭(한국어 헤더 포함) 정규화,
  누락 파일/컬럼의 친절한 오류(BooksLoadError), 불변식 7종. 특정 분기 하드코딩 없음 —
  INV-3 의 비교 분기는 데이터의 최근 분기에서 파생. `--data-dir`/env 해석(`resolveDataDir`)도 제공.
- `common/signals.mjs` — **신호 파생 엔진 (프레임워크의 심장)**. 수익-현금 괴리(S1) ·
  거래처 신용 집중(S2) · 원가 배분 왜곡(S3) · 차입 의존·금리 노출(S4)을 **임계값 기반으로 판정**하고,
  채점용 마커 정규식을 계산값에서 생성한다. 임계값은 데이터 폴더의 `analysis-config.json` 으로
  오버라이드 가능. 에이전트(detect-signals)와 채점기(briefing-eval)가 이 엔진 하나를 공유한다.
- `common/crosscheck.mjs` — **INV-8 상장사 외부 대사**. 내부 시산표 연간 합계(매출·영업이익)를
  DART 사업보고서(기본 별도 OFS, 감사 수치)와 허용오차 대조 — 내부 불변식이 못 잡는
  "일관되게 틀린 장부"의 두 번째 방어선. 완결 회계연도(Q1~Q4) 자동 파생, 없으면 skip.
  `fetchSummary` 주입 지점으로 네트워크 0 테스트(`crosscheck.test.mjs`), 삼성전자 2025 실공시
  라이브 검증(일치 0% PASS / 매출 부풀림 1.26% FAIL) 완료.
- `common/dart-signals.mjs` — **외부(공시) 신호 파생 엔진 (기본 모드의 심장)**. DART 전체
  재무제표(fnlttSinglAcntAll, 3개년) + 공시 목록에서 외부 신호 E1~E5(수익-채권 괴리 · 재고 적체 ·
  차입 확대·이자 부담 · 유동성 하락 · 공시 행간)를 임계값 기반으로 판정하고, 채점용 마커를
  계산값에서 생성한다. account_id(XBRL 표준) 우선 + 계정명 별칭 폴백, 계정 결측 시 evaluable=false
  (지어내지 않음). 내부 signals.mjs 와 동일한 신호 객체 형태 — 채점기와 그대로 호환.
- `bin/diagnose-company.mjs` — **2단계 진단 CLI (제품 진입점)**.
  기본 모드: `--company <기업명>` 만으로 식별(corp_code 확정) → 3개년 재무 → 공시 90일 →
  ECOS 금리 → 외부 신호 E1~E5. 상세 모드: `--data-dir <내부CSV폴더>` 를 붙이면 불변식 게이트 →
  내부 신호 S1~S4 → INV-8 공시 대사(확정된 corp_code 자동 배선)가 같은 진단 패킷에 얹힌다.
  `--json` 출력은 briefing-eval `--signals-file` 의 채점 정답지.
- `bin/verify-books.mjs` — **불변식 게이트 CLI** (`--data-dir`/`--json`). 상세 모드의 저수준 도구 —
  GATE PASS 여야 추론 진입 (doc/회계.md 의 "불변식 먼저, 추론은 그 위" 원칙의 구현).
  상장사면 `--dart-corp-code`(+`--dart-year/--dart-fs-div/--dart-unit-scale/--dart-tolerance-pct`)
  또는 analysis-config.json `crosscheck` 섹션으로 INV-8 을 활성화 (INV-1~7 PASS 시에만 실행).
- `bin/detect-signals.mjs` — **신호 파생 CLI** (`--data-dir`/`--json`). 게이트 통과 후 신호
  PRESENT/absent 판정과 근거 수치(증가율·집중도·재배부 손익·이자보상배율)를 출력. 에이전트는
  이 수치를 그대로 인용하고(암산 금지), absent 신호를 리스크로 승격하지 않는다.
  게이트 FAIL 데이터에서는 신호 파생을 거부한다.
- `bin/run-sample.ps1` — 데이터 존재 확인 → 불변식 게이트 → 신호 파생 → 데모 프롬프트 출력.
- `test/briefing-eval.mjs` — 생성된 브리핑의 **자동 채점기** (v3). 정답지를 하드코딩하지 않고
  `--data-dir` 데이터에서 파생한다 — 어떤 회사 데이터로 만든 브리핑이든 같은 절차로 채점:
  - **재현율 + 근접성**: 파생 PRESENT 신호별 마커 2개 이상이 **같은 섹션(##/### 블록) 안**에서
    함께 나와야 포착 인정 (흩뿌린 숫자는 불인정 — 한 가설로 수렴했는지 근사).
  - **정밀도/오탐**: absent 신호를 리스크로 주장(마커 수렴 또는 카테고리 서술)하면 FAIL.
  - **과잉 확신**: 근거 마커 0개인데 "확인됨"으로 단정한 리스크 섹션 검출.
  - **확신도 보정 / 판별 테스트**: 포착 신호마다 확신도 태그·확인 절차가 섹션에 있는가.
  - **표현 안전성 / 구조**: 단정 표현 가드 + 필수 섹션(결론·근거·확신도·권고 조치).
  - **음성 채점**: `--clean` 명시 또는 PRESENT 0건이면 자동 전환 — 리스크를 지어내면 FAIL,
    "이상 없음" 승인이 있어야 PASS.
  - `--self-test` 로 채점기 자체 회귀 검증 (샘플 파생 4건 / clean 파생 0건 포함).
- `data/fixtures/clean/` — **음성 픽스처**: 불변식 게이트는 GATE PASS 하고 파생 신호가 0건인
  건강한 회사 데이터. `--data-dir` 로 지정해 오탐(없는 리스크 지어내기) 검출을 회귀 검증한다.
- `.mcp.json` — DART MCP 서버(`trusted-ceo-agent-dart`) 등록. manifest 의
  `"mcpServers": "./.mcp.json"` 으로 연결됨.
- `dart/` — DART OpenAPI 클라이언트 (zero-dependency). `client.mjs`(API 래퍼,
  DART_API_KEY 는 env 우선 + 상위 `.env` 폴백) + `corp-codes.mjs`(corpCode.zip
  다운로드→자체 zip 해제→`data/cache/corp-codes.json` 7일 캐시, 이름/종목코드 검색).
- `mcp/dart-server.mjs` — 읽기 전용 DART MCP 서버, 도구 6종:
  `dart_corp_search`(진입점 — corp_code 확정) · `dart_company`(기업개황) ·
  `dart_disclosures`(공시 목록, 정정공시 반복 등 행간 신호) ·
  `dart_financial_summary`(주요계정, 당기/전기/전전기) ·
  `dart_financial_full`(전체 재무제표, CFS/OFS) · `dart_status`(키/캐시 점검)
- `bin/dart-cli.mjs` — 수동 점검용: `node src/bin/dart-cli.mjs search 삼성전자`
- `bin/dart-to-csv.mjs` — DART 공시 재무제표를 `trial_balance_public.csv` 로 생성.
  내부 aging/원가배분/원장 분석을 대체하지 않는 공시 기반 요약 CSV.
- `test/dart-smoke.mjs` — MCP 왕복 + (키 있으면) 라이브 검증
- `ecos/client.mjs` — 한국은행 ECOS OpenAPI 클라이언트 (zero-dependency,
  ECOS_API_KEY 는 env 우선 + 상위 `.env` 폴백). StatisticSearch/KeyStatisticList 래퍼 +
  검증된 지표 카탈로그 4종 (이 저장소 economics-service V1 시드와 동일 좌표):
  BASE_RATE(722Y001) · TREASURY_3Y(817Y002) · USD_KRW(731Y001) · CPI(901Y009)
- `mcp/ecos-server.mjs` — 읽기 전용 ECOS MCP 서버, 도구 4종:
  `ecos_indicator`(핵심 4지표 시계열+최신값+변화량) · `ecos_series`(임의 통계 원시 조회) ·
  `ecos_key_stats`(100대 통계지표 스냅숏 — 거시 브리핑용) · `ecos_status`(키/카탈로그 점검)
- `test/ecos-smoke.mjs` — MCP 왕복 + (키 있으면) 라이브 검증
- `naver/client.mjs` — 네이버 뉴스 검색 OpenAPI 클라이언트(zero-dependency,
  NAVER_CLIENT_ID/NAVER_CLIENT_SECRET env 우선 + 상위 `.env` 폴백). 기사 본문 전문은 수집하지 않고
  제목·요약·링크·발행일 메타데이터만 정규화한다.
- `mcp/news-server.mjs` — 읽기 전용 네이버 뉴스 MCP 서버, 도구 3종:
  `news_search_company`(기업명 기준 뉴스 검색) · `news_search_risk`(기업명 + 리스크 키워드 검색) ·
  `news_status`(키/기본 리스크 키워드 상태)
- `test/news-smoke.mjs` — MCP 왕복 + (키 있으면) 라이브 검증
- `registry/client.mjs` — 국세청 사업자등록정보 진위확인/상태조회 클라이언트(data.go.kr,
  DATA_GO_KR_API_KEY env 우선 + 상위 `.env` 폴백). 로컬 체크섬 검증 후 status/validate API 를 호출한다.
- `mcp/registry-server.mjs` — 읽기 전용 사업자등록 MCP 서버, 분석용 도구 4종 + 상태 도구 1종:
  `business_number_validate` · `business_status_check` · `business_auth_check` ·
  `company_identity_gate` · `registry_status`
- `test/registry-smoke.mjs` — MCP 왕복 + 로컬 체크섬 + (키 있으면) 라이브 검증

DART 키 발급: https://opendart.fss.or.kr (무료, 일 20,000건). `DART_API_KEY` env 로 주입.
ECOS 키 발급: https://ecos.bok.or.kr → Open API (무료). `ECOS_API_KEY` env 로 주입.
샘플 CSV(`data/sample/`)가 "내부 데이터" 시나리오라면, DART 도구는 "외부 공시" 축,
ECOS 도구는 "거시 환경" 축이다 — 내부 장부 ↔ 공시 재무제표 대사(cross-check)에
금리(이자 부담)·환율(수입 원가)·CPI(원가 전가 여력) 컨텍스트를 결부지어
Trusted CEO Agent 의 실데이터 데모 경로를 완성한다.

## 단위 테스트 + 커버리지 (zero-dependency — Node 22 내장 러너)

```bash
# submission/ 에서 실행. 라인 커버리지 90% 게이트 포함 (현재 ~99%)
node --test --experimental-test-coverage --test-coverage-lines=90 \
  --test-coverage-include='src/common/**' --test-coverage-include='src/dart/**' \
  --test-coverage-include='src/ecos/**'   --test-coverage-include='src/mcp/**' \
  --test-coverage-include='src/naver/**'  --test-coverage-include='src/registry/**' \
  --test-coverage-include='src/bin/**' \
  --test-coverage-include='src/test/briefing-eval.mjs' \
  src/test/unit/*.test.mjs
```

- `test/unit/` — 단위 테스트 파일. 네트워크 0: in-process 는 `fetch` 스텁,
  자식 프로세스(MCP 서버·CLI)는 `NODE_OPTIONS --import` 프리로드 스텁(`helpers/fetch-preload.mjs`)로 차단.
- 테스트 주입 지점: `CORP_CODES_CACHE`(실캐시 오염 방지), `VERIFY_BOOKS_DATA_DIR`(위반 시나리오 픽스처).
- MCP 서버 spawn 테스트는 stdin 을 닫아 자연 종료시켜야 자식 프로세스 커버리지가 flush 된다
  (`helpers/proc.mjs` — `kill()` 은 커버리지가 유실됨).

## 스킬 호출 흐름

```
사용자(CEO): "놓친 리스크 찾아줘"
  └─ ceo-risk-recon (게이트 → 신호 파생 → 인벤토리 → 디스패치 → 교차검증 → 중요도 → 브리핑)
       ├─ [Step 1] bin/verify-books.mjs --data-dir …   (GATE PASS 여야 진입)
       ├─ [Step 2] bin/detect-signals.mjs --data-dir … (신호 판정 + 근거 수치 파생)
       ├─ company-identity      (사업자번호 검증/상태조회/진위확인 + 식별자 확정)
       ├─ accounting-anomaly     (시산표/분개 → break + 원인 가설)
       ├─ cashflow-bottleneck    (aging/운전자본 → 병목 + 현금 영향)
       ├─ cost-allocation-audit  (배부표 → 민감도 재계산 + 뒤집힘 탐지)
       ├─ macro-exposure         (ECOS 지표 × 차입/외화/원가 → 노출 민감도)
       ├─ disclosure-crosscheck  (DART 공시 ↔ 내부 장부 대사 + 공시 행간)
       ├─ external-signal        (네이버 뉴스 ↔ 투자유치/제휴/규제/보안 신호)
       └─ ceo-briefing           (서명용 보고 형식으로 최종 출력)
                └─ 사후 채점: test/briefing-eval.mjs --data-dir … (파생 신호 재현율 + 오탐 + 표현 안전성)
```
