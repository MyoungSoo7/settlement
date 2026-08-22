# Seed — settlement-service 리포트 as-is 사양 (캐시플로우·매출통계·기간 대사)

> **상태: CONFIRMED** (역산 결정화, 2026-08-23) · 정본 데이터: [`settlement-service-report.seed.yaml`](settlement-service-report.seed.yaml)
> 자매 Seed: [회계 코어](settlement-service-accounting-core.seed.md) · [세무](settlement-service-tax.seed.md) ·
> [지급후 회수](settlement-service-recovery.seed.md) · [월마감·정합성](settlement-service-closing-integrity.seed.md)
>
> `report` 는 회계 코어 Seed 의 제외 목록에 이름이 있었고, 2026-08-22 감사에서도 **R2-5 로 남았던
> 마지막 슬라이스**다. 이 문서로 settlement 10슬라이스가 전부 Seed 범위 안에 들어온다.
>
> **as-is 원칙** — 결함은 교정하지 않고 Known Issues 로만 기록한다.

## Goal (한 줄)

**settlement-service 리포트 슬라이스(기간 캐시플로우 집계·PDF·매출 통계 축별 구성비·전기 대비·
리포트 기간 대사 3종)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화해,
회귀 기준선 · 표시 규약(반올림·스케일·null 의미) 드리프트 방지 · 면접/포트폴리오 문서로 사용한다.**

## 이 슬라이스의 성격

읽기 전용이다 — 상태를 바꾸는 경로가 없다. 그래서 위험이 **금액을 잘못 움직이는 것**이 아니라
**같은 데이터를 두 화면이 다르게 말하는 것**에 있다. 이 Seed 의 불변식 대부분이
"누가 계산하고 누가 반올림하는가"를 못 박는 이유가 그것이다.

## 범위

| 포함 | 제외 |
|------|------|
| 기간 규약(366일 상한·반개구간·직전 기간 정의) | 정산·지급·원장 도메인 규칙(→ 회계 코어) |
| 표시 규약(스케일·반올림·null 의미·구성비 합) | 프로젝션 뷰 적재·백필(→ `projection-view-ops` 스킬) |
| 축별 집계 5종과 SQL 주입 차단 방식 | 정합성 스위트 8종(→ 월마감·정합성 Seed) |
| 리포트 기간 대사 3종과 실패 알림 경로 | PDF 렌더링 라이브러리 내부(iText) |
| 캐시플로우 PDF 산출 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

> 경로 접두: `settlement-service/src/main/java/github/lms/lemuel/report/`

### 기간

1. **조회 기간 상한은 366일이고 그 이유가 코드에 있다** — 제한이 없으면 **연도 오타 한 번
   (2026 → 2016)이 전 기간 스캔이 되어 운영 DB 를 물고 늘어진다**. 366은 "윤년 1년"이라 연간
   리포트가 경계에 딱 맞는다 (`domain/ReportPeriod.java:9-12,20,32-35`).

2. **기간 역전·null 도 도메인이 막는다** — `InvalidReportPeriodException`
   (`ReportPeriod.java:24-31`). 일수는 **양 끝 포함**이다(`daysBetween = between + 1`).

3. **"직전 기간"은 같은 길이를 바로 앞에 붙인 것이지 "지난달"이 아니다.**
   1월(31일)의 직전은 12월(31일)이다 — **달 길이가 들쭉날쭉해 비교가 흔들리는 것보다 길이를
   고정하는 편이 읽기 쉽다** (`ReportPeriod.java:14-17,55-58`).

4. **`previous()` 는 검증 팩토리를 거치지 않고 정본 생성자를 직접 쓴다** — 상한 검증이
   **내부 파생까지 막지 않게** 하려는 의도다(주석 명문, `ReportPeriod.java:36-38,55-58`).

5. **SQL 에는 반개구간을 준다** — `endExclusive()` 가 종료 다음 날을 돌려주어
   `settlement_date < ?` 로 쓰게 한다 (`ReportPeriod.java:51-52`).

### 표시 규약 — "누가 계산하고 누가 반올림하는가"

6. **분모가 0이면 증감률은 `null` 이다. 이 한 줄이 `SalesComparison` 의 존재 이유다**
   (`domain/SalesComparison.java:9-19`):
   - `0%` 로 답하면 **"변화 없음"으로 읽혀 정반대의 사실을 전한다** — 실제로는 0에서 매출이 생긴 것이다
   - `∞`·큰 수로 답하면 화면의 축이 무너진다
   - "비교할 직전 값이 없다"는 숫자가 아니라 **상태**라서 `null` 로 넘겨 화면이 `—` 로 그리게 한다

7. **증감률 판정은 축(거래액·순정산액·건수)마다 따로 한다** — 축마다 분모가 다르기 때문이다
   (`SalesComparison.java:31-44`).

8. **비율 스케일은 소수 4자리이고 백분율 변환은 화면 몫이다**(`0.0000` = 0%, `1.0000` = +100%,
   `SalesComparison.java:22,25`). **반면 구성비는 0~100 스케일로 서버가 확정한다**
   (`75.00`, 0.75 아님) — **화면이 다시 100을 곱하는 일이 없도록** (`domain/SalesShare.java:6-10`).
   두 규약이 다르다는 점이 이 슬라이스에서 가장 헷갈리는 지점이다.

9. **구성비는 도메인이 계산한다** — 화면에서 나누면 **반올림 규칙이 화면 수만큼 생긴다**.
   소수 둘째 자리 반올림 하나로 고정한다 (`domain/SalesBreakdown.java:14-18,21-22`).

10. **구성비 합이 정확히 100.00 이 아닐 수 있고 그건 정상이다**(33.33 × 3 = 99.99).
    **억지로 100을 맞추려 잔여를 특정 구간에 몰아주면 그 구간만 실제와 다른 값을 갖게 된다**
    (`SalesBreakdown.java:16-18`).

11. **분모가 0이면 구성비는 0을 준다** — 전 구간이 0원인 기간에서 나눗셈으로 터지지 않는다
    (`SalesBreakdown.java:44-45`).

12. **정렬도 도메인이 잡는다** — SQL `ORDER BY` 에 맡기면 축을 늘릴 때마다 순서 규칙이 따로 생기고
    **실행계획이 바뀌면 동점 구간의 순서가 흔들린다**. 거래액 내림차순 + **동점은 라벨로 확정**
    (`SalesBreakdown.java:11-13,35-37`).

13. **라벨이 비어 오면 `UNKNOWN` 으로 고정한다** — `payment_method` 가 NULL 인 옛 결제가 실제로 있고,
    그대로 흘리면 화면에 빈 칸이 생긴다 (`domain/SalesSlice.java:11-13,20,22`).

14. **환불률은 `refunded / gmv`, 소수 4자리, `gmv = 0` 이면 0** (`domain/CashflowTotals.java:16-17,34-36`).

15. **`CashflowReport` 컴팩트 생성자가 null 을 방어한다** — `buckets` null → 빈 목록,
    `totals` null → 버킷 합으로 파생, `reconciliation` null → `empty()`
    (`domain/CashflowReport.java:17-34`). `from > to` 는 여기서도 거부한다.

16. **`groupBy` 는 빈 값이면 `DAY`, 미지원 값이면 도메인 예외**다
    (`domain/BucketGranularity.java:12-23`). `IllegalArgumentException` 을
    `ReportInvariantViolationException` 으로 감싸 도메인 예외 계열을 유지한다.

### 집계와 MSA 경계

17. **집계 축을 enum 으로 고정한 이유는 SQL 이다.** 집계 컬럼을 문자열로 받으면 **그 자리가 곧
    주입 지점**이 된다 — 열거된 다섯 개 밖의 값은 애초에 어댑터에 도달하지 못한다
    (`domain/SalesDimension.java:6-8`). 어댑터의 `switch` 가 축→컬럼을 상수로 매핑한다
    (`adapter/out/persistence/SalesStatsJdbcAdapter.java:118-126`).

18. **다섯 축은 두 성격이다** — 앞의 셋(결제수단·셀러등급·정산상태)은 **구성비**(값의 가짓수가 적어
    전부 보여도 된다), 뒤의 둘(셀러·상품)은 **랭킹**(값이 계정 수만큼 늘어 상위 N 으로 잘라야 한다).
    **계산은 같으므로 타입은 나누지 않고 상위 N 클램프만 서비스가 공통으로 건다**
    (`SalesDimension.java:10-12`).

19. **상위 N 은 1~100 으로 클램프하고 0·음수는 1로 올린다** — `LIMIT 0` 은 **오류 없이 빈 화면을
    만들어 "데이터가 없다"로 오독된다** (`application/service/SalesStatsService.java:37-38,58-60`).

20. **직전 기간은 서비스가 스스로 잡는다** — 화면이 분모를 넘기게 두면 **화면마다 "전기"의 정의가
    달라져 같은 데이터에서 다른 증감률이 나온다** (`SalesStatsService.java:22-24,44-46`).
    호출 순서가 곧 비교의 방향이다(현재가 분자, 직전이 분모).

21. **집계는 `settlements` + settlement 소유 프로젝션만 읽는다** — 결제수단·셀러·상품명을 order
    원천이 아니라 로컬 프로젝션(`settlement_{payment,order,product,user}_view`)에서 읽어
    **settlement_db 단독으로 대시보드가 성립한다** (ADR 0020, `SalesStatsJdbcAdapter.java:14-19`).

22. **전부 `LEFT JOIN` 인 이유가 명문화돼 있다.** 프로젝션은 이벤트로 따라오므로 잠깐 비어 있을 수
    있는데, `INNER JOIN` 이면 **그 사이의 정산이 집계에서 조용히 사라져** 합계가
    `/api/reports/cashflow` 와 어긋난다. `LEFT JOIN` 이면 라벨만 비고(→ `UNKNOWN`) 금액은 남는다 —
    **없는 척하는 것보다 모른다고 말하는 편이 정직하다** (`SalesStatsJdbcAdapter.java:21-25`).

23. **합계는 프로젝션을 아예 타지 않는다** — `totals` 는 `settlements` 단독 집계라
    **프로젝션 지연과 무관하게 항상 캐시플로우 리포트와 같은 값을 낸다**
    (`SalesStatsJdbcAdapter.java:27-29`).

24. **날짜 축은 `settlement_date` 로 통일한다** — 캐시플로우 리포트와 같은 축이라야 두 화면이
    같은 말을 한다 (`SalesStatsJdbcAdapter.java:31-32`).

### 기간 대사

25. **리포트 생성 시 대사 3종을 함께 돌린다**
    (`application/service/GenerateCashflowReportService.java:30-35,97-113`):

    | # | 이름 | 규칙 |
    |---|---|---|
    | 1 | `payments_minus_refunds_equals_settlement` | `Σ(결제 − 환불) = Σ(정산 net + commission)` — 결제→정산 금액 보존 |
    | 2 | `adjustments_equal_linked_refunds` | `\|Σ조정\| = Σ(조정에 연결된 환불)` — 조정·환불 원장 정합성 |
    | 3 | `outbox_published_equals_settlements_created` | `count(outbox PaymentCaptured PUBLISHED) = count(정산 생성)` — 이벤트 파이프라인 원자성 |

26. **`matched` 는 모든 개별 체크가 통과한 경우에만 true 다** — 하나라도 실패하면 **금액이 샜다는
    의미**라 Alertmanager 연계 대상이다 (`domain/CashflowReconciliation.java:6-10,17-24`).

27. **체크 통과 판정은 `expected − actual == 0`(`compareTo`)이다** — 허용 오차가 없다.
    차이는 `discrepancy` 로 함께 남는다 (`domain/ReconciliationCheck.java:24-29`).

28. **셀러 스코프 조회는 대사를 건너뛴다** — 대사는 **시스템 전체 불변식이라 판매자 단위에는 의미가
    없다**. `CashflowReconciliation.empty()`(matched=true, checksRun=0)를 돌려준다
    (`GenerateCashflowReportService.java:76-79`).

29. **불일치는 두 갈래로 알린다** — 즉시 감시 가능한 `ERROR` 로그 + **실패한 체크마다 `check` 태그를
    단 Counter 증가**(`cashflow_reconciliation_mismatch_total`). Alertmanager 가
    `rate(...[1h]) > 0` 으로 감시한다 (`GenerateCashflowReportService.java:31,81-92`).

30. **생성 지연은 Timer 로 관측하고 Timer 는 한 번 만들어 재사용한다** — 반복 호출 시 MeterRegistry
    조회 비용을 없앤다. p50/p95/p99 퍼센타일 발행
    (`cashflow_report_generation_duration_seconds`, `GenerateCashflowReportService.java:27,46-52`).

31. **캐시플로우 조회는 감사로그를 남긴다** — `@Auditable(CASHFLOW_REPORT_ACCESSED)` 에 기간·
    granularity·sellerId 를 기록한다 (`GenerateCashflowReportService.java:56-61`).

## 인터페이스

`/api/reports/**` 는 **ADMIN·MANAGER 전용**이다
(`shared-common/.../SecurityConfig.java:250` — `hasAnyRole("ADMIN", "MANAGER")`).

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/reports/cashflow` | 기간 캐시플로우 + 대사 3종 |
| GET | `/api/reports/cashflow/pdf` | 같은 리포트를 PDF 로(`application/pdf`, `attachment`) |
| GET | `/api/reports/sellers/{sellerId}/cashflow` | 판매자별 — 대사는 빈 값 |
| GET | `/api/reports/sales-stats/summary` | 기간 합계 + 전기 대비 |
| GET | `/api/reports/sales-stats/breakdown` | 축별 구성비 상위 N |

`400` 매핑(잘못된 `groupBy`·필수 파라미터 누락)은 shared-common `GlobalExceptionHandler` 로
일원화돼 있다 (`adapter/in/web/ReportController.java:93-94` 주석).

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 366일 초과·기간 역전이 거부된다 | `ReportPeriodTest` |
| AC-2 | 직전 기간이 "같은 길이 바로 앞"으로 계산된다 | `ReportPeriodTest` |
| AC-3 | 분모 0에서 증감률이 `0` 이 아니라 `null` 이다 | `SalesComparisonTest` |
| AC-4 | 구성비가 0~100 스케일·소수 2자리이고 합이 100 을 넘도록 보정되지 않는다 | `SalesBreakdownTest` |
| AC-5 | 동점 구간 정렬이 라벨로 확정된다 | `SalesBreakdownTest` |
| AC-6 | 빈 라벨이 `UNKNOWN` 으로 고정된다 | `SalesBreakdownTest` |
| AC-7 | 상위 N 이 1~100 으로 클램프된다(0 → 1) | `SalesStatsServiceTest` |
| AC-8 | 미지원 `groupBy` 가 도메인 예외로 거부된다 | `BucketGranularityTest` |
| AC-9 | 합계·환불률이 버킷 합에서 파생된다(`gmv=0` → 0) | `CashflowTotalsTest` · `CashflowReportTest` |
| AC-10 | 대사 3종이 전부 통과해야 `matched` 다 | `CashflowReconciliationTest` |
| AC-11 | 불일치 시 체크별 Counter 가 증가한다 | `GenerateCashflowReportServiceTest` |
| AC-12 | 셀러 스코프 조회가 대사를 건너뛴다 | `GenerateCashflowReportServiceTest` · `ReportControllerTest` |
| AC-13 | PDF 가 `application/pdf` + `attachment` 로 나간다 | `ReportControllerPdfTest` · `CashflowPdfAdapterTest` |
| AC-14 | ~~`/api/reports/**` 가 ADMIN·MANAGER 로 제한된다~~ | **게이트 없음 — KI-7** |
| AC-15 | 모든 인바운드 포트가 어댑터에서 도달 가능하다 | `InboundPortReachabilityTest` (ArchUnit) |
| AC-16 | 커버리지 LINE >= 90% | `./gradlew :settlement-service:jacocoTestCoverageVerification` |

> AC-14 는 **의도적으로 미충족 상태로 남긴다**. 규칙은 `SecurityConfig` 에 선언돼 있으나
> 그것을 어서트하는 테스트가 없다(KI-7). 없는 게이트를 있는 것처럼 적지 않는다.

**테스트 자산**: 13개 클래스 — 도메인 7(`ReportPeriodTest`·`SalesComparisonTest`·`SalesBreakdownTest`·
`CashflowTotalsTest`·`CashflowReportTest`·`CashflowReconciliationTest`·`BucketGranularityTest`) ·
서비스 2 · 컨트롤러 3 · PDF 어댑터 1.

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1 ★ 셀러 축 라벨이 셀러 이메일 원문이다.** `SalesDimension.SELLER` 의 라벨은
  `COALESCE(MAX(uv.email), 'seller#' || pv.seller_id)` 로, **프로젝션에 이메일이 있으면 그대로
  응답에 실린다** (`SalesStatsJdbcAdapter.java:122-123`). 응답 DTO에도 마스킹이 없다
  (`dto/SalesBreakdownResponse.java:29,37`). ADMIN·MANAGER 전용이라 무인증 노출은 아니지만,
  프로젝트의 PII 마스킹 규율과는 어긋난다.
  → `recorded-not-fixed` (마스킹 적용 후보 — 셀러 식별은 `seller#{id}` 로도 성립한다)

- **KI-2 감사로그가 두 경로에서 비대칭이다.** 캐시플로우는 `@Auditable(CASHFLOW_REPORT_ACCESSED)`
  로 남지만(불변식 31) **매출 통계(`/sales-stats/**`)에는 `@Auditable` 이 없다**
  (`SalesStatsService.java` 전체). 그런데 `SELLER` 축 조회는 **셀러별 거래액 랭킹**을 그대로
  드러낸다 — 민감도가 캐시플로우보다 낮지 않은데 누가 봤는지 기록이 없다.
  → `recorded-not-fixed` (감사 범위 격차)

- **KI-3 대사가 "누군가 리포트를 열 때"만 돈다.** 대사 3종은 `generate()` 안에서 실행되므로
  **아무도 캐시플로우를 조회하지 않으면 금액 누수가 감지되지 않는다.** 주기적으로 호출하는 배치가
  없다(`report` 슬라이스에 `@Scheduled` 0건). 월마감·정합성 Seed 의 KI-1 과 같은 결의 격차이며,
  그쪽 일일 모니터(06:00)는 **이 3종을 돌리지 않는다**.
  → `recorded-not-fixed` (③ 발동 층 격차)

- **KI-4 대사 #3 은 짧은 기간에서 거짓 불일치가 날 수 있다.** `count(outbox PUBLISHED) =
  count(정산 생성)` 은 **경계 시각 오차**가 있어 코드 주석이 **월 단위 기간을 권장**한다
  (`GenerateCashflowReportService.java:104-106`). 그러나 기간 제약은 없어 **하루 조회에서도 그대로
  돌고 Counter 를 올린다** — Alertmanager 가 `rate > 0` 으로 감시하므로 짧은 기간 조회 한 번이
  알림을 만들 수 있다.
  → `recorded-not-fixed` (임계·기간 조건 부재)

- **KI-5 PDF 경로가 컨트롤러에서 아웃포트를 직접 부른다.** `ReportController` 가
  `RenderCashflowReportPdfPort`(=`application.port.out`)를 주입받아 호출한다
  (`ReportController.java:6,37,60`). 현행 ArchUnit 은 `domain → application/adapter` 와
  `application → adapter` 만 막고 **`adapter.in → application.port.out` 은 막지 않아** 통과한다.
  실질 피해는 없으나(감사·집계는 `generate()` 를 먼저 타므로 그대로 적용된다) PDF 조립 책임이
  애플리케이션 계층에 없다.
  → `recorded-not-fixed` (구조 일관성 — 규칙을 늘릴지 결정 사항)

- **KI-7 역할 제한에 회귀 가드가 없다.** `/api/reports/**` = ADMIN·MANAGER 는
  `shared-common/.../SecurityConfig.java:250` 한 줄에만 있고, **이를 어서트하는 테스트가 리포트 쪽에도
  shared-common 쪽에도 없다**(`ReportControllerTest`·`SalesStatsControllerTest`·`ReportControllerPdfTest`
  모두 인가를 세우지 않는다). 그 한 줄이 지워지거나 매처 순서가 바뀌어도 **테스트는 전부 초록이다** —
  셀러별 거래액과 이메일(KI-1)이 열리는 방향의 회귀인데 컴파일러도 테스트도 보지 못한다.
  → `recorded-not-fixed` (게이트 공백 — AC-14 가 이것을 가리킨다)

- **KI-6 `/sellers/{sellerId}/cashflow` 는 이름과 달리 셀러가 못 쓴다.** `/api/reports/**` 가
  ADMIN·MANAGER 이므로 이 경로는 **운영자가 특정 셀러를 들여다보는 용도**다. 셀러 자가 조회 경로는
  이 슬라이스에 없다 — 경로명이 소유자 기반 접근을 시사해 **IDOR 대조가 있는 것으로 오독되기 쉽다**
  (실제로는 소유권 대조 코드가 없고, 필요도 없다).
  → `by-design-documented` (셀러 자가 리포트가 생기면 그때 IDOR 대조가 필요해진다)
