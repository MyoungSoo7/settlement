# Seed — market-service 시세 공개조회 as-is 사양

> 상태: CONFIRMED (settlement/account/deposit seed 와 동일 방식 — 역산 결정화)
> 자매 Seed: `financial-statements-service-public-read`(같은 공개 read-only 위성 패턴)

## Goal (한 줄)

**market-service(KRX 일별 시세·시총 공개조회 — 밸류에이션 미계산 경계)의 현행 동작을 실행 가능한
게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 경계 방어 근거 · 면접/포트폴리오 문서로 쓴다.**

## 범위

**포함**

- 시세 레코드(StockQuote)의 타입 규율과 값 무결성
- 종목마스터(Stock) 피드 파생 upsert
- 신뢰도 표기(ValueSource) — 도메인 언어 재정의 이력 포함
- 공개 GET / `/admin` 키 게이트 이원화
- 수집(KRX) 트리거·스케줄러 경계

**제외**

- KRX 외부 API 페이로드 상세(어댑터 사정)
- 파티셔닝 운영 절차(마이그레이션 존재 사실만)

## 핵심 불변식 (as-is, 파일:라인 근거)

경로 접두 `../../../market-service/src/main/java/github/lms/lemuel/market`

| # | 불변식 | 근거 |
|---|---|---|
| 1 | **PER/PBR 미계산** — 이 서비스는 시세·시총만 서빙하고 밸류에이션을 만들지 않는다. 조인은 소비측 몫 | `../../../scripts/harness/guard.mjs` `MARKET-NO-VALUATION` 규칙(실시간·pre-commit·CI 3중) |
| 2 | **금액/수량 타입 분리** — 금액은 `BigDecimal`, 수량(거래량·상장주식수)과 원 단위 총액(거래대금·시총)은 `BigInteger` | `domain/StockQuote.java:12-22` |
| 3 | **피드값 보존** — economics 와 달리 이전 관측치에서 파생 계산하지 않고 받은 값을 그대로 저장한다 | `domain/StockQuote.java:12-13` |
| 4 | **필수 필드 강제** — `stockCode`·`baseDate`·`closePrice`·`source` 누락은 생성 시점 거부 | `StockQuote.java:25,28,31,34` |
| 5 | **(종목, 거래일) 유일** — 같은 날 같은 종목은 한 행. 재수집은 UPSERT 로 대체된다 | `V1__market_core.sql:35` (`uq_sq_stock_date`) |
| 6 | **값 무결성 CHECK** — 종가 `> 0`, 나머지 가격·수량 `>= 0`. 단 `prior_day_diff`·`fluctuation_rate` 는 **음수가 정상**(하락)이라 CHECK 제외 | `V20260718300000__stock_quotes_value_checks.sql:12-18` |
| 7 | **신뢰도는 도메인 언어로** — `SAMPLE`(근사 샘플, 신뢰 불가) / `EXCHANGE`(거래소 공시 실시세). 과거 `SEED`/`KRX` 에서 재정의됨 — 적재 수단·공급자 이름은 도메인 상수가 아니라는 판단 | `domain/ValueSource.java:14` + `V20260810160000__value_source_domain_rename.sql:1-8` |
| 8 | **종목마스터는 피드 파생** — 그날 피드에 등장한 종목의 이름/시장을 upsert 해 상장/상장폐지가 자동 반영된다 | `domain/Stock.java:9` |
| 9 | **시장 구분 고정** — `KOSPI`·`KOSDAQ`·`KONEX` | `V1__market_core.sql:15` (`chk_stock_market`) |
| 10 | **공개 GET / 관리 게이트 이원화** — 조회는 공개, 수집 트리거는 `X-Internal-Api-Key` 필요 + gateway 미라우팅 | `adapter/in/web/StockController.java:28` vs `MarketSyncAdminController.java:24,30` + `config/AdminApiKeyFilter.java:18` |

## 이벤트 계약

**발행 0 · 소비 0** — Kafka 를 쓰지 않는 공개 read-only 위성이다. shared-common 미의존.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | PER/PBR 계산 코드가 유입되지 않는다 | `node scripts/harness/guard.mjs` — `MARKET-NO-VALUATION` |
| AC-2 | 금액/수량 타입 분리가 유지된다 | `./gradlew :market-service:test` — `MarketPersistenceAdapterTest` |
| AC-3 | (종목, 거래일) 중복 적재가 행을 늘리지 않는다(UPSERT) | `uq_sq_stock_date` · `KrxSyncServiceTest` |
| AC-4 | 값 무결성(종가 > 0 등)이 DB 에서 강제된다 | `V20260718300000` CHECK 3종 |
| AC-5 | 수집 실패가 조용히 성공으로 보이지 않는다 | `KrxSyncServiceFailureTest` · `MarketSyncSchedulerTest` |
| AC-6 | 헥사고날 의존 방향 위반 0 | `HexagonalArchitectureTest` |
| AC-7 | `/admin/market/sync` 가 키 없이 통과하지 않는다(운영 fail-closed) | `MarketWebLayerTest` · `AdminApiKeyFilter` |
| AC-8 | 커버리지 LINE >= 90% | `./gradlew :market-service:jacocoTestCoverageVerification` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** 도메인이 필수값 검증에 **generic `IllegalArgumentException`** 을 쓴다(`StockQuote.java:25-34`,
  `Stock.java:17,23`). guard 의 `OO-DOMAIN-GENERIC-IAE` 는 금융 5서비스에만 걸려 market 은 대상 밖이다.
  → `disposition: recorded-not-fixed` (게이트 비대칭 — 위성은 `*-rules` 스킬로 커버하는 것이 의도된 설계)
- **KI-2** `AdminApiKeyFilter` 는 **키 미설정 시 개발 통과**다. 운영에서 `app.security.internal-key-required=true`
  주입이 빠지면 수집 트리거가 무인증으로 열린다 — 배포 절차에 의존하는 안전장치다.
  → `disposition: by-design` (전 서비스 공통 패턴, 운영 주입 필수)
- **KI-3** ~~값 무결성 CHECK 가 `NOT VALID` 로 추가됐다~~ → **검증 결과 사실 아님**(2026-08-13). 같은 파일
  `:23-25` 가 `VALIDATE CONSTRAINT` 3종을 이어서 수행한다. 임시 DB 실측에서 `chk_sq` 4종 모두
  `convalidated = t`, 미검증 제약 0건(파티션 상속본 포함), 음수 종가 INSERT 는 실제로 거부됐다.
  `NOT VALID`→`VALIDATE` 2단은 락 회피 패턴이지 검증 누락이 아니다.
  → `disposition: verified-not-an-issue`
- **KI-4** `ValueSource` 재정의(`SEED`→`SAMPLE`, `KRX`→`EXCHANGE`)는 **파티션드 테이블 + CHECK 제약 해제·재생성
  순서에 의존**하는 마이그레이션이었다(`V20260810160000:10-14`). 같은 형태의 값 집합 변경이 또 필요하면
  동일한 순서 함정을 밟는다. → `disposition: recorded-not-fixed` (마이그레이션 패턴 위험)
