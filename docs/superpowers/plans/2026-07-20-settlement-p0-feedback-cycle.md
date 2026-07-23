# Settlement P0 Feedback Cycle Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task after explicit user approval.

**Goal:** 감사에서 확인한 세 P0를 닫아 모든 정산 조정이 원장/회수 기록으로 추적되고, 정산 확정 뒤 즉시 지급과 홀드백 지급이 각각 정확히 한 번 생성되며, 계약 위반 Kafka 이벤트가 ACK 폐기되지 않고 격리·수정·재처리되게 한다.

**Architecture:** 현재 헥사고날 경계를 유지한다. 이벤트 계약 위반은 `shared-common` 소비 골격에서 예외로 표준화하고 settlement DLT가 격리한다. 모든 환불·차지백·PG 대사 조정은 `settlement_adjustments`를 단일 회계 원천으로 삼아 일반화된 ledger outbox를 거쳐 역분개한다. 확정 후 조정은 기존 정산/Payout을 수정하지 않고 판매자 회수채권과 배분 레코드를 추가한다. 지급은 `(settlement_id, payout_type)` 단위로 멱등 생성하고 `IMMEDIATE`와 `HOLDBACK_RELEASE`를 분리한다.

**Tech Stack:** Java 21, Spring Boot, Spring Kafka, Spring Data JPA/JDBC, PostgreSQL/Flyway, Gradle, Testcontainers, JUnit 5/Mockito, React/Vitest(회귀 확인만).

## 0. 승인 대상과 고정 결정

### 포함 범위

1. P0-EVENT: 누락·비 UUID `event_id` 및 JSON 계약 오류의 DLT 격리, 감사 가능한 수정 replay, 멱등 결과 관측.
2. P0-ACCOUNTING: 환불·차지백·PG 대사 조정의 출처/적용방식 명시, 조정별 원장 역분개, 확정 후 회수채권.
3. P0-PAYOUT: 정산 확정/대출 공제 결과에서 `IMMEDIATE`, 홀드백 해제에서 `HOLDBACK_RELEASE` 지급 생성.
4. 기존 행을 위한 report-first 백필, 이중 승인 apply, 무중단 호환/롤백, 고정 데이터 E2E.

### 제외 범위

- P1/P2 일반 성능 개선, UI 개편, 신규 지급사업자 연동, 새 정산 상품/통화.
- 과거 정산·원장·완료 Payout의 UPDATE/DELETE 정정.
- order DB 직접 조회 또는 settlement↔order 코드/DB FK 추가.
- 운영 DB 직접 접속 절차. 운영 검증은 애플리케이션 관리 API/MCP와 메트릭만 사용한다.

### 불변식

- 금액은 `BigDecimal`; 나눗셈은 `RoundingMode.HALF_UP`; JSON 금액은 십진 문자열이다.
- `settlement_adjustments.id` 하나마다 회계 효과가 정확히 하나다: 정산 잔액에 이미 반영됐거나, `ADJUSTMENT` 역분개와 회수채권으로 반영된다.
- POSTED 원장과 COMPLETED Payout은 변경하지 않는다. 정정은 역분개·회수·배분 레코드 추가만 허용한다.
- Payout은 `(settlement_id, payout_type)`당 최대 하나다.
- `IMMEDIATE = max(0, net_amount - 미해제 holdback_amount - loan_deduction - 이번 지급에 배분된 recovery)`.
- `HOLDBACK_RELEASE = max(0, 해제 시점의 미소진 holdback 잔액 - 이번 지급에 배분된 recovery)`.
- 계약 위반 이벤트는 ACK 성공 경로로 빠지지 않는다. DLT 격리 또는 정상 처리/중복 처리 중 하나의 관측 가능한 결과를 갖는다.
- DB 상태 변경과 outbox/회수 생성은 동일 트랜잭션이다. 크래시 후 재실행은 같은 결과에 수렴한다.

## 1. 현재 기준선과 감사 근거

| 발견 | 근거 파일 | 현재 결과 |
|---|---|---|
| `event_id` 누락 시 ACK 폐기 | `shared-common/src/main/java/github/lms/lemuel/common/outbox/adapter/in/kafka/IdempotentEventConsumer.java:74-75` | 경고 후 `ack.acknowledge()` |
| settlement DLT 기반은 존재 | `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/KafkaErrorHandlerConfig.java`, `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/DlqReplayService.java` | 예외는 DLT 가능, replay 최대 5회 |
| Payout 생성 production caller 단절 | `PayoutService.java:81`, `LoanRepaymentAppliedConsumer.java:64`, `ApplyLoanDeductionService.java:35` | 공제 기록만 하고 `requestForSettlement` 호출 없음 |
| 지급 멱등 키가 settlement 하나뿐 | `V1__settlement_baseline.sql:87`, `LoadPayoutPort.java`, `PayoutService.java:84` | 홀드백 별도 지급 불가 |
| 즉시지급 계산의 홀드백 함수는 존재 | `Settlement.java:463` | 호출 경로 없음 |
| 홀드백 해제 후 지급 없음 | `ReleaseHoldbackService.java`, ADR `0015-settlement-holdback-policy.md` | 해제 상태만 저장 |
| 환불만 전용 역분개 | `ReverseEntryService.java:52`, `LedgerOutboxService.java:75` | `refundId` 전용 |
| PG/차지백 회계 공백 | `ApplyReconciliationAdjustmentService.java`, `ChargebackService.java` | 조정 저장 후 일반 원장 경로 없음 |
| 무결성 SQL도 refund 전용 | `IntegrityQueryJdbcAdapter.java` | 조정 전체 완전성 판정 불가 |

현재 기준선 명령(2026-07-20):

- `./gradlew.bat -p shared-common test --tests github.lms.lemuel.common.outbox.OutboxAdaptersTest` → 성공.
- `./gradlew.bat :settlement-service:test --tests github.lms.lemuel.payout.application.service.PayoutServiceTest --tests github.lms.lemuel.settlement.application.service.ReleaseHoldbackServiceTest --tests github.lms.lemuel.settlement.application.service.ApplyReconciliationAdjustmentServiceTest --tests github.lms.lemuel.chargeback.application.service.ChargebackServiceTest` → 성공.
- `cd frontend && npm run test:run` → 성공, 17 files / 196 tests. P0는 백엔드 계약 변경이므로 프런트는 회귀 게이트만 둔다.

## 2. 구현 순서와 독립 커밋

의존성은 `P0-EVENT (독립) → P0-ACCOUNTING → P0-PAYOUT → 통합 E2E/재감사`다. 각 굵은 단계는 단독 배포 가능하고 테스트가 초록인 커밋으로 끝낸다.

## Task 1 — P0-EVENT-1: 계약 위반 이벤트를 ACK하지 않고 DLT로 보낸다

**변경 파일**

- Modify: `shared-common/src/main/java/github/lms/lemuel/common/outbox/adapter/in/kafka/IdempotentEventConsumer.java`
- Create: `shared-common/src/main/java/github/lms/lemuel/common/outbox/adapter/in/kafka/EventContractViolationException.java`
- Create: `shared-common/src/main/java/github/lms/lemuel/common/outbox/adapter/in/kafka/EventContractViolationReason.java`
- Modify: `shared-common/src/test/java/github/lms/lemuel/common/outbox/OutboxAdaptersTest.java`
- Modify: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/KafkaErrorHandlerConfig.java`
- Create: `settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/kafka/EventContractDlqIntegrationTest.java`

**계약**

- `MISSING_EVENT_ID`, `MALFORMED_EVENT_ID`, `INVALID_JSON`을 표준 이유 코드로 둔다.
- 위 오류는 `EventContractViolationException`을 던지고 consumer 본문/processed marker/ACK를 실행하지 않는다.
- error handler는 이 예외를 retry 없이 즉시 `<source>.DLT`로 보내며 원본 topic/partition/offset, reason, traceparent를 보존한다.
- 로그에는 payload, 계좌, 실명 등 원문을 넣지 않고 위치·eventId(있을 때)·reason만 남긴다.

**TDD 순서**

1. 기존 “누락 event_id도 ACK” 테스트를 `assertThrows` + `verifyNoInteractions(ack)`로 바꿔 RED를 확인한다.
2. malformed UUID와 invalid JSON 테스트를 추가해 RED를 확인한다.
3. 예외/이유 enum과 파싱 순서를 구현한다.
4. Testcontainers Kafka 통합 테스트로 DLT 헤더와 `processed_events` 미생성을 확인한다.

**검증**

```powershell
.\gradlew.bat -p shared-common test --tests github.lms.lemuel.common.outbox.OutboxAdaptersTest
.\gradlew.bat :settlement-service:test --tests github.lms.lemuel.settlement.adapter.in.kafka.EventContractDlqIntegrationTest
```

**완료 증거:** 누락/오류 세 경우 모두 원본 consumer ACK 0회, DLT 1건, processed marker 0건. 정상/중복 이벤트 기존 테스트는 그대로 통과.

**커밋:** `fix(events): quarantine invalid event contracts instead of acking`

## Task 2 — P0-EVENT-2: 격리 이벤트를 명시적으로 수정해 감사 가능하게 replay한다

**변경 파일**

- Modify: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/DlqReplayService.java`
- Modify: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/web/admin/DlqAdminController.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/web/admin/DlqRepairRequest.java`
- Modify: `settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/kafka/DlqReplayServiceValidationTest.java`
- Create: `settlement-service/src/test/java/github/lms/lemuel/settlement/adapter/in/kafka/DlqRepairReplayIntegrationTest.java`
- Modify: `docs/API.md`

**API/보안 계약**

- 기존 bulk replay는 정상 `event_id`가 있는 레코드에만 허용한다.
- 새 `POST /admin/dlq/repair-replay`는 DLT topic/partition/offset과 `eventIdOverride` 또는 `payloadOverride`를 받는다.
- `X-Idempotency-Key` 필수, ADMIN 권한, replay 한도 5회 유지.
- 감사로그에는 before/after SHA-256, reason, operator, 원본 위치만 기록하고 payload 원문은 기록하지 않는다.
- payload override는 JSON 파싱/필수 필드 검증 후 발행한다.

**TDD/검증**

```powershell
.\gradlew.bat :settlement-service:test --tests github.lms.lemuel.settlement.adapter.in.kafka.DlqReplayServiceValidationTest --tests github.lms.lemuel.settlement.adapter.in.kafka.DlqRepairReplayIntegrationTest
```

**완료 증거:** 같은 idempotency key 재호출은 1회만 발행; 수정된 이벤트는 1회 처리; 다시 replay하면 DUPLICATE로 수렴; 감사로그에 원문 PII 없음.

**커밋:** `feat(events): add audited repair replay for quarantined records`

## Task 3 — P0-ACCOUNTING-1: 조정 출처와 적용 방식을 스키마/도메인에 고정한다

**변경 파일**

- Create: `settlement-service/src/main/resources/db/migration/V20260720100000__classify_settlement_adjustments.sql`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/domain/AdjustmentSourceType.java`
- Create: `settlement-service/src/main/java/github/lms/lemuel/settlement/domain/AdjustmentApplicationMode.java`
- Modify: `settlement-service/src/main/java/github/lms/lemuel/settlement/domain/SettlementAdjustment.java`
- Modify: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentJpaEntity.java`
- Modify: `settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentPersistenceAdapter.java`
- Create: `settlement-service/src/test/java/github/lms/lemuel/settlement/domain/SettlementAdjustmentTest.java`
- Modify: `settlement-service/src/test/java/github/lms/lemuel/settlement/integration/SettlementPersistenceAdaptersIT.java`
- Modify: `settlement-service/src/test/java/github/lms/lemuel/settlement/integration/SettlementDbBootIT.java`

**DB 계약**

- Add `source_type varchar(32)`, `source_id bigint`, `application_mode varchar(32)`.
- 출처는 `REFUND`, `CHARGEBACK`, `PG_RECONCILIATION`; 신규 행은 source type/id 필수이며 `(source_type, source_id)` unique.
- 적용 방식은 `SETTLEMENT_BALANCE`(이미 net/holdback에 반영) 또는 `POST_CONFIRMATION_RECOVERY`.
- 1차 migration은 nullable 컬럼 추가 + 결정 가능한 기존 행 backfill + 검증 view/report를 만든다. 미분류 행이 0임을 확인한 후 별도 validation migration에서 NOT NULL/check를 적용한다.
- 기존 `refund_id`, `chargeback_id`, `reconciliation_discrepancy_id`는 혼합 버전 호환을 위해 이번 사이클에는 유지한다.

**백필 분류**

- chargeback: 항상 `POST_CONFIRMATION_RECOVERY`.
- refund/PG: 조정 생성 시점이 settlement `confirmed_at`보다 이전이면 `SETTLEMENT_BALANCE`, 이후면 `POST_CONFIRMATION_RECOVERY`.
- 판정 불가 행은 자동 추정하지 않고 report의 `UNCLASSIFIED`로 남겨 apply를 차단한다.

**검증**

```powershell
.\gradlew.bat :settlement-service:test --tests github.lms.lemuel.settlement.domain.SettlementAdjustmentTest --tests github.lms.lemuel.settlement.integration.SettlementPersistenceAdaptersIT --tests github.lms.lemuel.settlement.integration.SettlementDbBootIT
```

**완료 증거:** 신규 조정의 출처 XOR/unique/적용방식 제약 통과, 기존 fixture migration 성공, 미분류 수가 report에 명시됨.

**커밋:** `feat(accounting): classify adjustment sources and effects`

## Task 4 — P0-ACCOUNTING-2: 모든 조정을 일반 ledger outbox로 역분개한다

**변경 파일**

- Create: `settlement-service/src/main/resources/db/migration/V20260720110000__generalize_adjustment_ledger_outbox.sql`
- Modify: `ledger/domain/LedgerOutboxTask.java`, `LedgerEntryType.java`, `ReferenceType.java`
- Modify: `ledger/application/port/in/ReverseEntryUseCase.java`
- Modify: `ledger/application/port/out/SaveLedgerOutboxPort.java`
- Modify: `ledger/application/service/ReverseEntryService.java`, `LedgerOutboxService.java`
- Modify persistence: `LedgerOutboxJpaEntity.java`, `LedgerOutboxPersistenceAdapter.java`
- Modify producers: `AdjustSettlementForRefundService.java`, `ApplyReconciliationAdjustmentService.java`, `ChargebackService.java`
- Modify tests: `LedgerEndToEndIntegrationTest.java`, 각 세 application service test

**DB/도메인 계약**

- ledger outbox에 `adjustment_id`, `adjustment_source_type`, `adjustment_source_id`, `adjustment_amount` 추가.
- 신규 `REVERSE_ADJUSTMENT` task와 `ReferenceType.ADJUSTMENT`, `LedgerEntryType.ADJUSTMENT_REVERSED` 추가.
- 역분개 idempotency key는 adjustment ID다. 기존 ledger unique key와 명시적 `existsByReference`를 함께 사용한다.
- legacy `REVERSE_ENTRY/refund_id` poller 분기는 기존 pending 행 소진을 위해 유지한다.
- 조정 저장과 outbox insert를 같은 `@Transactional` 경계에 둔다.

**TDD 순서**

1. refund/chargeback/PG 각각 “조정 1건 → outbox 1건 → 균형 원장 2건” 테스트를 먼저 RED로 만든다.
2. 같은 source event 재처리와 poller crash-after-post 재시도 테스트를 RED로 만든다.
3. 일반화 포트/어댑터를 구현하고 legacy 분기를 유지한다.
4. 차변합=대변합, amount scale/rounding, POSTED 불변을 검증한다.

**검증**

```powershell
.\gradlew.bat :settlement-service:test --tests github.lms.lemuel.ledger.integration.LedgerEndToEndIntegrationTest --tests github.lms.lemuel.settlement.application.service.AdjustSettlementForRefundServiceTest --tests github.lms.lemuel.settlement.application.service.ApplyReconciliationAdjustmentServiceTest --tests github.lms.lemuel.chargeback.application.service.ChargebackServiceTest
```

**완료 증거:** 세 출처 모두 adjustment:outbox:ledger reference가 1:1, 중복/크래시 재실행 후 추가 원장 없음, 각 분개의 차대 균형 0.

**커밋:** `feat(ledger): reverse every settlement adjustment through outbox`

## Task 5 — P0-ACCOUNTING-3: 확정 후 조정을 회수채권으로 기록한다

**변경 파일**

- Create migration: `V20260720120000__seller_recoveries.sql`
- Create domain: `recovery/domain/SellerRecovery.java`, `RecoveryStatus.java`, `RecoveryAllocation.java`
- Create ports/services/persistence under `recovery/application` and `recovery/adapter/out/persistence`
- Modify: `AdjustSettlementForRefundService.java`, `ApplyReconciliationAdjustmentService.java`, `ChargebackService.java`
- Create tests: `SellerRecoveryServiceTest.java`, `SellerRecoveryPersistenceIT.java`, `PostConfirmationAdjustmentRecoveryIT.java`

**DB 계약**

- `seller_recoveries`: source adjustment ID unique, seller ID, original amount, status, created/closed timestamps.
- `recovery_allocations`: recovery ID, payout ID, amount, created_at; unique `(recovery_id,payout_id)`.
- 회수채권 원금/배분은 append-only. 잔액은 `original_amount - sum(allocations)`로 계산한다.
- 지급이 이미 REQUESTED/SENT/COMPLETED면 해당 Payout 금액을 바꾸지 않는다. 다음 Payout에서 상계하고, 다음 지급이 없으면 `OPEN/MANUAL_REQUIRED`로 운영 리포트에 남긴다.

**검증**

```powershell
.\gradlew.bat :settlement-service:test --tests github.lms.lemuel.recovery.application.service.SellerRecoveryServiceTest --tests github.lms.lemuel.recovery.integration.SellerRecoveryPersistenceIT --tests github.lms.lemuel.recovery.integration.PostConfirmationAdjustmentRecoveryIT
```

**완료 증거:** DONE/지급 후 조정이 원 정산/Payout을 수정하지 않고 원장 역분개+회수채권을 생성; 중복 이벤트는 회수 1건; 잔액은 음수가 되지 않음.

**커밋:** `feat(recovery): record append-only post-confirmation recoveries`

## Task 6 — P0-PAYOUT-1: 지급 유형과 암호화된 판매자 지급계좌를 도입한다

**변경 파일**

- Create migration: `V20260720130000__payout_types_and_seller_accounts.sql`
- Create: `payout/domain/PayoutType.java`, `SellerPayoutAccount.java`
- Modify: `payout/domain/Payout.java`, `PayoutJpaEntity.java`, `SpringDataPayoutRepository.java`, `PayoutPersistenceAdapter.java`, `LoadPayoutPort.java`, `RequestPayoutUseCase.java`, `PayoutService.java`
- Create account ports/service/JPA repository and `payout/adapter/in/web/SellerPayoutAccountAdminController.java`
- Reuse: `PayoutFieldEncryptionConverter.java`
- Modify: `settlement-service/src/test/java/github/lms/lemuel/payout/application/service/PayoutServiceTest.java`
- Create: `settlement-service/src/test/java/github/lms/lemuel/payout/integration/PayoutPersistenceAdaptersIT.java`
- Create: `settlement-service/src/test/java/github/lms/lemuel/payout/adapter/in/web/SellerPayoutAccountAdminControllerTest.java`

**DB/API 계약**

- `payouts.payout_type NOT NULL DEFAULT 'IMMEDIATE'`.
- 기존 `uq_payouts_settlement`을 `UNIQUE(settlement_id,payout_type) WHERE settlement_id IS NOT NULL`로 교체.
- 조회 포트를 `findBySettlementIdAndType`으로 전환하고 기존 method는 migration 기간 read-only 호환용으로만 둔다.
- `seller_payout_accounts`는 seller ID unique, bank/account/holder 암호문, key version, 활성 상태, timestamps를 갖는다. 평문 로그/응답 금지.
- ADMIN 계좌 등록/교체는 `X-Idempotency-Key`와 감사로그를 요구한다. Payout에는 요청 시점 계좌 스냅샷을 복사한다.

**롤아웃 안전성**

1. 확장 migration 배포(기존 행은 IMMEDIATE), 새 unique index 생성.
2. 타입 인식 reader/writer 배포, 자동 생성 feature flag는 OFF.
3. 계좌 준비율/중복 0 확인 후 자동 생성 ON.
4. DB 컬럼은 롤백 때 제거하지 않고 flag OFF + 이전 바이너리 호환을 유지한다.

**검증**

```powershell
.\gradlew.bat :settlement-service:test --tests github.lms.lemuel.payout.application.service.PayoutServiceTest --tests github.lms.lemuel.payout.integration.PayoutPersistenceAdaptersIT --tests github.lms.lemuel.payout.adapter.in.web.SellerPayoutAccountAdminControllerTest
```

**완료 증거:** 같은 settlement/type 동시 요청은 1건, 서로 다른 두 type은 2건, DB/로그/API에 계좌 평문 없음.

**커밋:** `feat(payout): support typed payouts and encrypted seller accounts`

## Task 7 — P0-PAYOUT-2: 정산 확정에서 IMMEDIATE Payout을 생성한다

**변경 파일**

- Create: `payout/application/service/SettlementPayoutCoordinator.java`, `PayoutAmountCalculator.java`
- Create corresponding input/output ports for settlement, deduction, account, open recovery loading/allocation
- Modify: `ApplyLoanDeductionService.java` and/or `LoanRepaymentAppliedConsumer.java`
- Modify: `SettlementLoanDeductionPersistenceAdapter.java`
- Create tests: `PayoutAmountCalculatorTest.java`, `SettlementPayoutCoordinatorTest.java`, `ImmediatePayoutFlowIT.java`

**트랜잭션/금액 계약**

- trigger는 `LoanRepaymentApplied` 처리다. loan-service는 deduction 0도 결과를 발행하므로 확정 후 단일 합류점이 된다.
- consumer transaction 안에서 deduction 멱등 기록 → settlement/account/recovery 조회 → Payout 요청 → recovery allocation을 수행한다.
- amount는 `Settlement.getImmediatePayoutAmount()`에서 deduction과 실제 배분 recovery를 뺀 값이다.
- 0 이하는 Payout을 만들지 않고 `ZERO_NET` 결과 레코드/메트릭을 남긴다.
- 계좌 미등록은 이벤트를 DLT 폐기하지 않고 `PAYOUT_BLOCKED_ACCOUNT_MISSING` 운영 상태로 남겨 계좌 등록 후 멱등 재시도한다.
- 기능 flag: `settlement.payout.auto-request.enabled=false`가 초기값.

**TDD/검증**

```powershell
.\gradlew.bat :settlement-service:test --tests github.lms.lemuel.payout.application.service.PayoutAmountCalculatorTest --tests github.lms.lemuel.payout.application.service.SettlementPayoutCoordinatorTest --tests github.lms.lemuel.payout.integration.ImmediatePayoutFlowIT
```

**완료 증거:** holdback 미해제/해제, deduction 0/양수, recovery 0/양수, 중복 이벤트, DB unique race, 각 crash point가 표 기반 기대값과 일치.

**커밋:** `feat(payout): create immediate payout after loan deduction`

## Task 8 — P0-PAYOUT-3: 홀드백 해제에서 별도 Payout을 생성한다

**변경 파일**

- Modify: `ReleaseHoldbackService.java`, `ReleaseHoldbackUseCase.java`, `HoldbackReleaseScheduler.java`
- Modify seller lookup through settlement-owned `settlement_payment_view` port; order DB 조회 금지
- Reuse: `SettlementPayoutCoordinator` with `PayoutType.HOLDBACK_RELEASE`
- Modify/create tests: `ReleaseHoldbackServiceTest.java`, `HoldbackReleasePayoutIT.java`, `HoldbackReleaseSchedulerTest.java`

**계약**

- settlement row lock 후 해제 가능한 holdback 잔액을 스냅샷으로 잡고 release 저장과 payout request를 같은 transaction에서 수행한다.
- 이미 조정으로 소진된 holdback은 지급하지 않는다.
- seller projection/account가 없으면 해제 사실은 보존하고 blocked request를 남겨 복구한다.
- 중복 스케줄/노드 경쟁은 `(settlement_id,HOLDBACK_RELEASE)` unique로 수렴한다.
- 기능 flag: `settlement.payout.holdback-release.enabled=false`가 초기값.

**검증**

```powershell
.\gradlew.bat :settlement-service:test --tests github.lms.lemuel.settlement.application.service.ReleaseHoldbackServiceTest --tests github.lms.lemuel.payout.integration.HoldbackReleasePayoutIT --tests github.lms.lemuel.settlement.adapter.in.batch.HoldbackReleaseSchedulerTest
```

**완료 증거:** 즉시지급과 홀드백 지급은 동일 settlement에 각각 1건, 두 금액 합이 recovery/deduction 반영 후 지급가능액을 초과하지 않음.

**커밋:** `feat(payout): create idempotent holdback release payout`

## Task 9 — P0-BACKFILL/INTEGRITY: report-first 백필과 이중 승인을 제공한다

**변경 파일**

- Create migration: `V20260720140000__accounting_backfill_runs.sql`
- Create package: `backfill/domain`, `backfill/application`, `backfill/adapter/in/web`, `backfill/adapter/out/persistence`
- Modify: `IntegrityQueryJdbcAdapter.java`, `IntegrityQueryPort.java`, `IntegrityQueryService.java`, `IntegrityAdminController.java`
- Modify: `PayoutReconReport.java`, `RefundAdjustmentReport.java` 또는 일반 `AdjustmentAccountingReport.java`로 교체
- Create tests: `AccountingBackfillServiceTest.java`, `AccountingBackfillIntegrationTest.java`; modify `IntegrityPhaseAIntegrationTest.java`

**절차/API**

1. `POST /admin/backfills/accounting/report`가 후보와 checksum을 생성한다(쓰기 없음).
2. 운영자 A가 immutable run을 요청한다.
3. 다른 운영자 B만 approve 가능하다; 같은 사용자 승인은 거부한다.
4. apply는 adjustment/outbox/recovery/payout을 append-only로 보충하고 row별 result를 기록한다.
5. 같은 run/idempotency key 재실행은 0건 추가된다.

**무결성 쿼리**

- 모든 adjustment의 source 분류, ledger reference, recovery 필요/존재.
- Payout `(settlement,type)` 중복, IMMEDIATE/HOLDBACK_RELEASE 기대액, 완료 후 회수 미처리.
- legacy refund ledger는 호환 판정하되 신규 report에서 별도 legacy count로 노출한다.

**검증**

```powershell
.\gradlew.bat :settlement-service:test --tests github.lms.lemuel.backfill.integration.AccountingBackfillIntegrationTest --tests github.lms.lemuel.integrity.integration.IntegrityPhaseAIntegrationTest
```

**완료 증거:** dry-run checksum 고정, same-actor 승인 거부, 1차 apply 후 gap 0, 2차 apply inserted 0, 기존 재무행 UPDATE/DELETE 0.

**커밋:** `feat(integrity): add dual-control accounting backfill and typed payout recon`

## Task 10 — 고정 데이터 종단 간 검증과 문서 정합화

**변경 파일**

- Create: `settlement-service/src/test/java/github/lms/lemuel/e2e/SettlementAccountingP0E2ETest.java`
- Create/update: `scripts/test-settlement-accounting-p0.ps1`
- Modify: `docs/API.md`, `docs/DB.md`, `docs/etc/ARCHITECTURE.md`
- Modify ADRs: `0003`, `0004`, `0007`, `0015`, `0016`
- Modify: `README.md`, `.env.example`, CI workflow relevant to settlement tests

**고정 시나리오**

1. PaymentCaptured → settlement 생성/확정 → loan deduction 0 → IMMEDIATE 지급.
2. 미해제 holdback 제외 확인 → holdback 해제 → HOLDBACK_RELEASE 지급.
3. 지급 전 refund/PG 조정 → holdback/net 반영 + adjustment ledger.
4. 지급 후 chargeback/PG 조정 → adjustment ledger + recovery → 다음 payout 상계.
5. missing/malformed event_id → DLT → 명시적 repair → 1회 처리.
6. 각 단계 중복 전달과 강제 crash 후 재실행 → 같은 최종 상태.

**단일 명령**

```powershell
.\scripts\test-settlement-accounting-p0.ps1
```

스크립트는 다음을 순서대로 실행하고 종료코드 하나로 결과를 반환한다.

```powershell
.\gradlew.bat -p shared-common test
.\gradlew.bat :settlement-service:test
Push-Location frontend; npm run test:run; Pop-Location
```

선택적 Docker/Testcontainers E2E는 Docker 미가동 시 성공으로 건너뛰지 않고 `BLOCKED_DOCKER`로 실패 원인을 명시한다.

**완료 증거 묶음**

- JUnit XML/HTML 및 Vitest 결과.
- Flyway migration/validation 성공 로그.
- E2E fixture별 adjustment↔ledger↔recovery↔payout ID 매핑 JSON(PII 없음).
- 무결성 report: unclassified=0, missing ledger=0, duplicate payout=0, payout mismatch=0, unrecoverable event=0.
- README/API/DB/ADR의 상태 의미: `Settlement.DONE`은 정산 확정, 실제 송금 완료는 `Payout.COMPLETED`로 일치.

**커밋:** `test(docs): prove settlement accounting p0 end to end`

## 3. 호환성·롤백·백필 운영 순서

1. **사전 report:** unclassified adjustment, legacy ledger outbox, seller projection/account 준비율, payout 중복을 산출한다.
2. **expand DB:** nullable/new default 컬럼과 신규 테이블/index만 먼저 배포한다. 기존 컬럼은 삭제하지 않는다.
3. **호환 코드:** old/new row를 모두 읽고 신규 쓰기만 v2 형태로 한다. 자동 Payout과 adjustment-v2 flags는 OFF다.
4. **백필:** report → A 요청 → B 승인 → 소규모 apply → 무결성 재검사 → 전체 apply.
5. **기능 활성화:** event quarantine → adjustment ledger v2 → immediate payout → holdback payout 순으로 켠다.
6. **롤백:** 해당 flag를 OFF하고 consumer/scheduler를 정지한다. 이미 추가된 adjustment/ledger/recovery/Payout은 삭제하지 않는다. 실패 outbox/blocked request는 재개 시 처리한다.
7. **contract:** legacy pending row 0, 구버전 인스턴스 0, 재감사 통과 후에만 legacy refund outbox 필드/reader 제거를 다음 P1 Seed로 넘긴다.

## 4. 승인 게이트

이 문서는 구현 전 초안이다. 승인 전에는 Task 1의 실패 테스트도 작성하지 않는다. 승인 시 첫 구현은 의존성이 없고 데이터 손실을 막는 **Task 1 / P0-EVENT-1**이다. 각 커밋 뒤 해당 검증 명령과 증거를 보고하고 다음 커밋으로 진행한다. 마지막 재감사 결과는 다음 Ouroboros Seed의 입력으로 환류한다.
