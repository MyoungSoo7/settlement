# Seed — account-service GL 코어 as-is 사양

> 상태: CONFIRMED (settlement/investment seed 와 동일 방식 — 역산 결정화)
> 자매 Seed: `settlement-service-accounting-core`(전표의 원천 이벤트 발행측), 수신 3종(정기예금·적금·퇴직연금)은 별도 Seed 대상

## Goal (한 줄)

**account-service(전사 GL 집계 — 소비 전용 복식부기 원장)의 현행 동작을 실행 가능한 게이트에 매핑된
불변 사양으로 결정화해, 회귀 기준선 · 계약 드리프트 게이트 · 면접/포트폴리오 문서 · 후속 기능 베이스로 쓴다.**

## 범위

**포함**

- 전표(AccountEntry) 생성 규칙 — 구성적 균형·금액 정규화·팩토리 전용
- 계정과목(GlAccount 14종)과 차/대 방향 고정
- 이벤트→분개 매핑 17토픽 (소비 전용)
- 멱등 3단 방어의 account 측 구현 (processed_events + 자연키 UNIQUE)
- append-only 강제(트리거·CHECK)와 조회 표면(시산표·통제계정 대사)

**제외**

- 수신 3종(정기예금·적금·퇴직연금) 도메인 — 계정과목 경계 사실만 기재, 상세는 별도 Seed
- 감사로그 파티셔닝 운영 절차(마이그레이션 사실만)

## 핵심 불변식 (as-is, 파일:라인 근거)

경로 접두 `account-service/src/main/java/github/lms/lemuel/account/`

| # | 불변식 | 근거 |
|---|---|---|
| 1 | **구성적 균형** — 전표는 차변1·대변1·금액1. 생성자가 private 이라 팩토리로만 만들어지고, 반쪽 전표를 표현할 타입 자체가 없다 | `domain/AccountEntry.java:72,110-123` |
| 2 | **차/대 동일 계정 금지** — DB CHECK 로 이중 강제 | `V20260715141000__account_entry_append_only_hardening.sql:19` (`chk_account_entry_accounts_distinct`) |
| 3 | **금액 정규화 + 조용한 반올림 금지** — Money(scale 2 HALF_UP)로 정규화해 저장하되, scale>2 유입은 예외로 거부한다(원천 금액을 조용히 깎지 않는다) | `AccountEntry.java:123,137-138` → `ExcessivePrecisionEntryAmountException` |
| 4 | **금액 > 0** — 음수·0 전표 불가 | `V1__account_core.sql` `chk_account_entry_amount` |
| 5 | **append-only** — UPDATE/DELETE 를 트리거가 차단. 정정은 역방향 전표로만 | `V20260715141000:38-39` (`trg_account_entry_append_only`) |
| 6 | **멱등 자연키** — `(source_topic, ref_type, ref_id)` UNIQUE 가 3단 방어의 마지막 층 | `V1__account_core.sql:14-16,22` (`uq_account_entry_natural`) |
| 7 | **소비 전용** — 이벤트 발행 0. Outbox 발행 머시너리 미의존 + `kafkaTemplate.send` 직접 호출 금지를 ArchUnit 이 하드스톱 | `AccountArchitectureTest.java:139-160` |
| 8 | **계정과목·방향 고정** — 14종, 각 계정의 차/대 성격이 enum 에 박혀 있다 | `domain/GlAccount.java:13-60`, `domain/AccountSide.java:9-12` |
| 9 | **조정 전표의 leg 검증** — 허용되지 않은 targetLeg 로 조정하면 거부 | `AccountEntry.java:188-190` → `UnbalancedAccountEntryException` |
| 10 | **Option A — 확정은 무전표** — `settlement.confirmed` 는 GL 에 전기하지 않는다. 상계는 `payout.completed` 시점 | `adapter/in/kafka/SettlementConfirmedConsumer.java` `handle()` |
| 11 | **실지급 분할 전기** — `payable = min(amount, max(0, 현재 SELLER_PAYABLE 잔액))` 만 미지급금 상계(DR SELLER_PAYABLE / CR CASH)하고, 초과분은 선지급 채권으로 분리 → 통제계정을 음수로 몰지 않는다 | `application/service/RecordPayoutService.java:72,75` |

## 이벤트 계약

**발행 0** — 소비 전용 서비스다(불변식 7).

**소비 17토픽** → 각 토픽이 정확히 한 종류의 분개로 매핑된다.

| 원천 | 토픽 | 분개 팩토리 |
|---|---|---|
| settlement | `settlement.created` | `settlementCreatedImmediate` (`AccountEntry.java:148`) |
| settlement | `settlement.holdback_released` / `.holdback_consumed` | `holdbackReleased:168` / `holdbackConsumed:178` |
| settlement | `settlement.adjusted` | `settlementAdjusted:188` |
| settlement | `settlement.canceled` | `settlementCanceledPayable:198` (+ `settlementCanceledHoldback:205`) |
| settlement | `settlement.withholding_accrued` | `withholdingAccrued` |
| settlement | `settlement.confirmed` | **무전표**(불변식 10) |
| settlement | `payout.completed` | `payoutCompleted` + `payoutAdvanceReceivable`(분할, 불변식 11) |
| settlement | `seller_recovery.opened` / `.offset` | `recoveryOpened:215` / `recoveryOffset:225` |
| loan | `loan.disbursement_requested` / `.repayment_applied` | `loanDisbursed` / `loanRepaid` |
| loan | `loan.corporate_loan_disbursed` | `corporateLoanDisbursed` |
| loan | `loan.secured_loan_disbursed` / `.secured_loan_repaid` / `.secured_loan_principal_repaid` | 동명 팩토리 3종 |
| investment | `investment.executed` | `investmentExecuted` |

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 전표는 차1·대1·금액1 구성적 균형이며 팩토리 밖 생성 경로가 없다 | `./gradlew :account-service:test` — `AccountEntryTest` |
| AC-2 | 이벤트 발행 0 (Outbox 미의존 + KafkaTemplate.send 직접호출 0) | `AccountArchitectureTest` |
| AC-3 | 같은 이벤트 재소비가 전표를 늘리지 않는다 | `uq_account_entry_natural` + `processed_events` PK, `AccountConsumerParsingTest`·`RecordAccountEntryServiceTest` |
| AC-4 | 시산표 차·대 합이 일치한다 | `TrialBalanceTest`, `GET /api/account/trial-balance` |
| AC-5 | 통제계정 잔액이 보조부와 어긋나지 않는다 | `BalanceReconSchedulerTest`, `GET /api/account/control-recon` |
| AC-6 | 실지급이 SELLER_PAYABLE 을 음수로 몰지 않는다(분할 전기) | `RecordPayoutServiceTest` |
| AC-7 | 커버리지 LINE >= 90% | `./gradlew :account-service:jacocoTestCoverageVerification` |
| AC-8 | 소비 컨슈머가 DLT 배선에 닿는다(재시도 소진 메시지 유실 0) | `scripts/harness/guard.mjs` KAFKA-DLQ 규칙 |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** `settlement.confirmed` 무전표(Option A)는 "확정 시점에 GL 이 아무것도 모른다"는 뜻이다. 확정과 실지급 사이의
  기간에는 GL 상 미지급금이 `settlement.created` 시점 값 그대로 남아 있어, 그 구간의 시산표는 정산 상태와 시점이 다르다.
  → `disposition: by-design` (ADR 0026 Option A)
- **KI-2** 불변식 11의 클램프는 "SELLER_PAYABLE 을 음수로 만들지 않는다"를 보장할 뿐, **전역으로 음수가 아님을 보장하지 않는다**.
  잔액을 읽지 않는 다른 전기가 동시에 오면 통제계정이 음수가 될 수 있다(`RecordPayoutService.java:29-31` 주석이 명시).
  → `disposition: recorded-not-fixed` (동시성 경계)
- **KI-3** 수신 3종(정기예금·적금·퇴직연금)이 같은 서비스·같은 GL 에 얹혀 있어 계정과목이 15종으로 늘었다.
  집계 서비스와 상품 서비스가 한 배포 단위에 섞여 있다. → `disposition: recorded-not-fixed` (경계 후보)
- **KI-4** 이 서비스는 소비 전용이라 **재발행 경로가 없다**. DLT 로 격리된 분개 이벤트는 원천 서비스가 다시 쏘거나
  수기 보정해야 하며, 그 절차가 문서화돼 있지 않다. → `disposition: gap` (러너북 후보)
