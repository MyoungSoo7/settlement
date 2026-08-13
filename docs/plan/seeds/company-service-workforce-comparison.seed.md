# Seed — company-service 국민연금 사업장 업종·지역 비교 조회

> **상태: IMPLEMENTED** (2026-07-30 구현 완료) · 정본 데이터: [`company-service-workforce-comparison.seed.yaml`](company-service-workforce-comparison.seed.yaml)
> Ouroboros Interview → Seed 로 결정화 (`interview_20260729_095356`, ambiguity 0.047) → 직접 TDD 구현.
> (Ouroboros 실행 경로는 Windows 에서 MCP 백그라운드 잡이 `WinError 87` 로 죽어 인수 기준으로만 사용했다.)
> 게이트: `:company-service:test` + `jacocoTestCoverageVerification`(LINE 90%) + `HexagonalArchitectureTest` +
> `guard.mjs` 통과. 집계·조회 SQL 은 어댑터 소스에서 추출한 원문을 실 PostgreSQL 17 에 걸어 검증
> (AC-5 표본수 대사 불일치 **0건**, 백분위 분모 = 표본수, 동률 동일 백분위, 추정연봉 SQL 역산 = 도메인 계산).

## Goal (한 줄)

**company-service 의 국민연금 사업장 데이터에 업종코드를 추가해 전량 재적재하고, 복합키로 지목하는
단건 상세 엔드포인트에서 같은 업종·같은 지역 집단 대비 인원수·추정연봉의 중앙값 차이와
백분위(cume_dist)를 사전집계 기반으로 제공하되, 표본 10 미만이면 업종은 코드 앞3자리·지역은 시도로
한 단계만 넓히고 그래도 미달이면 사유코드와 함께 null 을 반환하며, 기준소득월액 상한 도달 여부를
별도 플래그로 알린다.**

## 왜 이걸 하는가

`company_workforce` 는 이미 적재돼 있고 `CompanyWorkforce.estimatedAnnualSalary()` 가 추정연봉까지
역산한다. 하지만 **숫자 하나만으로는 해석이 안 된다** — "연봉 4,200만원"이 높은지 낮은지 알려면 비교
대상이 필요하다. 이 Seed 는 그 비교 맥락을 붙인다.

인터뷰에서 확인된 제약: 사업자등록번호가 앞 6자리만 공개돼 다른 서비스(상장사 stockCode, 셀러 조직)와
**자동 조인이 불가능**하다. 그래서 loan/investment 연계를 포기하고 company-service 단독으로 완결한다.

## 범위

| 포함 | 제외 |
|------|------|
| `industry_code` 컬럼 추가 + 전량 재적재 | loan·investment 등 외부 서비스 연계 |
| 복합키 단건 상세 엔드포인트 (신규) | 기존 목록 검색 API 변경 |
| 업종 비교 (6자리 → 앞3자리 폴백) | 업종×지역 교차 집단 비교 |
| 지역 비교 (시도+시군구 → 시도 폴백) | 평균 기반 비교 (중앙값만) |
| 상한 도달 신뢰도 플래그 | 월별 시계열 추이 |
| 월별 사전 집계 + 사업장별 백분위 사전 계산 | 전국 랭킹 탐색 |

## 핵심 설계 결정 (인터뷰에서 확정)

1. **업종·지역을 각각 따로** — 교차(업종 AND 지역)는 모수가 급감해 한 자릿수 표본이 되는 조합이 많아 배제.
2. **평균이 아니라 중앙값** — 소수 대기업이 평균을 왜곡한다. 백분위는 `cume_dist`("값 이하 비율")를
   쓴다. `percent_rank` 는 작은 집단에서 최저=0%·최고=100% 로 고정돼 왜곡이 크다.
3. **업종코드를 살린다** — 원본 CSV 에 업종코드(6자리)와 업종명이 **둘 다** 오는데 현행 코드는 명만
   저장하고 코드를 버린다. 코드가 없으면 표본 미달 시 상위 분류로 넓히는 폴백이 **원천 봉쇄**된다.
   그래서 컬럼 추가 + 전량 재적재를 범위에 넣었다. 롤업 단위는 앞 3자리(국세청 업종코드 연계표 기준).
4. **백분위는 조회 시 계산하지 않는다** — `cume_dist` 는 대상 값에 종속돼 집단 중앙값처럼 접어둘 수
   없다. 그러나 비교 대상은 언제나 같은 월 데이터에 존재하는 사업장이므로, **적재 시점에 사업장별로
   미리 계산**해 `WorkforcePercentile` 에 저장한다. 조회 경로는 읽기만 한다.
5. **상한 도달은 실패가 아니라 신뢰도 플래그** — 기준소득월액 상한 때문에 고연봉 사업장은 전부 같은
   값에 몰려 백분위가 무의미해진다. 상한액은 공표된 고정 테이블이라 계산 가능하다.
6. **식별자는 복합키** — 내부 시퀀스 id 를 API 계약에 넣지 않는다. ⚠️ 실제 데이터에
   `(유)케이비에프에스"전주밥상 다잡수소!"` 같은 따옴표·느낌표 포함 사업장명이 있으므로
   path variable 이 아니라 **query parameter** 로 받아 표준 URL 인코딩으로 처리해야 한다.

## 데이터 원천의 한계 (해석 시 반드시 인지)

공공데이터포털 `국민연금공단_국민연금 가입 사업장 내역` 명세 기준:

- **수록 범위가 3인 이상 법인사업장 / 10인 이상 개인사업장(2025.7 이후)** — 소규모가 이미 잘려 나간
  **절단 표본**이다. 산출되는 "업종 중앙값"은 전체 기업 중앙값이 아니다.
- **당월고지금액에 기준소득월액 상한이 적용**돼 실제 소득과 다르다 (원문 유의사항 명시).
  상한액: `2022-07~2023-06` 5,530,000 / `2023-07~2024-06` 5,900,000 / `2024-07~2025-06` 6,170,000 /
  `2025-07~2026-06` 6,370,000 / `2026-07~2027-06` 6,590,000
- **업종코드 공란 행이 존재**한다 ("사업장의 미신고로 업종코드 등 공란존재"). `INDUSTRY_CODE_MISSING`
  분기는 이론적 예외가 아니라 실제로 발생하는 경로다.
- 갱신 주기는 월간.

## 수용 기준 (5개)

| AC | 기준 |
|----|------|
| AC-1 | 목록 검색 응답 불변 + 복합키 상세 엔드포인트, 미매칭 404 / 형식 위반 400 |
| AC-2 | 업종 비교 완결 — 두 지표 각각 중앙값·차이·증감률·백분위·표본수·비교단계, 10 미만 시 앞3자리 폴백, 미달 `SAMPLE_TOO_SMALL`, 코드 없음 `INDUSTRY_CODE_MISSING` |
| AC-3 | 지역 비교 완결 — 업종과 독립, 10 미만 시 시도 폴백, 미달 `SAMPLE_TOO_SMALL`, 파싱 실패 `REGION_UNPARSEABLE` |
| AC-4 | 추정연봉 ≥ 상한액×12 이면 `salaryCapReached=true`, 비교 성패와 무관하게 항상 제공 |
| AC-5 | 중앙값·표본수·백분위가 하나의 원자적 교체로 갱신, `sampleSize` = 동일 월·축·그룹키 적격 레코드 수, `sourceRowCount = acceptedRowCount + rejectedRowCount` |

프로젝트 공통 게이트(별도 명시 없이 적용): `:company-service:test` ·
`:company-service:jacocoTestCoverageVerification`(LINE ≥ 90%) · `HexagonalArchitectureTest` ·
`guard.mjs` OO-*/MONEY-* 규칙.

## 구현 시 확정한 미결 7항목

Seed 작성 시점엔 "아직 정하지 않은 것"이었고, 구현(2026-07-30)에서 아래로 확정했다.
근거·부속 규칙 전문은 정본 YAML 의 `deferred_to_implementation` / `implementation_decisions` 참조.

| # | 항목 | 결정 |
|---|------|------|
| 1 | required vs nullable | 비교 **객체는 항상 존재**(사유코드 운반). null 은 지표 하위객체·`industryCode`·`sido`/`sigungu`·`level`/`groupKey`(집단 부재)·`unavailableReason`(성공)·`differenceRate`(중앙값 0)·`salaryCapMonthlyAmount`(고시표 밖) |
| 2 | ErrorResponse | 기존 `{"message"}` 계약 재사용(IAE→400, NoSuchElement→404). 검증 순서 누락 → 형식 → 길이, 파라미터 순 name → bizRegNoPrefix → snapshotMonth. 중복 파라미터는 콤마 병합 → 404 |
| 3 | 적격 레코드 | `headcount > 0 AND monthly_billed_amount > 0`. 거부 행은 미저장, 중복은 UNIQUE 로 불가, 축 그룹키 없는 행은 **그 축만** 제외 |
| 4 | 나눗셈 스케일 | 금액 0자리 · 인원수 1자리 · 비율/백분위 2자리, 전부 HALF_UP |
| 5 | 고시표 밖 월 | 거부하지 않음 — `salaryCapMonthlyAmount=null`, `salaryCapReached=false` |
| 6 | 빌드 세대·복구 | 세대 식별자 없음. 단일 트랜잭션 `BUILDING`→교체→`COMPLETE`, 중단 시 롤백으로 직전 COMPLETE 보존·stale 없음 |
| 7 | 세종시 1토큰 주소 | 시도만 있으면 EXACT 건너뛰고 BROADENED 직행. 시도 판정은 **명칭 목록 대조**(접미사 규칙은 `"주소"`·`"0"` 이형을 시도로 오인) |

## 실적재 검증 (2026-07-30)

국민연금공단 2026-06 스냅샷 원본(115MB · 593,127행)을 실기동 서비스(8090) + PostgreSQL 17 에 적재해 확인했다.

| 항목 | 결과 |
|------|------|
| 적재 대사 | 수신 593,127 = 적재 554,542 + 스킵 38,585, 미귀속 0, build `COMPLETE` |
| 집계 규모 | 집단 1,990개(업종 EXACT 1,576 · 상위 154 / 지역 EXACT 242 · 시도 18) · 집계행 3,980 · 백분위행 4,421,632 |
| **AC-5 전수 대사** | `sample_size` 불일치 집단 **0개**, 백분위 분모 = 표본수 |
| 지역 파싱률 | **100.0%** (554,021/554,190 — 잔여 169건은 주소 자체가 공백) |
| 성능 | 적재 + 집계 재생성 97초. 조회는 사전집계만 읽어 집단 크기와 무관 |

시나리오 8종 실호출: EXACT 정상비교 / 업종 BROADENED 폴백(`940901` 표본 1 → `940` n=1,346) /
세종시 지역 BROADENED 직행 / `SAMPLE_TOO_SMALL`(마지막 시도 단계·집단키·표본수 보존) /
`REGION_UNPARSEABLE`(주소 공백 실제 행 — 업종 비교는 정상 = 축 독립 실증) / 상한 도달 `true`
(78,993,809 ≥ 76,440,000) / 400 4종 · 404 1종 오류 본문 / 따옴표·느낌표 사업장명 200.

## 구현에서 잡은 결함 (실 PostgreSQL 검증)

단위 테스트만으로는 증명되지 않아 실 DB·실기동까지 갔고, 그 과정에서 결함 3건을 잡았다.

1. **백분위 분모 불일치** (SQL 검증) — `WHERE biz_reg_no_prefix <> ''` 를 창 함수와 같은 질의에 두었더니
   SQL 평가 순서(WHERE → 윈도우) 때문에 `cume_dist` 분모가 14가 아니라 12가 되어 중앙값 모집단과 백분위
   모집단이 어긋났다. 필터를 바깥 질의로 옮기고, "공란 필터는 `) ranked` 이후에만 등장"을 테스트에 고정.
2. **금액이 수치로 직렬화** (실기동) — Jackson 2 `@JsonSerialize(ToStringSerializer)` 는 Boot 4 런타임
   (HTTP 변환이 Jackson 3 `tools.jackson`)에서 무시된다. 단위 테스트가 Jackson 2 컨버터를 수동 배선해
   가짜 GREEN 이었다. DTO 필드 타입을 `String` + `toPlainString()` 으로 바꾸고(Outbox 컨벤션과 동일),
   테스트는 원시 JSON 의 따옴표까지 확인하게 했다.
3. **시도 명칭 목록이 실데이터를 못 따라감** (실적재) — 목록에 없던 통합 명칭(`전남광주통합특별시`) 때문에
   34,064건(6.2%)이 통째로 `REGION_UNPARSEABLE` 로 떨어졌다. 명칭 목록은 행정통합·개편마다 조용히 낡는
   실패 모드가 있어 **광역 단위 접미사 규칙**으로 교체(파싱률 93.8% → 100.0%).

부수적으로, 이 검증 과정에서 **제 변경과 무관한 기존 기동 불가 버그**도 드러나 함께 고쳤다 — 아래 참조.

## 함께 고친 선행 결함 (company-service 기동 불가)

`659b7a560`(outbox 금액 wire 표준화)에서 발행 어댑터가 `@Qualifier("outboxObjectMapper")` 를 요구하게
됐지만, company-service 는 스캔을 `company` + `common.outbox` 로 한정해 그 빈이 있는
`common.config.JacksonCompatConfig` 를 보지 못했다. 그래서 이 서비스는 로컬이든 jar 든 **부팅 자체가
불가능**했다(`CompanyReputationEventPublisherAdapter` 주입 실패). CLAUDE.md 가 경고하는 "제한 스캔 서비스에
shared-common 빈 추가 시 `@Import` 필요" 함정이 실제로 발화한 사례다. `HttpClientConfig` 에
`@Import(JacksonCompatConfig.class)` 를 붙여 해소하고, 중복이 된 로컬 `newsObjectMapper` 빈은 제거했다
(공용 빈이 상위집합). 회귀 가드로 `ConfigBeansTest` 가 `@Import` 존재를 어서트한다.
다른 제한 스캔 서비스는 2026-07-30 점검 완료 — ai·account·operation 은 Outbox 발행 어댑터
자체가 없어(`outboxObjectMapper` 미사용) 해당 없음, 발행 서비스(loan·investment·organization 등)는
전역 스캔 또는 기존 배선으로 정상이다.

## 산출 이력

| 단계 | 결과 |
|------|------|
| Interview | `interview_20260729_095356`, ambiguity **0.047** (게이트 ≤ 0.2 통과) |
| Seed 생성 | `seed_031a1527dd6a` |
| QA 정련 | 5회 — 0.72 → 0.68 → 0.76 → 0.76 → **0.82** (임계값 0.90 미달 상태로 명시 수용) |
| 최종 편집 | AC 6개 → 5개(형제 중복 병합), `AggregateBuild` 에 accepted/rejectedRowCount 추가 |

QA 정련 중 잡은 실제 결함 2건:

- **백분위 ↔ "조회 시 실시간 집계 금지" 정면충돌** — 사업장별 백분위 사전 저장으로 해소
  (`Correctness` 0.65 → 0.80).
- **금액 필드가 `type: number`** — 프로젝트 `MONEY-PRIMITIVE` 가드레일 위반 예고.
  `MoneyMetricComparison` / `HeadcountMetricComparison` 분리로 해소 (`Domain Specific` 0.68 → 0.79).

정련 이력 전문(채택·기각 결정 포함)은 `~/.ouroboros/seed-revisions/interview_20260729_095356.md`
(로컬, 미추적).

## 후속 — 프런트엔드 배선 (2026-07-30)

- 목록 검색 응답에 `bizRegNoPrefix` 추가(하위호환 확장) — 상세 복합키 3요소를 목록만으로 채우기
  위함. AC-1 의 "목록 응답 불변"은 구현 당시 기존 소비자 보호 조건이었고, 필드 추가는 이를 깨지 않는다.
- `frontend` 에 사업장 검색·상세 비교 화면 신규(`WorkforcePage`) — ADMIN/MANAGER 전용 `/workforce` + CEO 사이드바
  `/admin/ceo/workforce`(사업장비교). 금액은 문자열 계약 그대로 수신해 표시 시에만 수치화한다.
