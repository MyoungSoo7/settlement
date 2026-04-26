# Payout + Bank Transfer Adapter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 정산 DONE 후 셀러 계좌로 실제 송금하는 어댑터 신설. `Payout` 도메인 + `BankTransferPort`(Mock 펌뱅킹) + `Settlement.PAID` 상태 추가 + 송금 분개. 면접관 "DONE 다음에 돈은 어떻게 보내요?"에 대한 답변 코드.

**Architecture:** 헥사고날 + DDD. `Payout`을 별도 aggregate로 신설(Settlement와 1:1 관계, settlementId 외래키). `BankTransferPort` Outbound 추상화로 PG 교체 가능성 확보(현재는 Mock, 추후 실 펌뱅킹). 송금 시점에 `Settlement.markPaid()`로 DONE→PAID 전이 + `LedgerService.recordPayoutExecuted` 분개. 멱등성은 `settlementId` UNIQUE + 도메인 가드 이중.

**Tech Stack:** Spring Boot 3.x / JPA / PostgreSQL / Flyway / JUnit 5 / Mockito.

**Scope guardrails (이번 plan에 포함하지 않는 것):**
- 실제 토스페이먼츠 펌뱅킹 API 연동 (Mock만, 추후 별도 plan)
- 송금 실패 시 자동 재시도 정책 (수동 재시도 가능, 자동 재시도 정책은 후속)
- 송금 한도/수수료 처리 (펌뱅킹 PG가 부과하는 송금 수수료)
- 환불에 의한 송금 취소 처리 (Settlement이 PAID 후 환불 시 별도 보상 송금 또는 회수 — 후속 plan)
- 셀러 정산 주기별 batch 합산 송금 (현 plan은 settlement 1건당 1 payout)

**Verification anchor (전 chunk 통과 후 만족해야 할 시나리오):**
> 100,000원 결제 → 수수료 3% → Settlement DONE (sellerId=42, net=97,000)
> ↓
> PayoutScheduler 매일 04시 cron → DONE 상태 + 미송금 정산 조회 (셀러별)
> ↓
> ExecutePayoutUseCase:
>   - 멱등성 체크: (settlementId) 기준 기존 Payout 있으면 반환
>   - Payout(settlementId=10, sellerId=42, amount=97,000, status=PENDING) INSERT
>   - BankTransferPort.transfer(seller, 97,000) 호출 → Mock UUID 반환
>   - Payout.markSucceeded(bankTransactionId) + Settlement.markPaid() (DONE → PAID)
>   - JournalEntry(PAYOUT_EXECUTED, payoutId): SELLER_PAYABLE Dr 97,000 / PLATFORM_CASH Cr 97,000
> ↓
> 같은 settlementId로 재시도 → 새 INSERT 0, 기존 Payout 반환 (DB UNIQUE + 도메인 가드)
> ↓
> 송금 실패 시 → Payout FAILED, Settlement DONE 유지 (수동 재시도 가능)

---

## Chunk 1: Payout 도메인 + 영속화

목표: `Payout` aggregate 신설 (Settlement와 분리된 송금 도메인). V38 마이그레이션으로 payouts 테이블 + Settlement PAID 상태 허용.

### Task 1.1: PayoutStatus enum + Payout aggregate (TDD)

**Files:**
- Create: `src/main/java/github/lms/lemuel/payout/domain/PayoutStatus.java`
- Create: `src/main/java/github/lms/lemuel/payout/domain/Payout.java`
- Create: `src/test/java/github/lms/lemuel/payout/domain/PayoutTest.java`

> 새 패키지 `payout` 신설. settlement/payment/seller 도메인과 동급의 헥사고날 모듈.

- [ ] **Step 1: failing test 작성**

```java
package github.lms.lemuel.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class PayoutTest {

    @Nested
    @DisplayName("Payout 생성")
    class Creation {
        @Test
        @DisplayName("정상: 양수 amount + sellerId/settlementId 있으면 PENDING으로 생성")
        void create_ok() {
            Payout payout = Payout.request(10L, 42L, new BigDecimal("97000"));

            assertThat(payout.getSettlementId()).isEqualTo(10L);
            assertThat(payout.getSellerId()).isEqualTo(42L);
            assertThat(payout.getAmount()).isEqualByComparingTo("97000");
            assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PENDING);
            assertThat(payout.getRequestedAt()).isNotNull();
        }

        @Test
        @DisplayName("amount 0/음수 거부")
        void amount_must_be_positive() {
            assertThatThrownBy(() -> Payout.request(10L, 42L, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Payout.request(10L, 42L, new BigDecimal("-1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("settlementId/sellerId 누락 거부")
        void ids_required() {
            assertThatThrownBy(() -> Payout.request(null, 42L, new BigDecimal("97000")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Payout.request(10L, null, new BigDecimal("97000")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("상태 머신")
    class StateMachine {
        @Test
        @DisplayName("PENDING → SUCCEEDED with bankTransactionId")
        void mark_succeeded() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            p.markSucceeded("BANK-TX-001");
            assertThat(p.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
            assertThat(p.getBankTransactionId()).isEqualTo("BANK-TX-001");
            assertThat(p.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("PENDING → FAILED with reason")
        void mark_failed() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            p.markFailed("계좌 정보 오류");
            assertThat(p.getStatus()).isEqualTo(PayoutStatus.FAILED);
            assertThat(p.getFailureReason()).isEqualTo("계좌 정보 오류");
        }

        @Test
        @DisplayName("이미 SUCCEEDED 상태에서 markSucceeded 거부")
        void cannot_succeed_twice() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            p.markSucceeded("BANK-TX-001");
            assertThatThrownBy(() -> p.markSucceeded("BANK-TX-002"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("FAILED → 재시도 (PENDING으로 복귀)")
        void retry_from_failed() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            p.markFailed("일시 오류");
            p.retry();
            assertThat(p.getStatus()).isEqualTo(PayoutStatus.PENDING);
            assertThat(p.getFailureReason()).isNull();
        }

        @Test
        @DisplayName("PENDING에서 retry 거부")
        void retry_only_from_failed() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            assertThatThrownBy(p::retry).isInstanceOf(IllegalStateException.class);
        }
    }
}
```

- [ ] **Step 2: 테스트 → FAIL 확인**

```bash
./gradlew test --tests "github.lms.lemuel.payout.domain.PayoutTest"
```

- [ ] **Step 3: PayoutStatus enum 작성**

```java
package github.lms.lemuel.payout.domain;

public enum PayoutStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
```

- [ ] **Step 4: Payout 도메인 작성**

```java
package github.lms.lemuel.payout.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Payout {

    private Long id;
    private Long settlementId;
    private Long sellerId;
    private BigDecimal amount;
    private PayoutStatus status;
    private String bankTransactionId;
    private String failureReason;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Payout() {}

    public static Payout request(Long settlementId, Long sellerId, BigDecimal amount) {
        if (settlementId == null) throw new IllegalArgumentException("settlementId required");
        if (sellerId == null) throw new IllegalArgumentException("sellerId required");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        Payout p = new Payout();
        p.settlementId = settlementId;
        p.sellerId = sellerId;
        p.amount = amount;
        p.status = PayoutStatus.PENDING;
        p.requestedAt = LocalDateTime.now();
        p.createdAt = p.requestedAt;
        p.updatedAt = p.requestedAt;
        return p;
    }

    public void markSucceeded(String bankTransactionId) {
        if (this.status != PayoutStatus.PENDING) {
            throw new IllegalStateException("Cannot mark SUCCEEDED. status=" + this.status);
        }
        this.status = PayoutStatus.SUCCEEDED;
        this.bankTransactionId = bankTransactionId;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = this.completedAt;
    }

    public void markFailed(String reason) {
        if (this.status != PayoutStatus.PENDING) {
            throw new IllegalStateException("Cannot mark FAILED. status=" + this.status);
        }
        this.status = PayoutStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    public void retry() {
        if (this.status != PayoutStatus.FAILED) {
            throw new IllegalStateException("Can only retry FAILED. status=" + this.status);
        }
        this.status = PayoutStatus.PENDING;
        this.failureReason = null;
        this.updatedAt = LocalDateTime.now();
    }

    public Payout(Long id, Long settlementId, Long sellerId, BigDecimal amount,
                  PayoutStatus status, String bankTransactionId, String failureReason,
                  LocalDateTime requestedAt, LocalDateTime completedAt,
                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.status = status;
        this.bankTransactionId = bankTransactionId;
        this.failureReason = failureReason;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id already assigned");
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAmount() { return amount; }
    public PayoutStatus getStatus() { return status; }
    public String getBankTransactionId() { return bankTransactionId; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 5: 테스트 PASS 확인 + commit**

```bash
./gradlew test --tests "github.lms.lemuel.payout.domain.PayoutTest"
git add src/main/java/github/lms/lemuel/payout/domain/PayoutStatus.java \
        src/main/java/github/lms/lemuel/payout/domain/Payout.java \
        src/test/java/github/lms/lemuel/payout/domain/PayoutTest.java
git commit -m "feat(payout): add Payout aggregate with state machine and invariants"
```

---

### Task 1.2: V38 마이그레이션 — payouts 테이블 + chk_settlements_status 갱신

**Files:**
- Create: `src/main/resources/db/migration/V38__create_payouts_and_settlement_paid.sql`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- V38: payouts 테이블 신설 + Settlement PAID 상태 추가
-- 정산 DONE → 셀러 송금 → PAID 흐름 활성화

-- 1. payouts 테이블
CREATE TABLE payouts (
    id BIGSERIAL PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    bank_transaction_id VARCHAR(100),
    failure_reason TEXT,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payout_settlement FOREIGN KEY (settlement_id) REFERENCES settlements(id),
    CONSTRAINT chk_payouts_amount CHECK (amount > 0),
    CONSTRAINT chk_payouts_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);

-- 정산 1건당 송금 1건 보장 (멱등성 + 비즈니스 invariant)
CREATE UNIQUE INDEX idx_payouts_settlement_id_unique ON payouts(settlement_id);

-- 조회 최적화
CREATE INDEX idx_payouts_seller_id ON payouts(seller_id);
CREATE INDEX idx_payouts_status ON payouts(status);
CREATE INDEX idx_payouts_requested_at ON payouts(requested_at);

-- 2. Settlement status에 PAID 추가
ALTER TABLE settlements DROP CONSTRAINT IF EXISTS chk_settlements_status;
ALTER TABLE settlements
    ADD CONSTRAINT chk_settlements_status
    CHECK (status IN ('REQUESTED', 'PROCESSING', 'DONE', 'PAID', 'FAILED', 'CANCELED'));

COMMENT ON TABLE payouts IS '셀러 송금 이력 — 정산 DONE → 펌뱅킹 송금 → SUCCEEDED/FAILED';
COMMENT ON COLUMN settlements.status IS '정산 상태: REQUESTED|PROCESSING|DONE|PAID|FAILED|CANCELED';
```

- [ ] **Step 2: 테스트로 검증**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL (H2가 마이그레이션을 실행하고 모든 테스트 통과).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V38__create_payouts_and_settlement_paid.sql
git commit -m "feat(db): V38 migration for payouts table and Settlement.PAID status"
```

---

### Task 1.3: Payout JPA 엔티티 + 매퍼 + 리포지토리

**Files:**
- Create: `src/main/java/github/lms/lemuel/payout/adapter/out/persistence/PayoutJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/payout/adapter/out/persistence/SpringDataPayoutJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/payout/adapter/out/persistence/PayoutMapper.java`

`PayoutJpaEntity.java`:
```java
package github.lms.lemuel.payout.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payouts",
       uniqueConstraints = @UniqueConstraint(
           name = "idx_payouts_settlement_id_unique",
           columnNames = "settlement_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PayoutJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "bank_transaction_id", length = 100)
    private String bankTransactionId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
```

`SpringDataPayoutJpaRepository.java`:
```java
package github.lms.lemuel.payout.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SpringDataPayoutJpaRepository extends JpaRepository<PayoutJpaEntity, Long> {
    Optional<PayoutJpaEntity> findBySettlementId(Long settlementId);
}
```

`PayoutMapper.java`:
```java
package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;

public final class PayoutMapper {
    private PayoutMapper() {}

    public static PayoutJpaEntity toJpa(Payout d) {
        return PayoutJpaEntity.builder()
                .id(d.getId())
                .settlementId(d.getSettlementId())
                .sellerId(d.getSellerId())
                .amount(d.getAmount())
                .status(d.getStatus().name())
                .bankTransactionId(d.getBankTransactionId())
                .failureReason(d.getFailureReason())
                .requestedAt(d.getRequestedAt())
                .completedAt(d.getCompletedAt())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }

    public static Payout toDomain(PayoutJpaEntity e) {
        return new Payout(
                e.getId(), e.getSettlementId(), e.getSellerId(), e.getAmount(),
                PayoutStatus.valueOf(e.getStatus()),
                e.getBankTransactionId(), e.getFailureReason(),
                e.getRequestedAt(), e.getCompletedAt(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
```

- [ ] **Step 1-3: 파일 작성 + 컴파일 + commit**

```bash
./gradlew compileJava
git add src/main/java/github/lms/lemuel/payout/adapter/out/persistence/
git commit -m "feat(payout): add Payout JPA entity, repository, and mapper"
```

---

### Task 1.4: SavePayoutPort + LoadPayoutPort + PayoutPersistenceAdapter

**Files:**
- Create: `src/main/java/github/lms/lemuel/payout/application/port/out/SavePayoutPort.java`
- Create: `src/main/java/github/lms/lemuel/payout/application/port/out/LoadPayoutPort.java`
- Create: `src/main/java/github/lms/lemuel/payout/adapter/out/persistence/PayoutPersistenceAdapter.java`

`SavePayoutPort.java`:
```java
package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.Payout;

public interface SavePayoutPort {
    Payout save(Payout payout);
}
```

`LoadPayoutPort.java`:
```java
package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.Payout;

import java.util.Optional;

public interface LoadPayoutPort {
    Optional<Payout> findBySettlementId(Long settlementId);
}
```

`PayoutPersistenceAdapter.java`:
```java
package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PayoutPersistenceAdapter implements SavePayoutPort, LoadPayoutPort {

    private final SpringDataPayoutJpaRepository repository;

    @Override
    public Payout save(Payout payout) {
        PayoutJpaEntity saved = repository.save(PayoutMapper.toJpa(payout));
        if (payout.getId() == null && saved.getId() != null) {
            payout.assignId(saved.getId());
        }
        return payout;
    }

    @Override
    public Optional<Payout> findBySettlementId(Long settlementId) {
        return repository.findBySettlementId(settlementId).map(PayoutMapper::toDomain);
    }
}
```

- [ ] **Step 1-3: 파일 작성 + 회귀 테스트 + commit**

```bash
./gradlew test
git add src/main/java/github/lms/lemuel/payout/application/port/out/ \
        src/main/java/github/lms/lemuel/payout/adapter/out/persistence/PayoutPersistenceAdapter.java
git commit -m "feat(payout): add Payout persistence ports and adapter"
```

---

## Chunk 2: BankTransferPort + Mock

목표: 외부 펌뱅킹을 추상화하고 Mock 구현 제공.

### Task 2.1: BankTransferPort + MockBankTransferAdapter

**Files:**
- Create: `src/main/java/github/lms/lemuel/payout/application/port/out/BankTransferPort.java`
- Create: `src/main/java/github/lms/lemuel/payout/adapter/out/external/MockBankTransferAdapter.java`

`BankTransferPort.java`:
```java
package github.lms.lemuel.payout.application.port.out;

import java.math.BigDecimal;

/**
 * 외부 펌뱅킹/송금 게이트웨이 추상화.
 * 현재는 MockBankTransferAdapter 구현. 추후 토스페이먼츠 펌뱅킹 / 실 PG로 교체 가능.
 */
public interface BankTransferPort {

    record BankAccount(String bankName, String accountNumber, String accountHolder) {
        public BankAccount {
            if (bankName == null || accountNumber == null || accountHolder == null) {
                throw new IllegalArgumentException("bank account fields required");
            }
        }
    }

    record TransferResult(String bankTransactionId) {}

    /**
     * 송금 실행. 성공 시 외부 트랜잭션 ID 반환, 실패 시 BankTransferException.
     */
    TransferResult transfer(BankAccount account, BigDecimal amount);

    class BankTransferException extends RuntimeException {
        public BankTransferException(String message) { super(message); }
        public BankTransferException(String message, Throwable cause) { super(message, cause); }
    }
}
```

`MockBankTransferAdapter.java`:
```java
package github.lms.lemuel.payout.adapter.out.external;

import github.lms.lemuel.payout.application.port.out.BankTransferPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mock 펌뱅킹 어댑터 — 항상 성공, UUID 트랜잭션 ID 반환.
 * 추후 토스페이먼츠 펌뱅킹 등 실 어댑터로 교체.
 */
@Slf4j
@Component
public class MockBankTransferAdapter implements BankTransferPort {

    @Override
    public TransferResult transfer(BankAccount account, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BankTransferException("transfer amount must be positive");
        }
        String txId = "MOCK-BANK-" + UUID.randomUUID();
        log.info("Mock bank transfer: bank={}, account={}, amount={}, txId={}",
                account.bankName(), account.accountNumber(), amount, txId);
        return new TransferResult(txId);
    }
}
```

- [ ] **Step 1-3: 파일 작성 + 컴파일 + commit**

```bash
./gradlew compileJava
git add src/main/java/github/lms/lemuel/payout/application/port/out/BankTransferPort.java \
        src/main/java/github/lms/lemuel/payout/adapter/out/external/MockBankTransferAdapter.java
git commit -m "feat(payout): add BankTransferPort + MockBankTransferAdapter"
```

---

## Chunk 3: SettlementStatus PAID + ExecutePayoutUseCase

목표: Settlement에 PAID 상태 추가 + 송금 실행 UseCase (멱등성 + Settlement 전이 + Ledger 분개).

### Task 3.1: SettlementStatus.PAID + Settlement.markPaid() + canTransitionTo (TDD)

**Files:**
- Modify: `src/main/java/github/lms/lemuel/settlement/domain/SettlementStatus.java`
- Modify: `src/main/java/github/lms/lemuel/settlement/domain/Settlement.java`
- Create: `src/test/java/github/lms/lemuel/settlement/domain/SettlementMarkPaidTest.java`

- [ ] **Step 1: failing test 작성**

```java
package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class SettlementMarkPaidTest {

    private Settlement done(Long sellerId) {
        return new Settlement(
                10L, 100L, 200L, sellerId,
                new BigDecimal("100000"), BigDecimal.ZERO,
                new BigDecimal("3000"), new BigDecimal("97000"),
                SettlementStatus.DONE, LocalDate.of(2026, 4, 26),
                null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("DONE → PAID 전이")
    void mark_paid_from_done() {
        Settlement s = done(42L);
        s.markPaid();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAID);
    }

    @Test
    @DisplayName("DONE이 아닌 상태에서 markPaid 거부")
    void cannot_mark_paid_from_non_done() {
        Settlement s = new Settlement(
                10L, 100L, 200L, 42L,
                new BigDecimal("100000"), BigDecimal.ZERO,
                new BigDecimal("3000"), new BigDecimal("97000"),
                SettlementStatus.PROCESSING, LocalDate.of(2026, 4, 26),
                null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        assertThatThrownBy(s::markPaid).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 PAID 상태에서 재호출 거부")
    void cannot_mark_paid_twice() {
        Settlement s = done(42L);
        s.markPaid();
        assertThatThrownBy(s::markPaid).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("canTransitionTo: DONE → PAID 허용")
    void transition_done_to_paid() {
        assertThat(SettlementStatus.DONE.canTransitionTo(SettlementStatus.PAID)).isTrue();
    }

    @Test
    @DisplayName("canTransitionTo: PAID는 종료 상태 (전이 불가)")
    void paid_is_terminal() {
        assertThat(SettlementStatus.PAID.canTransitionTo(SettlementStatus.DONE)).isFalse();
        assertThat(SettlementStatus.PAID.canTransitionTo(SettlementStatus.CANCELED)).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 → FAIL 확인**

```bash
./gradlew test --tests "github.lms.lemuel.settlement.domain.SettlementMarkPaidTest"
```

- [ ] **Step 3: SettlementStatus enum 갱신**

```java
package github.lms.lemuel.settlement.domain;

/**
 * 정산 상태 Enum
 *
 * 상태 전이:
 *   REQUESTED → PROCESSING → DONE → PAID
 *                          ↘ FAILED → REQUESTED (재시도)
 *   REQUESTED → CANCELED
 */
public enum SettlementStatus {
    REQUESTED,
    PROCESSING,
    DONE,
    PAID,
    FAILED,
    CANCELED;

    public static SettlementStatus fromString(String status) {
        try {
            return SettlementStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return REQUESTED;
        }
    }

    public boolean canTransitionTo(SettlementStatus targetStatus) {
        return switch (this) {
            case REQUESTED -> targetStatus == PROCESSING || targetStatus == CANCELED;
            case PROCESSING -> targetStatus == DONE || targetStatus == FAILED;
            case DONE -> targetStatus == PAID;
            case FAILED -> targetStatus == REQUESTED;
            case PAID, CANCELED -> false;
        };
    }
}
```

- [ ] **Step 4: Settlement.markPaid() 추가**

`Settlement.java`의 `cancel()` 위 또는 적절한 위치에 추가:
```java
    /**
     * 정산 송금 완료 (DONE → PAID)
     */
    public void markPaid() {
        if (this.status != SettlementStatus.DONE) {
            throw new IllegalStateException(
                "Cannot mark PAID. Current status: " + this.status + ". Expected: DONE");
        }
        this.status = SettlementStatus.PAID;
        this.updatedAt = LocalDateTime.now();
    }
```

- [ ] **Step 5: 테스트 PASS + 회귀 + commit**

```bash
./gradlew test
git add src/main/java/github/lms/lemuel/settlement/domain/SettlementStatus.java \
        src/main/java/github/lms/lemuel/settlement/domain/Settlement.java \
        src/test/java/github/lms/lemuel/settlement/domain/SettlementMarkPaidTest.java
git commit -m "feat(settlement): add PAID status and markPaid() transition (DONE → PAID)"
```

---

### Task 3.2: ExecutePayoutCommand + ExecutePayoutPort + LedgerService 확장

**Files:**
- Create: `src/main/java/github/lms/lemuel/payout/application/port/in/ExecutePayoutCommand.java`
- Create: `src/main/java/github/lms/lemuel/payout/application/port/in/ExecutePayoutPort.java`
- Modify: `src/main/java/github/lms/lemuel/ledger/application/port/in/RecordJournalEntryUseCase.java` (add `recordPayoutExecuted` method)
- Modify: `src/main/java/github/lms/lemuel/ledger/application/service/LedgerService.java` (implement)

`ExecutePayoutCommand.java`:
```java
package github.lms.lemuel.payout.application.port.in;

public record ExecutePayoutCommand(Long settlementId) {
    public ExecutePayoutCommand {
        if (settlementId == null) throw new IllegalArgumentException("settlementId required");
    }
}
```

`ExecutePayoutPort.java`:
```java
package github.lms.lemuel.payout.application.port.in;

import github.lms.lemuel.payout.domain.Payout;

public interface ExecutePayoutPort {
    /**
     * 정산 송금 실행. 동일 settlementId 재호출 시 기존 Payout 반환 (멱등).
     */
    Payout execute(ExecutePayoutCommand command);
}
```

`RecordJournalEntryUseCase.java`에 메서드 추가:
```java
    void recordPayoutExecuted(Long payoutId, Long sellerId, Money amount);
```

`LedgerService.java`에 구현 추가:
```java
    @Override
    public void recordPayoutExecuted(Long payoutId, Long sellerId, Money amount) {
        Account platformCash = loadAccountPort.getOrCreate(Account.createPlatformCash());
        Account sellerPayable = loadAccountPort.getOrCreate(Account.createSellerPayable(sellerId));

        recordJournalEntry(JournalEntry.create(
                "PAYOUT_EXECUTED", "PAYOUT", payoutId,
                List.of(
                        LedgerLine.debit(sellerPayable, amount),
                        LedgerLine.credit(platformCash, amount)
                ),
                "PAYOUT_EXECUTED:" + payoutId,
                "정산 송금 실행 (셀러 미지급금 청산 → 현금 유출)"
        ));

        log.info("송금 Ledger 분개 완료: payoutId={}, sellerId={}, amount={}",
                payoutId, sellerId, amount);
    }
```

- [ ] **Step 1-3: 파일 작성 + 컴파일**

```bash
./gradlew compileJava
```

> 본 task는 별도 commit하지 않음. Task 3.3에서 함께 commit.

---

### Task 3.3: ExecutePayoutUseCase 구현 (TDD)

**Files:**
- Create: `src/main/java/github/lms/lemuel/payout/application/service/ExecutePayoutService.java`
- Create: `src/test/java/github/lms/lemuel/payout/application/service/ExecutePayoutServiceTest.java`
- Modify: `src/main/java/github/lms/lemuel/settlement/application/port/out/LoadSettlementPort.java` (이미 findById 있음, 변경 없을 수도)
- Modify: `src/main/java/github/lms/lemuel/seller/application/port/out/LoadSellerPort.java` (findById 있는지 확인, 없으면 추가)

> 주의: `LoadSettlementPort`, `LoadSellerPort` 시그니처가 충분한지 확인 후, 부족하면 추가. ExecutePayoutService는 settlementId로 Settlement + Seller(bank info) 조회 필요.

- [ ] **Step 1: failing test 작성**

```java
package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.payout.application.port.in.ExecutePayoutCommand;
import github.lms.lemuel.payout.application.port.out.BankTransferPort;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutePayoutServiceTest {

    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private LoadSellerPort loadSellerPort;
    @Mock private LoadPayoutPort loadPayoutPort;
    @Mock private SavePayoutPort savePayoutPort;
    @Mock private BankTransferPort bankTransferPort;
    @Mock private RecordJournalEntryUseCase recordJournalEntryUseCase;

    @InjectMocks private ExecutePayoutService service;

    private Settlement done() {
        return new Settlement(
                10L, 100L, 200L, 42L,
                new BigDecimal("100000"), BigDecimal.ZERO,
                new BigDecimal("3000"), new BigDecimal("97000"),
                SettlementStatus.DONE, LocalDate.of(2026, 4, 26),
                null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    private Seller seller() {
        Seller s = Seller.create(99L, "테스트상점", "1234567890", "홍길동", "010", "test@x");
        s.setId(42L);
        s.approve();
        s.setBankName("우리은행");
        s.setBankAccountNumber("1002-123-456789");
        s.setBankAccountHolder("홍길동");
        return s;
    }

    @Test
    @DisplayName("정상 송금: Payout INSERT, BankTransfer 호출, Settlement PAID, Ledger 분개")
    void execute_payout_happy_path() {
        given(loadPayoutPort.findBySettlementId(10L)).willReturn(Optional.empty());
        given(loadSettlementPort.findById(10L)).willReturn(Optional.of(done()));
        given(loadSellerPort.findById(42L)).willReturn(Optional.of(seller()));
        given(savePayoutPort.save(any(Payout.class))).willAnswer(inv -> {
            Payout p = inv.getArgument(0);
            p.assignId(7L);
            return p;
        });
        given(bankTransferPort.transfer(any(), eq(new BigDecimal("97000"))))
                .willReturn(new BankTransferPort.TransferResult("BANK-TX-001"));
        given(saveSettlementPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        Payout result = service.execute(new ExecutePayoutCommand(10L));

        assertThat(result.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(result.getBankTransactionId()).isEqualTo("BANK-TX-001");

        // Settlement DONE → PAID 전이 검증
        ArgumentCaptor<Settlement> settlementCap = ArgumentCaptor.forClass(Settlement.class);
        verify(saveSettlementPort).save(settlementCap.capture());
        assertThat(settlementCap.getValue().getStatus()).isEqualTo(SettlementStatus.PAID);

        // Ledger 분개
        verify(recordJournalEntryUseCase).recordPayoutExecuted(
                eq(7L), eq(42L), eq(Money.krw(new BigDecimal("97000"))));
    }

    @Test
    @DisplayName("멱등성: 동일 settlementId 재호출 → 기존 Payout 반환")
    void idempotent_replay() {
        Payout existing = mock(Payout.class);
        given(loadPayoutPort.findBySettlementId(10L)).willReturn(Optional.of(existing));

        Payout result = service.execute(new ExecutePayoutCommand(10L));

        assertThat(result).isSameAs(existing);
        verify(bankTransferPort, never()).transfer(any(), any());
        verify(savePayoutPort, never()).save(any());
        verify(saveSettlementPort, never()).save(any());
    }

    @Test
    @DisplayName("Settlement DONE 아님 → IllegalStateException")
    void settlement_not_done() {
        given(loadPayoutPort.findBySettlementId(10L)).willReturn(Optional.empty());
        Settlement processing = new Settlement(
                10L, 100L, 200L, 42L,
                new BigDecimal("100000"), BigDecimal.ZERO,
                new BigDecimal("3000"), new BigDecimal("97000"),
                SettlementStatus.PROCESSING, LocalDate.of(2026, 4, 26),
                null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        given(loadSettlementPort.findById(10L)).willReturn(Optional.of(processing));

        assertThatThrownBy(() -> service.execute(new ExecutePayoutCommand(10L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("BankTransfer 실패 → Payout FAILED, Settlement DONE 유지, Ledger 호출 없음")
    void bank_transfer_failure() {
        given(loadPayoutPort.findBySettlementId(10L)).willReturn(Optional.empty());
        given(loadSettlementPort.findById(10L)).willReturn(Optional.of(done()));
        given(loadSellerPort.findById(42L)).willReturn(Optional.of(seller()));
        given(savePayoutPort.save(any(Payout.class))).willAnswer(inv -> {
            Payout p = inv.getArgument(0);
            if (p.getId() == null) p.assignId(7L);
            return p;
        });
        given(bankTransferPort.transfer(any(), any()))
                .willThrow(new BankTransferPort.BankTransferException("PG 장애"));

        assertThatThrownBy(() -> service.execute(new ExecutePayoutCommand(10L)))
                .isInstanceOf(BankTransferPort.BankTransferException.class);

        // Payout은 FAILED 상태로 저장되어야 함 (PENDING + FAILED 두 번 save 호출)
        verify(savePayoutPort, times(2)).save(any());
        verify(saveSettlementPort, never()).save(any()); // Settlement는 DONE 유지
        verify(recordJournalEntryUseCase, never()).recordPayoutExecuted(any(), any(), any());
    }
}
```

- [ ] **Step 2: 테스트 → FAIL 확인**

- [ ] **Step 3: ExecutePayoutService 작성**

```java
package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.payout.application.port.in.ExecutePayoutCommand;
import github.lms.lemuel.payout.application.port.in.ExecutePayoutPort;
import github.lms.lemuel.payout.application.port.out.BankTransferPort;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ExecutePayoutService implements ExecutePayoutPort {

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadSellerPort loadSellerPort;
    private final LoadPayoutPort loadPayoutPort;
    private final SavePayoutPort savePayoutPort;
    private final BankTransferPort bankTransferPort;
    private final RecordJournalEntryUseCase recordJournalEntryUseCase;

    @Override
    public Payout execute(ExecutePayoutCommand command) {
        // 1. 멱등성 체크
        var existing = loadPayoutPort.findBySettlementId(command.settlementId());
        if (existing.isPresent()) {
            log.info("Idempotent payout replay: settlementId={}", command.settlementId());
            return existing.get();
        }

        // 2. Settlement 로드 + DONE 검증
        Settlement settlement = loadSettlementPort.findById(command.settlementId())
                .orElseThrow(() -> new SettlementNotFoundException(
                        "Settlement not found: " + command.settlementId()));
        if (settlement.getStatus() != SettlementStatus.DONE) {
            throw new IllegalStateException(
                "Settlement must be DONE to execute payout. current=" + settlement.getStatus());
        }

        // 3. Seller 로드 + 송금 정보 조립
        Seller seller = loadSellerPort.findById(settlement.getSellerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Seller not found: " + settlement.getSellerId()));
        var account = new BankTransferPort.BankAccount(
                seller.getBankName(),
                seller.getBankAccountNumber(),
                seller.getBankAccountHolder());

        // 4. Payout PENDING 생성
        Payout payout = Payout.request(settlement.getId(), seller.getId(), settlement.getNetAmount());
        Payout savedPayout = savePayoutPort.save(payout);

        // 5. 송금 실행 (실패 시 Payout FAILED 후 예외)
        try {
            BankTransferPort.TransferResult result =
                    bankTransferPort.transfer(account, settlement.getNetAmount());
            savedPayout.markSucceeded(result.bankTransactionId());
            savePayoutPort.save(savedPayout);
        } catch (BankTransferPort.BankTransferException e) {
            log.error("Bank transfer failed. settlementId={}, payoutId={}",
                    settlement.getId(), savedPayout.getId(), e);
            savedPayout.markFailed(e.getMessage());
            savePayoutPort.save(savedPayout);
            throw e;
        }

        // 6. Settlement DONE → PAID 전이
        settlement.markPaid();
        saveSettlementPort.save(settlement);

        // 7. Ledger 분개 (payoutId 멱등키)
        recordJournalEntryUseCase.recordPayoutExecuted(
                savedPayout.getId(), seller.getId(), Money.krw(settlement.getNetAmount()));

        log.info("Payout executed. payoutId={}, settlementId={}, sellerId={}, amount={}",
                savedPayout.getId(), settlement.getId(), seller.getId(), settlement.getNetAmount());

        return savedPayout;
    }
}
```

- [ ] **Step 4: 테스트 PASS + 회귀 + commit**

```bash
./gradlew test
git add src/main/java/github/lms/lemuel/payout/application/ \
        src/main/java/github/lms/lemuel/ledger/application/port/in/RecordJournalEntryUseCase.java \
        src/main/java/github/lms/lemuel/ledger/application/service/LedgerService.java \
        src/test/java/github/lms/lemuel/payout/
git commit -m "feat(payout): ExecutePayoutService with idempotency, bank transfer, ledger posting"
```

> 주의: `LoadSellerPort.findById(Long)` 시그니처가 없을 경우 본 task에 추가. `Seller`에 setBankName 등 setter가 없으면 테스트에서 reconstitution constructor 사용.

---

## Chunk 4: 스케줄러 + 통합 테스트 + ADR

### Task 4.1: PayoutScheduler

**Files:**
- Create: `src/main/java/github/lms/lemuel/payout/adapter/in/scheduler/PayoutScheduler.java`
- Create: `src/main/java/github/lms/lemuel/settlement/application/port/out/LoadSettlementPort.java` 메서드 추가 (필요 시): `List<Settlement> findByStatusAndSeller(SettlementStatus, Long)` 또는 `findByStatus(SettlementStatus)` 등

```java
package github.lms.lemuel.payout.adapter.in.scheduler;

import github.lms.lemuel.payout.application.port.in.ExecutePayoutCommand;
import github.lms.lemuel.payout.application.port.in.ExecutePayoutPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 매일 04시 cron — DONE 상태 정산을 송금.
 * 셀러 정산 주기는 추후 BillingPeriod 모델 도입 시 반영.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutScheduler {

    private final LoadSettlementPort loadSettlementPort;
    private final ExecutePayoutPort executePayoutPort;

    @Scheduled(cron = "0 0 4 * * *")
    public void scheduledExecutePayouts() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("스케줄 실행: 송금 시작 - targetDate={}", yesterday);

        List<Settlement> doneSettlements =
                loadSettlementPort.findBySettlementDateAndStatus(yesterday, SettlementStatus.DONE);

        int success = 0;
        int failed = 0;
        for (Settlement s : doneSettlements) {
            try {
                executePayoutPort.execute(new ExecutePayoutCommand(s.getId()));
                success++;
            } catch (Exception e) {
                log.error("송금 실패: settlementId={}", s.getId(), e);
                failed++;
            }
        }
        log.info("스케줄 완료: 송금 - success={}, failed={}, total={}",
                success, failed, doneSettlements.size());
    }
}
```

> `findBySettlementDateAndStatus`는 이미 LoadSettlementPort에 존재함 (확인). 없다면 추가.

- [ ] **Step 1-3: 작성 + 테스트 + commit**

```bash
./gradlew test
git add src/main/java/github/lms/lemuel/payout/adapter/in/scheduler/PayoutScheduler.java
git commit -m "feat(payout): add PayoutScheduler (04:00 daily cron)"
```

---

### Task 4.2: PayoutFlowIntegrationTest (E2E)

**Files:**
- Create: `src/test/java/github/lms/lemuel/payout/application/PayoutFlowIntegrationTest.java`

PR #59의 RefundFlowIntegrationTest 패턴을 따라, mock-based app integration:
- Settlement, Seller, Payout, JournalEntry를 in-memory map으로 저장
- 실제 ExecutePayoutService + LedgerService + MockBankTransferAdapter wire-up
- 4 시나리오:
  1. happy path (DONE → 송금 → PAID + Ledger 분개)
  2. 멱등성 (같은 settlementId 재호출)
  3. Settlement PROCESSING 상태 → IllegalStateException
  4. Bank transfer 실패 시 Payout FAILED + Settlement DONE 유지

코드는 `RefundFlowIntegrationTest`를 템플릿으로 작성 (동일 패턴, mock-based store + 실제 서비스 wiring).

- [ ] **Step 1-3: 작성 + 통과 + commit**

```bash
./gradlew test --tests "github.lms.lemuel.payout.application.PayoutFlowIntegrationTest"
./gradlew test  # 전체 회귀
git add src/test/java/github/lms/lemuel/payout/application/PayoutFlowIntegrationTest.java
git commit -m "test(payout): end-to-end integration test for payout flow"
```

---

### Task 4.3: ADR-002 작성

**Files:**
- Create: `docs/adr/ADR-002-payout-as-separate-aggregate.md`

```markdown
# ADR-002: 송금을 Settlement에서 분리한 Payout aggregate로 처리

## Status
Accepted (2026-04-27)

## Context
정산이 DONE 상태가 된 후 셀러에게 실제 송금이 필요함. 면접 단골 질문:
> "Settlement DONE 다음에 돈은 어떻게 보내요?"

이전 상태:
- `Settlement.DONE`이 종료 상태였으나, 실제 송금 코드 0건 — \"DONE은 status만 바꿀 뿐 돈을 안 보냄\" (Devil's Advocate 지적)
- `BANK_TRANSFER_PENDING` Account만 V35에 미리 만들어져 있고 사용처 0
- 셀러 도메인에 `bankName/bankAccountNumber/bankAccountHolder` 필드는 있으나 활용처 없음

## Decision
1. **Payout aggregate 신설** — Settlement과 분리된 송금 도메인 (PENDING/SUCCEEDED/FAILED 상태머신)
2. **Settlement.PAID 상태 추가** — DONE → PAID 전이로 송금 완료 시점을 status에 표현
3. **BankTransferPort Outbound 추상화** — 현재 MockBankTransferAdapter, 추후 토스페이먼츠 펌뱅킹 등 실 어댑터로 교체 가능
4. **멱등성 이중 보장** — DB UNIQUE(settlement_id) + 도메인 가드 (LoadPayoutPort.findBySettlementId 선체크)
5. **Ledger 분개** — `LedgerService.recordPayoutExecuted`로 표준 송금 분개:
   - SELLER_PAYABLE Dr (셀러 미지급금 청산) / PLATFORM_CASH Cr (플랫폼 현금 유출)
   - `payoutId` idempotencyKey
6. **PayoutScheduler 04시 cron** — DONE 상태 정산을 일괄 송금

## Consequences

### Positive
- "DONE 후 돈은?" 면접 질문에 명확한 답변 가능
- Payout 분리로 도메인 책임 명확 (Settlement: 정산금 산정, Payout: 송금 실행)
- BankTransferPort 추상화로 실 PG 교체 용이성 확보
- Ledger 분개로 송금 회계 흔적 (감사·정합성 검증 가능)

### Negative
- Settlement과 Payout 두 도메인 간 일관성 책임 (현 plan은 단일 트랜잭션 fail-fast로 처리)
- BankTransferPort가 동기 호출 — 실 펌뱅킹은 비동기 콜백이 일반적이므로 추후 webhook 처리 필요
- 송금 후 환불 발생 시 보상 송금/회수 로직 부재 (별도 plan)
- 셀러 정산 주기별 batch 합산 송금 미구현 (settlement 1건당 payout 1건)

### Out-of-Scope
- 실 펌뱅킹 PG 연동 (토스페이먼츠 등)
- 송금 webhook 처리 (비동기 결과 통지)
- 송금 자동 재시도 정책
- 송금 한도/수수료 처리
- 셀러 정산 주기별 batch 합산
- 환불에 의한 송금 취소/회수

## References
- Plan: `docs/superpowers/plans/2026-04-27-payout-bank-transfer.md`
- V38 마이그레이션: `src/main/resources/db/migration/V38__create_payouts_and_settlement_paid.sql`
- 핵심 코드: `payout/domain/Payout.java`, `payout/application/service/ExecutePayoutService.java`
```

- [ ] **Step: commit**

```bash
git add docs/adr/ADR-002-payout-as-separate-aggregate.md
git commit -m "docs(adr): payout as separate aggregate with bank transfer abstraction"
```

---

## Verification Checklist (전체 plan 완료 후)

- [ ] `./gradlew build` 성공
- [ ] 신규 테스트 모두 통과:
  - `PayoutTest` (~7)
  - `SettlementMarkPaidTest` (5)
  - `ExecutePayoutServiceTest` (4)
  - `PayoutFlowIntegrationTest` (4)
- [ ] 기존 테스트 회귀 0건
- [ ] Verification Anchor 시나리오 통과 (E2E)
- [ ] `git grep "settlement\.markPaid\|markPaid()" -- 'src/main/'` — ExecutePayoutService 1곳에서만 호출
- [ ] V38 마이그레이션 실행 시 기존 정산 데이터에 영향 없음
