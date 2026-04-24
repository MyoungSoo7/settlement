# Ledger-First Settlement & Withdrawal System Design

> **Date**: 2026-04-24
> **Status**: Approved
> **Purpose**: Portfolio project demonstrating senior-level fintech domain design

## 1. Overview

### Goal
현재 헥사고날 아키텍처 기반 Settlement 모놀리스에 복식부기 Ledger, 출금, Event Sourcing/CQRS, Outbox+Kafka, 대량 트랜잭션 최적화, 무중단 마이그레이션, 판매자별 수수료/정산 주기를 추가하여 시니어급 금융 도메인 역량을 증명한다.

### Architecture Decision
**Option A: Ledger-First 단일 서비스** 선택. 도메인 로직 깊이에 집중하면서 Kafka/Outbox/CQRS를 모두 포함. MSA로 전환 가능한 구조를 의도적으로 설계.

### Tech Stack
- Spring Boot + PostgreSQL + Elasticsearch + Spring Batch (기존)
- Kafka + Debezium CDC (신규)

---

## 2. Implementation Phases

```
Phase 1: 복식부기 Ledger 코어
Phase 2: 판매자별 수수료 & 정산 주기
Phase 3: Outbox 패턴 + Kafka (Debezium CDC)
Phase 4: Event Sourcing + CQRS (Settlement Aggregate)
Phase 5: 출금(Withdrawal) 모델
Phase 6: 대량 트랜잭션 최적화 & 무중단 마이그레이션
```

---

## 3. Package Structure

```
github.lms.lemuel
├── ledger/                          ← NEW: 복식부기 핵심
│   ├── domain/
│   │   ├── Account.java             (계정: SELLER_BALANCE, PLATFORM_FEE, BANK 등)
│   │   ├── AccountType.java         (ASSET, LIABILITY, REVENUE, EXPENSE)
│   │   ├── JournalEntry.java        (분개 전표 - Aggregate Root)
│   │   ├── LedgerLine.java          (차변/대변 개별 라인)
│   │   ├── Money.java               (Value Object: 금액+통화)
│   │   └── DebitCredit.java         (DEBIT/CREDIT enum)
│   ├── application/
│   │   ├── port/in/
│   │   │   ├── RecordJournalEntryUseCase.java
│   │   │   └── GetAccountBalanceUseCase.java
│   │   ├── port/out/
│   │   │   ├── SaveJournalEntryPort.java
│   │   │   └── LoadAccountPort.java
│   │   └── service/
│   │       └── LedgerService.java
│   └── adapter/
│       └── out/persistence/
│
├── withdrawal/                      ← NEW: 출금
│   ├── domain/
│   │   ├── Withdrawal.java
│   │   └── WithdrawalStatus.java
│   ├── application/
│   └── adapter/
│
├── settlement/                      ← 기존 확장
│   ├── domain/
│   │   ├── Settlement.java          (Event Sourcing 적용)
│   │   ├── SettlementEvent.java     ← NEW
│   │   └── SettlementCycle.java     ← NEW
│   └── ...
│
├── common/
│   ├── outbox/                      ← NEW: Outbox 패턴
│   │   ├── OutboxEvent.java
│   │   └── OutboxRepository.java
│   └── eventsourcing/               ← NEW: ES 공통
│       ├── DomainEvent.java
│       └── EventStore.java
│
└── payment/                         (기존 유지)
```

---

## 4. Phase 1: Double-Entry Ledger Core

### 4.1 Chart of Accounts

> **관점**: 모든 분개는 **플랫폼(회사) 장부** 관점에서 기록한다.

| Type      | Code                         | Purpose                              |
|-----------|------------------------------|--------------------------------------|
| ASSET     | PLATFORM_CASH                | 플랫폼 보유 현금 (PG로부터 수신)        |
| ASSET     | BANK_TRANSFER_PENDING        | 은행 이체 진행중                       |
| LIABILITY | SELLER_PAYABLE:{sellerId}    | 판매자에게 지급해야 할 금액 (정산 의무)  |
| REVENUE   | PLATFORM_COMMISSION          | 플랫폼 수수료 수익                     |
| EXPENSE   | REFUND_EXPENSE               | 환불 비용                             |

### 4.2 Journal Entry Scenarios

> 플랫폼 장부 관점: 결제 캡처 = 현금 유입(자산 증가) + 판매자 지급 의무 발생(부채 증가)

**1. 결제 캡처 → 정산 생성 (10,000원, 수수료 3%)**
```
JournalEntry: "SETTLEMENT_CREATED"
  DEBIT   PLATFORM_CASH            10,000원   (자산 증가: 현금 유입)
  CREDIT  SELLER_PAYABLE:42        10,000원   (부채 증가: 판매자 지급 의무)

JournalEntry: "COMMISSION_DEDUCTED"
  DEBIT   SELLER_PAYABLE:42           300원   (부채 감소: 수수료만큼 지급 의무 차감)
  CREDIT  PLATFORM_COMMISSION         300원   (수익 증가: 수수료 수익 인식)
```
→ 정산 후 SELLER_PAYABLE:42 잔액 = 9,700원 (판매자 실수령 예정액)

**2. 부분환불 (3,000원, 수수료 3% 비례 역산 90원)**
```
JournalEntry: "REFUND_PROCESSED"
  DEBIT   SELLER_PAYABLE:42         2,910원   (부채 감소: 판매자 지급액에서 차감)
  CREDIT  PLATFORM_CASH             3,000원   (자산 감소: 구매자에게 환불)
  DEBIT   PLATFORM_COMMISSION          90원   (수익 감소: 비례 수수료 되돌림)
```
→ 환불 후 SELLER_PAYABLE:42 잔액 = 9,700 - 2,910 = 6,790원
→ 수수료: 300 - 90 = 210원 (환불 비례 차감)

**3. 출금 실행 (6,790원 판매자 출금)**
```
JournalEntry: "WITHDRAWAL_INITIATED"
  DEBIT   SELLER_PAYABLE:42         6,790원   (부채 감소: 지급 의무 이행)
  CREDIT  BANK_TRANSFER_PENDING     6,790원   (자산 증가: 이체 진행중)

JournalEntry: "WITHDRAWAL_COMPLETED"
  DEBIT   BANK_TRANSFER_PENDING     6,790원   (자산 감소: 이체 완료)
  CREDIT  PLATFORM_CASH             6,790원   (자산 감소: 현금 유출)
```

**4. 출금 실패 시 보상 분개**
```
JournalEntry: "WITHDRAWAL_FAILED"
  DEBIT   BANK_TRANSFER_PENDING     6,790원   (이체 진행중 해소 - 역분개)
  CREDIT  SELLER_PAYABLE:42         6,790원   (지급 의무 복원)
```

**검증: Trial Balance**
모든 시점에서 `SUM(DEBIT) == SUM(CREDIT)` 성립. 계정별 잔액:
- PLATFORM_CASH: +10,000 - 3,000 - 6,790 = +210 (수수료 수익분 보유)
- SELLER_PAYABLE:42: +10,000 - 300 - 2,910 - 6,790 = 0 (전액 정산 완료)
- PLATFORM_COMMISSION: +300 - 90 = +210 (순 수수료 수익)

### 4.3 Domain Model

```java
public class JournalEntry {
    private Long id;
    private String entryType;
    private String referenceType;
    private Long referenceId;
    private List<LedgerLine> lines;
    private String description;
    private String idempotencyKey;
    private LocalDateTime createdAt;

    public static JournalEntry create(String entryType, String referenceType,
                                       Long referenceId, List<LedgerLine> lines,
                                       String idempotencyKey) {
        Money totalDebit = lines.stream()
            .filter(l -> l.getSide() == DebitCredit.DEBIT)
            .map(LedgerLine::getAmount)
            .reduce(Money.ZERO, Money::add);
        Money totalCredit = lines.stream()
            .filter(l -> l.getSide() == DebitCredit.CREDIT)
            .map(LedgerLine::getAmount)
            .reduce(Money.ZERO, Money::add);

        if (!totalDebit.equals(totalCredit)) {
            throw new UnbalancedJournalEntryException(totalDebit, totalCredit);
        }
        // ... build and return
    }
}
```

**Invariant**: 모든 JournalEntry에서 `SUM(DEBIT) == SUM(CREDIT)`. 위반 시 생성 자체가 불가.

### 4.4 DB Schema

```sql
CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    type        VARCHAR(20) NOT NULL,  -- ASSET, LIABILITY, REVENUE, EXPENSE
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE journal_entries (
    id              BIGSERIAL PRIMARY KEY,
    entry_type      VARCHAR(50) NOT NULL,
    reference_type  VARCHAR(30) NOT NULL,
    reference_id    BIGINT NOT NULL,
    description     TEXT,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE ledger_lines (
    id               BIGSERIAL PRIMARY KEY,
    journal_entry_id BIGINT NOT NULL REFERENCES journal_entries(id),
    account_id       BIGINT NOT NULL REFERENCES accounts(id),
    side             VARCHAR(6) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount           NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 실시간 잔액 조회: snapshot + delta 방식 (Phase 6의 9.4 참조)
-- Materialized View는 사용하지 않음 (refresh 타이밍 문제로 실시간 잔액에 부적합)
-- 대신 account_balance_snapshots + 이후 ledger_lines delta로 현재 잔액 계산

-- 실시간 잔액 조회 쿼리:
-- SELECT s.balance + COALESCE(SUM(
--     CASE WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'DEBIT' THEN ll.amount
--          WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'CREDIT' THEN -ll.amount
--          WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'CREDIT' THEN ll.amount
--          WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'DEBIT' THEN -ll.amount
--     END), 0) AS current_balance
-- FROM account_balance_snapshots s
-- JOIN accounts a ON s.account_id = a.id
-- LEFT JOIN ledger_lines ll ON ll.account_id = a.id AND ll.created_at > s.snapshot_at
-- WHERE s.account_id = ? AND s.snapshot_at = (SELECT MAX(snapshot_at) FROM account_balance_snapshots WHERE account_id = ?)
-- GROUP BY s.balance;

-- 배치 리포팅 용도의 Materialized View (참고용, 실시간 잔액에는 사용하지 않음)
CREATE MATERIALIZED VIEW account_balances_report AS
SELECT
    a.id AS account_id, a.code, a.type,
    COALESCE(SUM(CASE WHEN ll.side = 'DEBIT' THEN ll.amount ELSE 0 END), 0) AS total_debit,
    COALESCE(SUM(CASE WHEN ll.side = 'CREDIT' THEN ll.amount ELSE 0 END), 0) AS total_credit,
    COALESCE(SUM(CASE
        WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'DEBIT' THEN ll.amount
        WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'CREDIT' THEN -ll.amount
        WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'CREDIT' THEN ll.amount
        WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'DEBIT' THEN -ll.amount
    END), 0) AS balance
FROM accounts a
LEFT JOIN ledger_lines ll ON a.id = ll.account_id
GROUP BY a.id, a.code, a.type;
-- REFRESH: 매일 새벽 배치 완료 후 REFRESH MATERIALIZED VIEW CONCURRENTLY account_balances_report;
```

---

## 5. Phase 2: Seller Commission & Settlement Cycle

### 5.1 Seller Aggregate Extension

```java
public class Seller {
    private Long id;
    private Long userId;
    private String businessName;
    private BigDecimal commissionRate;
    private SettlementCycle settlementCycle;   // DAILY, WEEKLY, MONTHLY
    private DayOfWeek weeklySettlementDay;
    private int monthlySettlementDay;          // 1~28
    private BigDecimal minimumWithdrawalAmount;

    public boolean isSettlementDueOn(LocalDate date) {
        return switch (settlementCycle) {
            case DAILY -> true;
            case WEEKLY -> date.getDayOfWeek() == weeklySettlementDay;
            case MONTHLY -> date.getDayOfMonth() == monthlySettlementDay;
        };
    }
}
```

### 5.2 CommissionCalculation Value Object

```java
public record CommissionCalculation(
    BigDecimal paymentAmount,
    BigDecimal commissionRate,
    BigDecimal commissionAmount,
    BigDecimal netAmount
) {
    public static CommissionCalculation calculate(BigDecimal paymentAmount,
                                                   BigDecimal commissionRate) {
        BigDecimal commission = paymentAmount.multiply(commissionRate)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = paymentAmount.subtract(commission);
        return new CommissionCalculation(paymentAmount, commissionRate, commission, net);
    }
}
```

### 5.3 Settlement Scheduler Change

매일 새벽 2시 실행. 오늘 정산 대상인 판매자만 필터링하고, 판매자별 수수료율을 적용하여 정산 생성.

### 5.4 DB Changes

```sql
ALTER TABLE sellers
    ADD COLUMN settlement_cycle VARCHAR(10) NOT NULL DEFAULT 'DAILY',
    ADD COLUMN weekly_settlement_day VARCHAR(10),
    ADD COLUMN monthly_settlement_day INT,
    ADD COLUMN minimum_withdrawal_amount NUMERIC(12,2) NOT NULL DEFAULT 1000;

ALTER TABLE sellers ADD CONSTRAINT chk_monthly_day
    CHECK (monthly_settlement_day IS NULL OR (monthly_settlement_day >= 1 AND monthly_settlement_day <= 28));

-- seller_id는 nullable로 시작 (기존 데이터 호환)
ALTER TABLE settlements ADD COLUMN seller_id BIGINT REFERENCES sellers(id);
CREATE INDEX idx_settlements_seller_id ON settlements(seller_id);

-- Backfill: 기존 정산의 seller_id를 orders → products → sellers 체인으로 유추
-- UPDATE settlements s
-- SET seller_id = (
--     SELECT p.seller_id FROM orders o
--     JOIN products p ON o.product_id = p.id
--     WHERE o.id = s.order_id
-- )
-- WHERE s.seller_id IS NULL;

-- Backfill 완료 후 NOT NULL 전환:
-- ALTER TABLE settlements ALTER COLUMN seller_id SET NOT NULL;
```

---

## 6. Phase 3: Outbox Pattern + Kafka (Debezium CDC)

### 6.1 Problem

비즈니스 트랜잭션과 이벤트 발행의 원자성 보장 불가 → Outbox 패턴으로 해결.

### 6.2 Outbox Table

```sql
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    BIGINT NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_created_at ON outbox_events(created_at);
```

> **Cleanup 전략**: Debezium CDC는 PostgreSQL WAL에서 INSERT를 캡처하므로 `published` 플래그 불필요. 대신 스케줄러가 1시간 이상 된 행을 주기적으로 삭제 (`DELETE FROM outbox_events WHERE created_at < NOW() - INTERVAL '1 hour'`). Debezium이 WAL에서 이미 캡처한 후이므로 데이터 유실 없음.

### 6.3 Data Flow

```
Application (single TX)
  1. payment.capture()       → payments UPDATE
  2. outboxRepository.save() → outbox INSERT
  3. COMMIT
       │
       ▼ (Debezium reads PostgreSQL WAL)
Debezium CDC Connector
  - PostgreSQL logical replication slot
  - Captures INSERT on outbox_events
  - SMT EventRouter transforms envelope
       │
       ▼
Kafka Topics
  - payment.events
  - settlement.events
       │
       ├──→ SettlementConsumer  → Event Store
       ├──→ LedgerConsumer      → JournalEntry
       └──→ ProjectionConsumer  → Read model + ES
```

### 6.4 Consumer Idempotency

모든 Consumer는 `idempotencyKey` 기반 중복 처리. Event Store에 이미 존재하면 스킵.

### 6.5 Debezium Config

```yaml
connector.class: io.debezium.connector.postgresql.PostgresConnector
table.include.list: public.outbox_events
transforms: outbox
transforms.outbox.type: io.debezium.transforms.outbox.EventRouter
transforms.outbox.route.by.field: aggregate_type
transforms.outbox.route.topic.replacement: ${routedByValue}.events
```

---

## 7. Phase 4: Event Sourcing + CQRS

### 7.1 Settlement Events

```
SettlementCreatedEvent
CommissionCalculatedEvent
SettlementApprovedEvent
SettlementRejectedEvent
RefundAdjustedEvent
SettlementConfirmedEvent
WithdrawalLinkedEvent
SettlementCanceledEvent
```

> `LedgerEntryRecordedEvent`는 Settlement Aggregate에서 제거. Ledger는 별도 Bounded Context이므로, Ledger Consumer가 Settlement 이벤트(SettlementCreatedEvent 등)를 Kafka에서 구독하여 독립적으로 분개를 생성한다. 상관관계 추적은 `referenceType + referenceId`로 수행.

### 7.2 Aggregate Reconstitution

Settlement의 현재 상태를 직접 저장하지 않음. 이벤트 스트림을 재생하여 상태 복원.

```java
public class Settlement {
    public static Settlement reconstitute(List<SettlementEvent> history) {
        Settlement settlement = new Settlement();
        history.forEach(settlement::apply);
        settlement.uncommittedEvents.clear();
        return settlement;
    }

    // Command → validate → raise event
    public void create(...) { ... raise(new SettlementCreatedEvent(...)); }

    // Event → state change (no side effects)
    private void apply(SettlementEvent event) { ... this.version++; }
}
```

### 7.3 Aggregate Snapshot Strategy

이벤트가 무한히 쌓이면 복원 성능이 저하되므로, **매 50 이벤트마다 스냅샷**을 저장한다.

```sql
CREATE TABLE settlement_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_id  BIGINT NOT NULL,
    version       INT NOT NULL,              -- 스냅샷 시점의 버전
    state         JSONB NOT NULL,            -- Aggregate 직렬화 상태
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_snapshot_aggregate_version UNIQUE (aggregate_id, version)
);

CREATE INDEX idx_settlement_snapshots_aggregate
    ON settlement_snapshots(aggregate_id, version DESC);
```

**복원 로직**:
```java
public Settlement load(Long aggregateId) {
    // 1. 최신 스냅샷 조회
    Optional<SettlementSnapshot> snapshot = snapshotStore.findLatest(aggregateId);

    // 2. 스냅샷 이후 이벤트만 조회
    int fromVersion = snapshot.map(s -> s.getVersion() + 1).orElse(1);
    List<SettlementEvent> events = eventStore.findByAggregateIdAndVersionAfter(
        aggregateId, fromVersion);

    // 3. 스냅샷 + delta 이벤트로 복원
    Settlement settlement = snapshot
        .map(s -> Settlement.fromSnapshot(s))
        .orElse(new Settlement());
    events.forEach(settlement::apply);

    // 4. 스냅샷 저장 트리거 (50 이벤트마다)
    if (events.size() >= 50) {
        snapshotStore.save(settlement.toSnapshot());
    }

    return settlement;
}
```

### 7.4 Event Store (PostgreSQL)

```sql
CREATE TABLE settlement_events (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_id  BIGINT NOT NULL,
    version       INT NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB NOT NULL,
    occurred_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_aggregate_version UNIQUE (aggregate_id, version)
);
```

`UNIQUE (aggregate_id, version)` → 낙관적 동시성 제어.

### 7.5 CQRS Read Model

```sql
CREATE TABLE settlement_read_model (
    id                BIGINT PRIMARY KEY,
    payment_id        BIGINT NOT NULL,
    order_id          BIGINT NOT NULL,
    seller_id         BIGINT NOT NULL,
    payment_amount    NUMERIC(15,2),
    commission        NUMERIC(15,2),
    net_amount        NUMERIC(15,2),
    refunded_amount   NUMERIC(15,2) DEFAULT 0,
    status            VARCHAR(30),
    settlement_date   DATE,
    confirmed_at      TIMESTAMP,
    withdrawal_id     BIGINT,
    last_event_id     BIGINT,
    updated_at        TIMESTAMP DEFAULT NOW()
);
```

### 7.6 Data Flow

```
[Command]                          [Query]
    │                                  │
    ▼                                  ▼
Settlement.create()              settlement_read_model
    │                                  ▲
    ▼                                  │
settlement_events (INSERT)       Projection Consumer
    │                                  ▲
    ▼                                  │
Outbox → Debezium → Kafka ─────────────┘
                        │
                        └──→ ES indexing
                        └──→ Ledger journal entry
```

---

## 8. Phase 5: Withdrawal Model

### 8.1 Flow

```
판매자 출금 요청 → 잔액 확인 (Ledger, SELECT FOR UPDATE) → Withdrawal 생성 (REQUESTED)
→ 관리자 승인 (APPROVED)
→ 은행 이체 시작 (PROCESSING) + Ledger 분개 "WITHDRAWAL_INITIATED"
→ 이체 완료 (COMPLETED) + Ledger 분개 "WITHDRAWAL_COMPLETED"
→ 이체 실패 (FAILED) + Ledger 보상 분개 "WITHDRAWAL_FAILED"
```

> **Ledger 분개 타이밍**: APPROVED가 아닌 **PROCESSING 전환 시점**에 첫 분개를 기록한다. 이렇게 하면 승인 후 ~ 이체 시작 전 실패 시 Ledger에 dangling 분개가 남지 않는다.

> **동시성 제어**: 출금 요청 시 `SELECT ... FOR UPDATE`로 해당 판매자의 accounts 행을 잠근다. 이를 통해 두 출금 요청이 동시에 같은 잔액을 읽고 통과하는 TOCTOU 문제를 방지한다.

### 8.2 Domain Model

```java
public class Withdrawal {
    private Long id;
    private Long sellerId;
    private Money amount;
    private WithdrawalStatus status;
    private String bankName;
    private String bankAccountNumber;
    private String bankAccountHolder;
    private String transferReferenceId;
    private String rejectionReason;
    private String failureReason;
    private int retryCount;
    private static final int MAX_RETRY = 3;

    public static Withdrawal request(Long sellerId, Money amount, Money availableBalance,
                                      String bankName, String accountNumber, String holder,
                                      Money minimumAmount) { ... }

    // State transitions: REQUESTED → APPROVED → PROCESSING → COMPLETED
    //                    REQUESTED → REJECTED
    //                    PROCESSING → FAILED → retry → APPROVED
    //                    PROCESSING → FAILED_PERMANENT (after MAX_RETRY)
}
```

### 8.3 DB Schema

```sql
CREATE TABLE withdrawals (
    id                    BIGSERIAL PRIMARY KEY,
    seller_id             BIGINT NOT NULL REFERENCES sellers(id),
    amount                NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    status                VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
    bank_name             VARCHAR(50) NOT NULL,
    bank_account_number   VARCHAR(50) NOT NULL,
    bank_account_holder   VARCHAR(100) NOT NULL,
    transfer_reference_id VARCHAR(100),
    rejection_reason      VARCHAR(500),
    failure_reason        VARCHAR(500),
    retry_count           INT NOT NULL DEFAULT 0,
    requested_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    approved_at           TIMESTAMP,
    completed_at          TIMESTAMP,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## 9. Phase 6: Performance Optimization & Zero-Downtime Migration

### 9.1 Batch Partitioning

SellerIdRangePartitioner로 판매자 ID 범위별 8개 스레드 병렬 처리.

### 9.2 Bulk INSERT

JPA saveAll 대신 JDBC batchUpdate로 벌크 INSERT.

### 9.3 Table Partitioning

`ledger_lines`와 `settlement_events`를 월별 RANGE 파티셔닝. pg_partman으로 자동화.

### 9.4 Balance Snapshot + Delta

매일 자정 `account_balance_snapshots` 저장. 현재 잔액 = 마지막 스냅샷 + 이후 delta.

```sql
CREATE TABLE account_balance_snapshots (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT NOT NULL REFERENCES accounts(id),
    balance     NUMERIC(15,2) NOT NULL,
    snapshot_at DATE NOT NULL,
    CONSTRAINT uq_account_snapshot UNIQUE (account_id, snapshot_at)
);
```

### 9.5 Zero-Downtime Migration (settlements → Ledger)

```
Phase A: Dual-Write (2주)
  - 기존 settlements + Ledger 양쪽에 기록
  - 매일 대사 배치로 금액 일치 검증

Phase B: Shadow Read (2주)
  - 읽기 시 양쪽 조회, 응답은 기존 기준
  - 불일치 로그, 0%까지 유지

Phase C: Cutover (1주)
  - 읽기 Primary를 Ledger로 전환
  - 기존은 Shadow (롤백 가능)

Phase D: Cleanup
  - 기존 금액 컬럼 deprecated → 삭제
```

### 9.6 Backfill

기존 CONFIRMED/DONE 정산을 Ledger에 소급 분개. idempotencyKey(`BACKFILL_SETTLEMENT:{id}`)로 멱등성 보장.

---

## 10. Money Value Object

```java
public record Money(BigDecimal amount, Currency currency) {
    public static final Money ZERO = new Money(BigDecimal.ZERO, Currency.KRW);

    public Money {
        if (amount == null) throw new IllegalArgumentException("Amount cannot be null");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money multiply(BigDecimal rate) {
        return new Money(this.amount.multiply(rate), this.currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount) < 0;
    }

    private void requireSameCurrency(Money other) {
        if (this.currency != other.currency) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    public enum Currency { KRW, USD }
}
```

> 기존 코드베이스는 `BigDecimal`을 직접 사용. 새로 추가되는 Ledger/Withdrawal 도메인에서 `Money` VO를 사용하고, 기존 Payment/Settlement는 점진적으로 전환.

---

## 11. SettlementStatus Reconciliation

기존 `SettlementStatus` enum에 11개 상태가 있으나, Event Sourcing 도입 후 상태는 이벤트 재생으로 결정된다.

**전환 전략**:
- Event Sourcing 적용 후 Settlement의 상태는 이벤트 스트림에서 파생
- 기존 `SettlementStatus` enum은 **Read Model용으로 유지** (settlement_read_model.status)
- 레거시 상태(PENDING, WAITING_APPROVAL, CALCULATED)는 deprecated 처리, 새 정산부터는 사용하지 않음
- 축소된 핵심 상태: `REQUESTED → PROCESSING → DONE / FAILED / CANCELED`

---

## 12. Required Dependencies

### build.gradle.kts 추가 항목

```kotlin
// Kafka
implementation("org.springframework.kafka:spring-kafka")
testImplementation("org.springframework.kafka:spring-kafka-test")

// Jackson for Kafka JSON serialization (이미 있을 수 있음)
implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
```

### Infrastructure (docker-compose)

```yaml
# 기존: postgres, elasticsearch
# 추가:
zookeeper:
  image: confluentinc/cp-zookeeper:7.6.0

kafka:
  image: confluentinc/cp-kafka:7.6.0

kafka-connect:
  image: debezium/connect:2.6
  # Debezium PostgreSQL connector 내장

# PostgreSQL 설정 변경 필요:
# wal_level = logical  (postgresql.conf 또는 docker-compose env)
```

> Debezium은 애플리케이션 의존성이 아닌 **Kafka Connect 컨테이너**로 별도 배포.

---

## 13. API Endpoints

### Ledger

| Method | Path                              | Description      |
|--------|-----------------------------------|------------------|
| GET    | /api/ledger/accounts/{code}/balance | 계정 잔액 조회    |
| GET    | /api/ledger/accounts/{code}/entries | 계정별 분개 이력  |
| GET    | /api/ledger/trial-balance           | Trial Balance    |

### Withdrawal

| Method | Path                                    | Description        |
|--------|-----------------------------------------|--------------------|
| POST   | /api/withdrawals                        | 출금 요청           |
| GET    | /api/withdrawals/{id}                   | 출금 상세 조회       |
| GET    | /api/sellers/{sellerId}/withdrawals     | 판매자별 출금 목록    |
| POST   | /api/withdrawals/{id}/approve           | 출금 승인 (관리자)   |
| POST   | /api/withdrawals/{id}/reject            | 출금 거절 (관리자)   |

### Settlement (확장)

| Method | Path                                    | Description           |
|--------|-----------------------------------------|-----------------------|
| GET    | /api/sellers/{sellerId}/settlements     | 판매자별 정산 목록     |
| GET    | /api/sellers/{sellerId}/balance         | 판매자 정산 가능 잔액  |

---

## 14. Testing Strategy

### Unit Tests
- **JournalEntry invariant**: 차변/대변 불균형 시 `UnbalancedJournalEntryException` 발생 검증
- **Settlement state machine**: 각 이벤트 적용 후 상태 전이 검증
- **CommissionCalculation**: 경계값 (0원, 1원, 최대 금액) 테스트
- **Withdrawal domain**: 잔액 부족, 최소 금액 미달, MAX_RETRY 초과 검증
- **Money VO**: 통화 불일치, ZERO, 산술 연산 검증

### Integration Tests
- **Event Store**: 이벤트 저장 → 복원 → 스냅샷 → 스냅샷 이후 이벤트만 재생 검증
- **Outbox → Kafka**: 트랜잭션 커밋 후 Kafka 메시지 수신 확인 (Testcontainers)
- **Ledger 정합성**: 분개 기록 후 `SUM(DEBIT) == SUM(CREDIT)` across all accounts 검증
- **CQRS Projection**: 이벤트 발행 후 settlement_read_model 업데이트 확인

### Reconciliation Tests
- 매 테스트 스위트 후 Trial Balance 자동 검증
- Dual-Write 시 legacy vs Ledger 금액 일치 검증

### Test Infrastructure
- Testcontainers: PostgreSQL, Kafka, Elasticsearch
- 기존 Jacoco 70% 커버리지 요구사항 유지
