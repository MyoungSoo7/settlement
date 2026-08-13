# Seed — insurance-service GA 보험대리점 as-is 사양

> 상태: CONFIRMED (settlement/account seed 와 동일 방식 — 역산 결정화)
> 자매 Seed: `settlement-service-accounting-core`(수수료 정산의 회계 원칙 참조)

## Goal (한 줄)

**insurance-service(GA 보험대리점 — 청약·계약·유지변경·수수료정산 + 방카 규제 규칙)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 규제 규칙 근거 ·
면접/포트폴리오 문서로 쓴다.**

## 범위

**포함**

- 상태머신 3종(청약·계약·수수료)과 전이 강제
- 완전판매 게이트(상품설명서 교부 선행조건)
- 방카슈랑스 25%룰 판정
- 수수료 분급·환수(clawback) 계산 규칙
- 발행 이벤트 표면과 원수사 계약상태 소비

**제외**

- 보험료 산출(PremiumRater) 요율 테이블 상세
- 방카 배치 5종 운영 절차(존재 사실만)

## 핵심 불변식 (as-is, 파일:라인 근거)

경로 접두 `../../../insurance-service/src/main/java/github/lms/lemuel/insurance`

| # | 불변식 | 근거 |
|---|---|---|
| 1 | **청약 상태머신** — SUBMITTED → UNDER_REVIEW → APPROVED / REJECTED, `canTransitionTo` 로 강제 | `domain/ApplicationStatus.java:21-33,46` |
| 2 | **계약 상태머신** — ACTIVE / LAPSED / SURRENDERED / EXPIRED / CANCELLED, 전이 강제 | `domain/PolicyStatus.java:25-40,59` |
| 3 | **수수료 상태머신** — SCHEDULED → PAID → CLAWBACK_PENDING → CLAWED_BACK | `domain/CommissionStatus.java:25-37` |
| 4 | **완전판매 게이트** — 상품설명서 교부 기록이 없으면 청약을 승인할 수 없다. 승인 서비스가 선행 조회 후 거부 | `application/service/ApplicationUnderwritingService.java:118` (`DisclosureNotDeliveredException`) |
| 5 | **교부 증빙은 누가·어느 채널로를 담는다** — `deliveredBy`·`salesChannel`·계약자명이 필수이며 빈 값은 거부 | `domain/DisclosureDelivery.java:12,52,63-72` (`InvalidDisclosureException`) |
| 6 | **방카 25%룰 — 초과가 위반, 정확히 25%는 허용** — 단일 선언 지점 `CONCENTRATION_LIMIT = 0.25`, 비중 스케일 소수 4자리 HALF_UP | `domain/BancaRuleEvaluator.java:12-18,26-27` |
| 7 | **환수 창(window) 24개월** — 유지 개월 `m >= W` 면 환수 0, `m < W` 면 `기지급 합계 × (W − m) / W` 를 통화 최소단위 절사 | `domain/CommissionConstants.java:16-17,24` |
| 8 | **분급 12회** — 수수료 스케줄은 12분급이 기본 | `CommissionConstants.java:27` |
| 9 | **수취 주체 2종** — FC(설계사) / BANK(방카) 로 구분해 수수료 귀속을 표현 | `CommissionConstants.java:36,39` |
| 10 | **금액 스케일 2** — 통화 최소단위 절사 정책을 상수로 고정 | `CommissionConstants.java:33` |

## 이벤트 계약

**발행 4** — `InsurancePolicyIssued` · `InsurancePolicyStatusChanged` · `InsuranceCommissionPaid` ·
`InsuranceGeneralPayoutPaid` (Outbox 경유).

**소비 1** — 원수사 계약상태(`CarrierPolicyStatusConsumer`) → 계약 상태 반영.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 상태머신 3종 전이표·타입 예외가 일치한다 | `./gradlew :insurance-service:test` — 도메인 상태 테스트 |
| AC-2 | 상품설명서 교부 없이 청약이 승인되지 않는다 | `ApplicationUnderwritingService` 테스트 (`DisclosureNotDeliveredException`) |
| AC-3 | 25%룰 경계(정확히 25% 허용, 초과 위반)가 일치한다 | `BancaRuleEvaluator` 테스트 |
| AC-4 | 환수 공식과 24개월 창 경계가 일치한다 | `ClawbackCalculator` 테스트 |
| AC-5 | 12분급 스케줄 생성이 정본과 일치한다 | `CommissionScheduleFactory` 테스트 |
| AC-6 | 원수사 상태 소비가 DLT 배선에 닿는다 | `../../../scripts/harness/guard.mjs` KAFKA-DLQ |
| AC-7 | 헥사고날 의존 방향 위반 0 | ArchUnit 테스트 |
| AC-8 | 커버리지 LINE >= 90% | `./gradlew :insurance-service:jacocoTestCoverageVerification` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** **전용 `*-rules` 스킬이 없다** — organization·deposit 과 함께 3대 공백이며 `../../../HARNESS.md` 가
  "알려진 부채"로 명시한다. 규제성 규칙(25%룰·완전판매·환수)을 가진 서비스인데 온디맨드 규칙 문서가 없다.
  → `disposition: recorded-not-fixed` (문서화된 부채 — 이 Seed 가 임시 근거)
- **KI-2** 25%룰의 **판정 시점이 도메인 함수**라 "언제 평가되는가"(청약 시점 / 배치 / 조회 시)는 이 Seed
  범위에서 확인하지 않았다. 규제 규칙은 판정 시점이 곧 실효성이다. → `disposition: recorded-not-verified`
- **KI-3** 완전판매 게이트가 **존재 여부(`existsForApplication`)만 확인**한다. 교부 시점이 청약 이후여도,
  다른 상품코드로 교부된 기록이어도 통과할 수 있는지는 확인하지 않았다.
  → `disposition: recorded-not-verified` (게이트 강도)
- **KI-4** 환수 계산이 `기지급 합계 × (W − m) / W` 선형 비례다. 실무 GA 계약은 회차별 환수율이 다른 경우가
  많은데 여기서는 단일 공식으로 단순화돼 있다 — 의도된 MVP 단순화인지 문서에 없다.
  → `disposition: recorded-not-verified`
