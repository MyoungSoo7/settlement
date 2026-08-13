# Seed — economics-service 경제지표 공개조회 as-is 사양

> 상태: CONFIRMED (settlement/account/market seed 와 동일 방식 — 역산 결정화)
> 자매 Seed: `market-service-quotes`·`financial-statements-service-public-read` (같은 공개 read-only 위성 패턴)

## Goal (한 줄)

**economics-service(ECOS 경제지표 공개조회 — 카탈로그 기반·파생값 계산)의 현행 동작을 실행 가능한
게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 경계 방어 근거 · 면접/포트폴리오 문서로 쓴다.**

## 범위

**포함**

- 지표 카탈로그가 enum 이 아니라 DB row 라는 설계와 그 귀결
- 관측치(IndicatorValue) 필수값·유일성·주기(D/M) 정규화
- 파생값(변동액·변동률) 계산 규칙과 방어
- 공개 GET / `/admin` 키 게이트 이원화
- ECOS 수집 트리거·스케줄러 경계

**제외**

- ECOS 외부 API 응답 포맷 상세(어댑터 사정)
- 파티셔닝 운영 절차(마이그레이션·러너 존재 사실만)

## 핵심 불변식 (as-is, 파일:라인 근거)

경로 접두 `../../../economics-service/src/main/java/github/lms/lemuel/economics`

| # | 불변식 | 근거 |
|---|---|---|
| 1 | **카탈로그는 코드가 아니라 데이터** — 지표 추가는 스키마·코드 변경 없이 `indicators` row 추가로 끝난다 | `domain/Indicator.java:5` + `V1__economics_core.sql:3` |
| 2 | **주기는 D/M 뿐** — ECOS cycle 과 1:1 | `domain/IndicatorCycle.java:4` + `V1:11,17` (`chk_indicator_cycle`) |
| 3 | **월별은 1일로 정규화** — M 지표의 `observed_date` 는 해당 월 1일로 맞춰 저장한다(월 내 관측일이 달라도 한 점) | `V1:5` + `adapter/out/external/EcosApiClient.java:30,135` |
| 4 | **(지표, 관측일) 유일** — 재수집은 UPSERT 로 대체 | `V1:28` (`uq_iv_indicator_date`) |
| 5 | **필수 필드 강제** — `indicatorCode`·`observedDate`·`value`·`source` 누락은 생성 시점 거부 | `domain/IndicatorValue.java:14,17,20,23` |
| 6 | **파생값은 저장하지 않고 계산한다** — 변동액·변동률은 `changeFrom(previous)` 로 즉석 산출 | `IndicatorValue.java:28,30-46` |
| 7 | **이종 지표 혼합 차단** — 다른 지표끼리 변동 계산을 시도하면 거부 | `IndicatorValue.java:35-38` |
| 8 | **0 나눗셈은 null 로** — 직전 값이 0 이면 변동률을 `null` 로 둔다(0 이나 무한대로 위장하지 않는다). 변동액은 그대로 제공 | `IndicatorValue.java:41-44` |
| 9 | **변동률 스케일 고정** — `divide(previous, 4, HALF_UP)` | `IndicatorValue.java:44` |
| 10 | **공개 GET / 관리 게이트 이원화** — 조회는 공개, 수집 트리거는 `X-Internal-Api-Key` 필요 | `adapter/in/web/IndicatorController.java:25` vs `EconomicsSyncAdminController.java:33` |

## 이벤트 계약

**발행 0 · 소비 0** — Kafka 미사용 공개 read-only 위성. shared-common 미의존.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 지표 추가가 코드 변경 없이 row 추가로 끝난다 | `V1__economics_core.sql:34-36` 카탈로그 시드 + `IndicatorPersistenceAdapterTest` |
| AC-2 | (지표, 관측일) 중복 적재가 행을 늘리지 않는다 | `uq_iv_indicator_date` · `EcosSyncServiceTest` |
| AC-3 | 월별 지표가 1일로 정규화된다 | `EcosApiClientTest` |
| AC-4 | 파생값 계산의 경계(이종 지표·0 나눗셈)가 방어된다 | `./gradlew :economics-service:test` — `IndicatorValue` 단위 테스트 |
| AC-5 | 수집 실패가 조용히 성공으로 보이지 않는다 | `EcosSyncServiceTest` · `EconomicsSyncSchedulerTest` |
| AC-6 | 헥사고날 의존 방향 위반 0 | `HexagonalArchitectureTest` |
| AC-7 | `/admin/economics/sync` 가 키 없이 통과하지 않는다 | `AdminApiKeyFilterTest` · `EconomicsSyncAdminControllerTest` |
| AC-8 | 커버리지 LINE >= 90% | `./gradlew :economics-service:jacocoTestCoverageVerification` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** **`ValueSource` 가 도메인 언어로 정리되지 않았다** — 여기는 `SEED`/`ECOS` 그대로인데
  (`domain/ValueSource.java:4`, `V1:25,29`), 형제 서비스 market 은 같은 문제를 인지하고
  `SAMPLE`/`EXCHANGE` 로 재정의했다(`V20260810160000` 주석: "적재 수단·공급자 이름은 도메인 상수가 아니다").
  같은 논리가 economics 에도 그대로 적용되지만 반영되지 않아 **두 위성이 서로 다른 어휘를 쓴다**.
  → `disposition: recorded-not-fixed` (일관성 부채 — 소비측이 두 규칙을 외워야 한다)
- **KI-2** 도메인이 필수값·이종지표 검증에 **generic `IllegalArgumentException`** 을 쓴다
  (`IndicatorValue.java:14-23,36`). guard 의 `OO-DOMAIN-GENERIC-IAE` 는 금융 5서비스 한정이라 대상 밖.
  → `disposition: recorded-not-fixed` (게이트 비대칭)
- **KI-3** 카탈로그가 데이터라는 장점의 이면 — **스펙과 실제가 어긋나면 마이그레이션 주석으로만 남는다**.
  실제로 `BASE_RATE` 는 설계 스펙상 M 인데 ECOS 722Y001 이 일별이라 D 로 정정했고, 그 근거가
  `V1:35-36` 주석에만 있다. 카탈로그 값의 출처·정정 이력을 추적할 구조가 없다.
  → `disposition: recorded-not-fixed`
- **KI-4** 변동률이 `null` 일 수 있는데(불변식 8) 이 의미("계산 불가"이지 "변동 없음"이 아님)를
  API 응답 계약에 명시했는지는 이 Seed 범위에서 확인하지 않았다. 소비측이 `null` 을 0 으로 읽으면 오독이다.
  → `disposition: recorded-not-verified`
