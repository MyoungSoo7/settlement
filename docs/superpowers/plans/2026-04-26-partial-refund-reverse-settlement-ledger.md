# Partial Refund + Reverse Settlement + Ledger Posting Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 부분환불을 진짜로 구현하고, 정산 후 환불을 Adjustment 패턴(원본 immutable + adjustment 레코드)으로 처리하며, 환불 시 수수료 비례 환급을 Ledger 분개로 기록한다. 정산 도메인 1개 시나리오를 ledger 레벨까지 끝낸다.

**Architecture:** 헥사고날 + DDD. Refund와 SettlementAdjustment를 별도 도메인 엔티티로 신설(현재 0건). PaymentDomain은 누적 환불액 invariant(`refundedAmount ≤ amount`)를 도메인에서 강제. AdjustSettlementForRefundService는 Settlement를 mutate하지 않고 SettlementAdjustment를 INSERT한 뒤 LedgerService.recordRefundProcessed를 호출 (비례 수수료 역산 분개). 멱등성은 `(payment_id, idempotency_key)` UNIQUE 인덱스로 DB 레벨 + 도메인 레벨 이중 보장.

**Tech Stack:** Spring Boot 3.x / JPA / PostgreSQL / Flyway / JUnit 5 / Mockito. (resilience4j, kafka, ES 등은 본 plan 범위 외)

**Scope guardrails (이번 plan에 포함하지 않는 것):**
- VAT/원천징수 처리 (별도 plan)
- 영업일/공휴일 보정 (별도 plan)
- Toss API 멱등성 헤더 실 전달 (별도 plan — 본 plan은 우리 시스템 내부 멱등성만)
- SettlementStatus enum 11개 정리 (별도 plan)
- 송금 어댑터 (별도 plan)
- Spring Batch chunk-oriented 전환 (별도 plan)

**Verification anchor (전 chunk 통과 후 만족해야 할 시나리오):**
> 100,000원 결제 (수수료 3%, 셀러A) → CAPTURED → 정산 생성(commission=3,000, net=97,000) → DONE
> ↓
> 30,000원 부분환불 요청 (Idempotency-Key=K1) → Refund(id=1, amount=30,000, status=COMPLETED) INSERT
> ↓
> SettlementAdjustment(settlementId, refundId=1, amount=-30,000, status=PENDING) INSERT, **원 Settlement 변경 없음**
> ↓
> JournalEntry(REFUND_PROCESSED, refundId=1) 기록: SELLER_PAYABLE 차변 29,100 + PLATFORM_COMMISSION 차변 900 + PLATFORM_CASH 대변 30,000
> ↓
> 같은 Idempotency-Key=K1로 재요청 → 새 Refund INSERT 안 되고 기존 Refund 반환 (DB UNIQUE + 도메인 가드)
> ↓
> 추가 80,000원 환불 시도 → RefundExceedsPaymentException (누적 환불 110,000 > 결제액 100,000)

---

## Chunk 1: Refund Domain + Persistence

목표: `Refund` 도메인을 헥사고날 구조로 신설 (현재 0건). `refunds` 테이블(V4)은 이미 있으므로 마이그레이션은 불필요. 본 chunk 종료 시 `Refund`를 저장·조회 가능, 단위 테스트 통과.

### Task 1.1: `RefundStatus` enum + `Refund` 도메인 엔티티 (pure POJO + invariants)

**Files:**
- Create: `src/main/java/github/lms/lemuel/payment/domain/RefundStatus.java`
- Create: `src/main/java/github/lms/lemuel/payment/domain/Refund.java`
- Create: `src/test/java/github/lms/lemuel/payment/domain/RefundTest.java`

- [ ] **Step 1: failing test 작성**

`RefundTest.java`:
```java
package github.lms.lemuel.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class RefundTest {

    @Nested
    @DisplayName("Refund 생성")
    class Creation {

        @Test
        @DisplayName("정상: 양수 금액 + idempotencyKey 있으면 REQUESTED 상태로 생성된다")
        void create_ok() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", "고객 변심");

            assertThat(refund.getPaymentId()).isEqualTo(10L);
            assertThat(refund.getAmount()).isEqualByComparingTo("3000");
            assertThat(refund.getIdempotencyKey()).isEqualTo("K1");
            assertThat(refund.getReason()).isEqualTo("고객 변심");
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(refund.getRequestedAt()).isNotNull();
        }

        @Test
        @DisplayName("에러: 0 또는 음수 금액은 거부")
        void create_amount_zero_or_negative() {
            assertThatThrownBy(() -> Refund.request(10L, BigDecimal.ZERO, "K1", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("greater than zero");
            assertThatThrownBy(() -> Refund.request(10L, new BigDecimal("-1"), "K1", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("에러: idempotencyKey 누락")
        void create_idempotency_required() {
            assertThatThrownBy(() -> Refund.request(10L, new BigDecimal("3000"), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Refund.request(10L, new BigDecimal("3000"), "  ", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("상태 머신")
    class StateMachine {

        @Test
        @DisplayName("REQUESTED → COMPLETED")
        void complete_from_requested() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", null);
            refund.markCompleted();
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(refund.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("이미 COMPLETED 상태에서 markCompleted 재호출은 거부")
        void complete_twice_rejected() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", null);
            refund.markCompleted();
            assertThatThrownBy(refund::markCompleted)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("REQUESTED → FAILED")
        void fail_from_requested() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", null);
            refund.markFailed("PG timeout");
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        }
    }
}
```

- [ ] **Step 2: 테스트 실행 → FAIL 확인**

```bash
./gradlew test --tests "github.lms.lemuel.payment.domain.RefundTest"
```
Expected: 컴파일 에러 (`Refund` 클래스 없음).

- [ ] **Step 3: `RefundStatus` enum 작성**

`RefundStatus.java`:
```java
package github.lms.lemuel.payment.domain;

public enum RefundStatus {
    REQUESTED,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 4: `Refund` 도메인 작성**

`Refund.java`:
```java
package github.lms.lemuel.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Refund {

    private Long id;
    private Long paymentId;
    private BigDecimal amount;
    private RefundStatus status;
    private String reason;
    private String idempotencyKey;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Refund() {}

    public static Refund request(Long paymentId, BigDecimal amount,
                                  String idempotencyKey, String reason) {
        if (paymentId == null || paymentId <= 0) {
            throw new IllegalArgumentException("paymentId must be positive");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        Refund r = new Refund();
        r.paymentId = paymentId;
        r.amount = amount;
        r.idempotencyKey = idempotencyKey;
        r.reason = reason;
        r.status = RefundStatus.REQUESTED;
        r.requestedAt = LocalDateTime.now();
        r.createdAt = r.requestedAt;
        r.updatedAt = r.requestedAt;
        return r;
    }

    public void markCompleted() {
        if (this.status != RefundStatus.REQUESTED) {
            throw new IllegalStateException(
                "Cannot mark COMPLETED. Current status: " + this.status);
        }
        this.status = RefundStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = this.completedAt;
    }

    public void markFailed(String reason) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new IllegalStateException(
                "Cannot mark FAILED. Current status: " + this.status);
        }
        this.status = RefundStatus.FAILED;
        this.reason = (this.reason == null ? "" : this.reason + " | ") + "FAIL: " + reason;
        this.updatedAt = LocalDateTime.now();
    }

    // Reconstitution constructor (persistence layer only)
    public Refund(Long id, Long paymentId, BigDecimal amount, RefundStatus status,
                  String reason, String idempotencyKey,
                  LocalDateTime requestedAt, LocalDateTime completedAt,
                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
    public RefundStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** persistence가 INSERT 후 채운 PK를 주입할 때만 사용 */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id already assigned");
        }
        this.id = id;
    }
}
```

- [ ] **Step 5: 테스트 실행 → PASS 확인**

```bash
./gradlew test --tests "github.lms.lemuel.payment.domain.RefundTest"
```
Expected: 7 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/github/lms/lemuel/payment/domain/RefundStatus.java \
        src/main/java/github/lms/lemuel/payment/domain/Refund.java \
        src/test/java/github/lms/lemuel/payment/domain/RefundTest.java
git commit -m "feat(payment): add Refund aggregate with state machine and invariants"
```

---

### Task 1.2: Refund JPA 엔티티 + 매퍼 + Spring Data 리포지토리

**Files:**
- Create: `src/main/java/github/lms/lemuel/payment/adapter/out/persistence/RefundJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/payment/adapter/out/persistence/SpringDataRefundJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/payment/adapter/out/persistence/RefundMapper.java` (또는 기존 `PaymentMapper`에 추가)

> 참고: V4 마이그레이션의 `refunds` 테이블 컬럼 — `id, payment_id, amount, status, reason, idempotency_key, requested_at, completed_at, created_at, updated_at` + UNIQUE(`payment_id, idempotency_key`).

- [ ] **Step 1: `RefundJpaEntity` 작성**

`RefundJpaEntity.java`:
```java
package github.lms.lemuel.payment.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refunds",
       uniqueConstraints = @UniqueConstraint(
           name = "idx_refunds_payment_idempotency",
           columnNames = {"payment_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RefundJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

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

- [ ] **Step 2: `SpringDataRefundJpaRepository` 작성**

`SpringDataRefundJpaRepository.java`:
```java
package github.lms.lemuel.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataRefundJpaRepository extends JpaRepository<RefundJpaEntity, Long> {
    Optional<RefundJpaEntity> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);
}
```

- [ ] **Step 3: `RefundMapper` 작성**

`RefundMapper.java`:
```java
package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.RefundStatus;

public final class RefundMapper {
    private RefundMapper() {}

    public static RefundJpaEntity toJpa(Refund domain) {
        return RefundJpaEntity.builder()
                .id(domain.getId())
                .paymentId(domain.getPaymentId())
                .amount(domain.getAmount())
                .status(domain.getStatus().name())
                .reason(domain.getReason())
                .idempotencyKey(domain.getIdempotencyKey())
                .requestedAt(domain.getRequestedAt())
                .completedAt(domain.getCompletedAt())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public static Refund toDomain(RefundJpaEntity jpa) {
        return new Refund(
                jpa.getId(),
                jpa.getPaymentId(),
                jpa.getAmount(),
                RefundStatus.valueOf(jpa.getStatus()),
                jpa.getReason(),
                jpa.getIdempotencyKey(),
                jpa.getRequestedAt(),
                jpa.getCompletedAt(),
                jpa.getCreatedAt(),
                jpa.getUpdatedAt()
        );
    }
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/github/lms/lemuel/payment/adapter/out/persistence/RefundJpaEntity.java \
        src/main/java/github/lms/lemuel/payment/adapter/out/persistence/SpringDataRefundJpaRepository.java \
        src/main/java/github/lms/lemuel/payment/adapter/out/persistence/RefundMapper.java
git commit -m "feat(payment): add Refund JPA entity, repository, and mapper"
```

---

### Task 1.3: `SaveRefundPort` + `LoadRefundPort` + `RefundPersistenceAdapter`

**Files:**
- Create: `src/main/java/github/lms/lemuel/payment/application/port/out/SaveRefundPort.java`
- Create: `src/main/java/github/lms/lemuel/payment/application/port/out/LoadRefundPort.java`
- Create: `src/main/java/github/lms/lemuel/payment/adapter/out/persistence/RefundPersistenceAdapter.java`

- [ ] **Step 1: 포트 정의**

`SaveRefundPort.java`:
```java
package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.Refund;

public interface SaveRefundPort {
    Refund save(Refund refund);
}
```

`LoadRefundPort.java`:
```java
package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.Refund;

import java.util.Optional;

public interface LoadRefundPort {
    Optional<Refund> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);
}
```

- [ ] **Step 2: 어댑터 구현**

`RefundPersistenceAdapter.java`:
```java
package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RefundPersistenceAdapter implements SaveRefundPort, LoadRefundPort {

    private final SpringDataRefundJpaRepository repository;

    @Override
    public Refund save(Refund refund) {
        RefundJpaEntity saved = repository.save(RefundMapper.toJpa(refund));
        if (refund.getId() == null && saved.getId() != null) {
            refund.assignId(saved.getId());
        }
        return RefundMapper.toDomain(saved);
    }

    @Override
    public Optional<Refund> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey) {
        return repository.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey)
                .map(RefundMapper::toDomain);
    }
}
```

- [ ] **Step 3: 컴파일**

```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/github/lms/lemuel/payment/application/port/out/SaveRefundPort.java \
        src/main/java/github/lms/lemuel/payment/application/port/out/LoadRefundPort.java \
        src/main/java/github/lms/lemuel/payment/adapter/out/persistence/RefundPersistenceAdapter.java
git commit -m "feat(payment): add Refund persistence ports and adapter"
```

---

## Chunk 2: Partial Refund Use Case

목표: PaymentDomain에 누적 환불 invariant + RefundExceedsPaymentException 추가. RefundPaymentUseCase가 idempotencyKey/refundAmount를 실 사용. RefundController.partial이 진짜 부분환불.

### Task 2.1: `PaymentDomain.requestRefund(amount)` + invariant

**Files:**
- Modify: `src/main/java/github/lms/lemuel/payment/domain/PaymentDomain.java`
- Create: `src/test/java/github/lms/lemuel/payment/domain/PaymentDomainRefundTest.java`

- [ ] **Step 1: failing test 작성**

`PaymentDomainRefundTest.java`:
```java
package github.lms.lemuel.payment.domain;

import github.lms.lemuel.common.exception.RefundExceedsPaymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class PaymentDomainRefundTest {

    private PaymentDomain captured(BigDecimal amount, BigDecimal alreadyRefunded) {
        return new PaymentDomain(
                1L, 100L, amount, alreadyRefunded,
                PaymentStatus.CAPTURED, "CARD", "PG-X",
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("부분환불 정상: 누적 환불액 증가, 상태는 CAPTURED 유지")
    void partial_refund_keeps_captured() {
        PaymentDomain p = captured(new BigDecimal("100000"), BigDecimal.ZERO);

        p.requestRefund(new BigDecimal("30000"));

        assertThat(p.getRefundedAmount()).isEqualByComparingTo("30000");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(p.getRefundableAmount()).isEqualByComparingTo("70000");
    }

    @Test
    @DisplayName("환불 누적이 결제액과 같아지면 REFUNDED 상태로 전이")
    void cumulative_refund_equal_to_amount_transitions_to_refunded() {
        PaymentDomain p = captured(new BigDecimal("100000"), new BigDecimal("70000"));

        p.requestRefund(new BigDecimal("30000"));

        assertThat(p.getRefundedAmount()).isEqualByComparingTo("100000");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.isFullyRefunded()).isTrue();
    }

    @Test
    @DisplayName("초과 환불은 RefundExceedsPaymentException")
    void over_refund_rejected() {
        PaymentDomain p = captured(new BigDecimal("100000"), new BigDecimal("70000"));

        assertThatThrownBy(() -> p.requestRefund(new BigDecimal("30001")))
                .isInstanceOf(RefundExceedsPaymentException.class)
                .hasMessageContaining("100000");
    }

    @Test
    @DisplayName("0 또는 음수 환불 금액 거부")
    void non_positive_refund_rejected() {
        PaymentDomain p = captured(new BigDecimal("100000"), BigDecimal.ZERO);

        assertThatThrownBy(() -> p.requestRefund(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.requestRefund(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CAPTURED가 아닌 결제는 환불 불가")
    void cannot_refund_non_captured() {
        PaymentDomain p = new PaymentDomain(
                1L, 100L, new BigDecimal("100000"), BigDecimal.ZERO,
                PaymentStatus.AUTHORIZED, "CARD", "PG-X",
                null, LocalDateTime.now(), LocalDateTime.now()
        );

        assertThatThrownBy(() -> p.requestRefund(new BigDecimal("10000")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 REFUNDED 상태에서는 환불 불가")
    void cannot_refund_already_refunded() {
        PaymentDomain p = new PaymentDomain(
                1L, 100L, new BigDecimal("100000"), new BigDecimal("100000"),
                PaymentStatus.REFUNDED, "CARD", "PG-X",
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now()
        );

        assertThatThrownBy(() -> p.requestRefund(new BigDecimal("1")))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: 테스트 → FAIL 확인**

```bash
./gradlew test --tests "github.lms.lemuel.payment.domain.PaymentDomainRefundTest"
```
Expected: 컴파일 에러 (`requestRefund` 없음).

- [ ] **Step 3: `PaymentDomain.requestRefund(amount)` 추가**

`PaymentDomain.java` line 73 부근의 `refund()`를 다음으로 교체:

```java
    /**
     * 부분 또는 전체 환불 요청.
     * 누적 환불액이 결제액과 같아지면 status를 REFUNDED로 전이.
     */
    public void requestRefund(java.math.BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }
        if (this.status != PaymentStatus.CAPTURED) {
            throw new IllegalStateException(
                "Payment must be in CAPTURED status to refund. current=" + this.status);
        }
        java.math.BigDecimal newRefunded = this.refundedAmount.add(refundAmount);
        if (newRefunded.compareTo(this.amount) > 0) {
            throw new github.lms.lemuel.common.exception.RefundExceedsPaymentException(
                String.format("Refund exceeds payment. paymentAmount=%s, alreadyRefunded=%s, requested=%s",
                    this.amount, this.refundedAmount, refundAmount));
        }
        this.refundedAmount = newRefunded;
        if (this.refundedAmount.compareTo(this.amount) == 0) {
            this.status = PaymentStatus.REFUNDED;
        }
        this.updatedAt = java.time.LocalDateTime.now();
    }

    /**
     * @deprecated requestRefund(amount) 사용. 전액 환불은 requestRefund(getRefundableAmount()).
     */
    @Deprecated
    public void refund() {
        requestRefund(getRefundableAmount());
    }
```

> 주의: 기존 `refund()`를 deprecated wrapper로 유지해 다른 호출자가 깨지지 않게 한다. Chunk 4에서 모든 호출자를 `requestRefund(amount)`로 교체 후 deprecated 어노테이션 제거 또는 메서드 자체 제거 검토.

- [ ] **Step 4: 테스트 → PASS 확인**

```bash
./gradlew test --tests "github.lms.lemuel.payment.domain.PaymentDomainRefundTest"
```
Expected: 6 tests passed.

전체 테스트도 함께 통과해야 함:
```bash
./gradlew test
```
Expected: 기존 테스트 모두 통과 (refund() wrapper 덕분에).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/github/lms/lemuel/payment/domain/PaymentDomain.java \
        src/test/java/github/lms/lemuel/payment/domain/PaymentDomainRefundTest.java
git commit -m "feat(payment): PaymentDomain.requestRefund with cumulative invariant + RefundExceedsPaymentException"
```

---

### Task 2.2: `RefundCommand` + `RefundPaymentPort` 시그니처 변경

**Files:**
- Create: `src/main/java/github/lms/lemuel/payment/application/port/in/RefundCommand.java`
- Modify: `src/main/java/github/lms/lemuel/payment/application/port/in/RefundPaymentPort.java`

> 주의: `RefundPaymentUseCase` 구현체와 컨트롤러 호출부가 깨질 것. Task 2.3, 2.4에서 동시에 수정.

- [ ] **Step 1: `RefundCommand` 작성**

`RefundCommand.java`:
```java
package github.lms.lemuel.payment.application.port.in;

import java.math.BigDecimal;

public record RefundCommand(Long paymentId, BigDecimal refundAmount,
                             String idempotencyKey, String reason) {

    public RefundCommand {
        if (paymentId == null) throw new IllegalArgumentException("paymentId required");
        if (refundAmount == null || refundAmount.signum() <= 0)
            throw new IllegalArgumentException("refundAmount must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new IllegalArgumentException("idempotencyKey required");
    }
}
```

- [ ] **Step 2: `RefundPaymentPort` 시그니처 변경**

`RefundPaymentPort.java` 전체 교체:
```java
package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.Refund;

public interface RefundPaymentPort {
    /**
     * 부분 또는 전체 환불 처리.
     * 동일 (paymentId, idempotencyKey)로 재호출 시 기존 Refund 반환 (멱등).
     */
    Refund refund(RefundCommand command);
}
```

- [ ] **Step 3: 컴파일 확인 (실패 예상)**

```bash
./gradlew compileJava
```
Expected: `RefundPaymentUseCase` (override `refundPayment` 시그니처 불일치) 와 `RefundController` (호출부) 컴파일 에러. Task 2.3-2.4에서 수정.

- [ ] **Step 4: 임시 commit (혹은 Task 2.3-2.4와 묶어서 commit)**

본 step은 단독 commit하지 않음. Task 2.3, 2.4 완료 후 함께 commit.

---

### Task 2.3: `RefundPaymentUseCase` 재작성 (멱등성 + 부분환불 + Refund 영속화)

**Files:**
- Modify: `src/main/java/github/lms/lemuel/payment/application/RefundPaymentUseCase.java`
- Modify: `src/main/java/github/lms/lemuel/payment/application/port/out/PgClientPort.java` (필요 시)
- Create: `src/test/java/github/lms/lemuel/payment/application/RefundPaymentUseCaseTest.java`

> 참고: 정산 어댑터 호출은 Chunk 3에서 시그니처를 함께 바꿈. 본 Task에서는 일단 paymentId + refundAmount + refundId + sellerId를 전달할 새 시그니처를 가정하고 작성. Chunk 3 끝에 wire-up 완성.

- [ ] **Step 1: failing test 작성**

`RefundPaymentUseCaseTest.java`:
```java
package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.RefundCommand;
import github.lms.lemuel.payment.application.port.out.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.RefundStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundPaymentUseCaseTest {

    @Mock private LoadPaymentPort loadPaymentPort;
    @Mock private SavePaymentPort savePaymentPort;
    @Mock private LoadRefundPort loadRefundPort;
    @Mock private SaveRefundPort saveRefundPort;
    @Mock private PgClientPort pgClientPort;
    @Mock private UpdateOrderStatusPort updateOrderStatusPort;
    @Mock private PublishEventPort publishEventPort;
    @Mock private AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase;

    @InjectMocks private RefundPaymentUseCase useCase;

    private PaymentDomain captured() {
        return new PaymentDomain(
                10L, 100L, new BigDecimal("100000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "PG-X",
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("부분환불: PG 호출, Refund INSERT, Payment 업데이트, 정산 조정 호출")
    void partial_refund_happy_path() {
        given(loadPaymentPort.loadById(10L)).willReturn(Optional.of(captured()));
        given(loadRefundPort.findByPaymentIdAndIdempotencyKey(10L, "K1"))
                .willReturn(Optional.empty());
        given(saveRefundPort.save(any(Refund.class))).willAnswer(inv -> {
            Refund r = inv.getArgument(0);
            r.assignId(99L);
            return r;
        });
        given(savePaymentPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        Refund result = useCase.refund(new RefundCommand(10L, new BigDecimal("30000"), "K1", "변심"));

        assertThat(result.getId()).isEqualTo(99L);
        assertThat(result.getAmount()).isEqualByComparingTo("30000");
        assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);

        verify(pgClientPort).refund("PG-X", new BigDecimal("30000"));
        verify(savePaymentPort).save(argThat(p -> p.getRefundedAmount().compareTo(new BigDecimal("30000")) == 0));
        verify(adjustSettlementForRefundUseCase)
                .adjustSettlementForRefund(eq(99L), eq(10L), eq(new BigDecimal("30000")));
    }

    @Test
    @DisplayName("멱등성: 동일 (paymentId, idempotencyKey)면 기존 Refund 반환, PG 재호출 없음")
    void idempotent_replay_returns_existing() {
        Refund existing = mock(Refund.class);
        given(existing.getStatus()).willReturn(RefundStatus.COMPLETED);
        given(loadRefundPort.findByPaymentIdAndIdempotencyKey(10L, "K1"))
                .willReturn(Optional.of(existing));

        Refund result = useCase.refund(new RefundCommand(10L, new BigDecimal("30000"), "K1", null));

        assertThat(result).isSameAs(existing);
        verify(pgClientPort, never()).refund(any(), any());
        verify(savePaymentPort, never()).save(any());
        verify(saveRefundPort, never()).save(any());
        verify(adjustSettlementForRefundUseCase, never())
                .adjustSettlementForRefund(any(), any(), any());
    }

    @Test
    @DisplayName("결제 없음: PaymentNotFoundException")
    void payment_not_found() {
        given(loadRefundPort.findByPaymentIdAndIdempotencyKey(10L, "K1"))
                .willReturn(Optional.empty());
        given(loadPaymentPort.loadById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.refund(new RefundCommand(10L, new BigDecimal("30000"), "K1", null)))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
```

- [ ] **Step 2: 테스트 → FAIL 확인**

```bash
./gradlew test --tests "github.lms.lemuel.payment.application.RefundPaymentUseCaseTest"
```
Expected: 컴파일 실패 (RefundPaymentUseCase 시그니처 불일치 + 새 의존성).

- [ ] **Step 3: `AdjustSettlementForRefundUseCase` 시그니처 임시 확장**

본 Task에서는 컴파일을 위해 `adjustSettlementForRefund(refundId, paymentId, refundAmount)` 형태로 호출. Chunk 3에서 실제 시그니처를 이 형태로 정의. 일단 `AdjustSettlementForRefundUseCase.java`에 메서드 오버로드 추가:

```java
package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.Settlement;

import java.math.BigDecimal;

public interface AdjustSettlementForRefundUseCase {

    /** @deprecated Chunk 3에서 제거. refundId 포함 버전 사용. */
    @Deprecated
    Settlement adjustSettlementForRefund(Long paymentId, BigDecimal refundAmount);

    /** 환불별 1건 SettlementAdjustment를 INSERT하고 Ledger 분개를 기록한다 (Chunk 3에서 구현). */
    void adjustSettlementForRefund(Long refundId, Long paymentId, BigDecimal refundAmount);
}
```

기존 구현 `AdjustSettlementForRefundService`에는 새 메서드의 임시 default 구현 추가:
```java
    @Override
    public void adjustSettlementForRefund(Long refundId, Long paymentId, BigDecimal refundAmount) {
        // Chunk 3에서 재작성
        adjustSettlementForRefund(paymentId, refundAmount);
    }
```

- [ ] **Step 4: `RefundPaymentUseCase` 재작성**

`RefundPaymentUseCase.java` 전체 교체:
```java
package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.RefundCommand;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.application.port.out.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RefundPaymentUseCase implements RefundPaymentPort {

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final LoadRefundPort loadRefundPort;
    private final SaveRefundPort saveRefundPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase;

    @Override
    public Refund refund(RefundCommand command) {
        // 1. 멱등성 체크
        var existing = loadRefundPort.findByPaymentIdAndIdempotencyKey(
                command.paymentId(), command.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent replay: returning existing refund. paymentId={}, key={}",
                    command.paymentId(), command.idempotencyKey());
            return existing.get();
        }

        // 2. 결제 로드 + 도메인 invariant 검증
        PaymentDomain payment = loadPaymentPort.loadById(command.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(command.paymentId()));

        payment.requestRefund(command.refundAmount()); // 누적 검증 + 상태 전이

        // 3. PG 호출 (실패 시 트랜잭션 롤백)
        pgClientPort.refund(payment.getPgTransactionId(), command.refundAmount());

        // 4. Payment 업데이트
        PaymentDomain savedPayment = savePaymentPort.save(payment);

        // 5. Refund INSERT
        Refund refund = Refund.request(
                command.paymentId(), command.refundAmount(),
                command.idempotencyKey(), command.reason());
        refund.markCompleted();
        Refund savedRefund = saveRefundPort.save(refund);

        // 6. 전액 환불 시 주문 상태 동기화
        if (savedPayment.isFullyRefunded()) {
            updateOrderStatusPort.updateOrderStatus(savedPayment.getOrderId(), "REFUNDED");
        }

        // 7. 이벤트
        publishEventPort.publishPaymentRefunded(savedPayment.getId(), savedPayment.getOrderId());

        // 8. 정산 조정 (Adjustment + Ledger) — Chunk 3에서 실제 구현 완성
        adjustSettlementForRefundUseCase.adjustSettlementForRefund(
                savedRefund.getId(), savedPayment.getId(), command.refundAmount());

        log.info("Refund completed. refundId={}, paymentId={}, amount={}",
                savedRefund.getId(), savedPayment.getId(), command.refundAmount());

        return savedRefund;
    }
}
```

> 주의: 기존 RefundPaymentUseCase의 `try/catch (Exception e) { ...정산 조정 실패 시에도... }` catch-and-log 패턴은 **제거**. 정산 조정은 같은 트랜잭션에서 실패하면 환불 전체가 롤백되어야 한다 (회계 정합성). PG 호출이 이미 성공했다면 보상 트랜잭션이 별도 필요한데, 이는 Phase 후속 plan(Outbox)에서 다룬다 — 현 단계는 fail-fast.

- [ ] **Step 5: 테스트 → PASS 확인**

```bash
./gradlew test --tests "github.lms.lemuel.payment.application.RefundPaymentUseCaseTest"
```
Expected: 3 tests passed.

전체 테스트:
```bash
./gradlew test
```
Expected: 모두 통과 (Adjust...UseCase 임시 default 구현이 기존 동작 보존).

- [ ] **Step 6: Commit (Task 2.2 + 2.3 묶어서)**

```bash
git add src/main/java/github/lms/lemuel/payment/application/port/in/RefundCommand.java \
        src/main/java/github/lms/lemuel/payment/application/port/in/RefundPaymentPort.java \
        src/main/java/github/lms/lemuel/payment/application/RefundPaymentUseCase.java \
        src/main/java/github/lms/lemuel/settlement/application/port/in/AdjustSettlementForRefundUseCase.java \
        src/main/java/github/lms/lemuel/settlement/application/service/AdjustSettlementForRefundService.java \
        src/test/java/github/lms/lemuel/payment/application/RefundPaymentUseCaseTest.java
git commit -m "feat(payment): partial refund with idempotency, RefundCommand, and Refund persistence"
```

---

### Task 2.4: `RefundController.partial` 엔드포인트가 `refundAmount` 실 사용 + 응답 DTO

**Files:**
- Modify: `src/main/java/github/lms/lemuel/payment/adapter/in/api/RefundController.java`
- Create (option): `src/main/java/github/lms/lemuel/payment/adapter/in/dto/RefundResponse.java`

- [ ] **Step 1: `RefundResponse` DTO 작성**

`RefundResponse.java`:
```java
package github.lms.lemuel.payment.adapter.in.dto;

import github.lms.lemuel.payment.domain.Refund;

import java.math.BigDecimal;

public record RefundResponse(Long refundId, Long paymentId, BigDecimal amount,
                              String status, String idempotencyKey) {
    public static RefundResponse from(Refund r) {
        return new RefundResponse(r.getId(), r.getPaymentId(), r.getAmount(),
                r.getStatus().name(), r.getIdempotencyKey());
    }
}
```

- [ ] **Step 2: `RefundController` 재작성**

`RefundController.java` 전체 교체:
```java
package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.exception.MissingIdempotencyKeyException;
import github.lms.lemuel.payment.adapter.in.dto.RefundResponse;
import github.lms.lemuel.payment.application.port.in.RefundCommand;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundPaymentPort refundPaymentPort;

    /**
     * 부분 환불 (또는 전체 환불 with refundAmount = paymentAmount).
     * POST /api/refunds/{paymentId}?refundAmount=...
     */
    @PostMapping("/{paymentId}")
    public ResponseEntity<RefundResponse> createRefund(
            @PathVariable Long paymentId,
            @RequestParam BigDecimal refundAmount,
            @RequestParam(required = false) String reason,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Refund refund = refundPaymentPort.refund(
                new RefundCommand(paymentId, refundAmount, idempotencyKey, reason));
        return ResponseEntity.ok(RefundResponse.from(refund));
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key 헤더가 필요합니다.");
        }
    }
}
```

> 주의: 기존 `/full/{paymentId}`, `/partial/{paymentId}` 엔드포인트는 **삭제**. 단일 엔드포인트로 통합 (refundAmount만 다름). 프론트가 두 엔드포인트를 호출하고 있다면 별도 마이그레이션 필요 — 현 시점에 호출하는 프론트 없음으로 가정 (포트폴리오 단계).

- [ ] **Step 3: 컴파일 + 전체 테스트**

```bash
./gradlew test
```
Expected: 모두 통과.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/github/lms/lemuel/payment/adapter/in/api/RefundController.java \
        src/main/java/github/lms/lemuel/payment/adapter/in/dto/RefundResponse.java
git commit -m "feat(payment): RefundController unified endpoint with refundAmount + idempotencyKey"
```

---

## Chunk 3: Reverse Settlement (SettlementAdjustment + Ledger Posting)

목표: `SettlementAdjustment` 도메인 신설. `AdjustSettlementForRefundService`가 원 Settlement를 mutate하지 않고 Adjustment INSERT + Ledger `recordRefundProcessed` 호출. 수수료 비례 환급(commission reversal) 계산 정확.

### Task 3.1: `SettlementAdjustment` 도메인 + 상태 enum

**Files:**
- Create: `src/main/java/github/lms/lemuel/settlement/domain/SettlementAdjustmentStatus.java`
- Create: `src/main/java/github/lms/lemuel/settlement/domain/SettlementAdjustment.java`
- Create: `src/test/java/github/lms/lemuel/settlement/domain/SettlementAdjustmentTest.java`

> V4의 `settlement_adjustments` 테이블 컬럼: `id, settlement_id, refund_id, amount(<0), status, adjustment_date, confirmed_at, created_at, updated_at`. Domain은 `amount`를 양수로 다루고 JPA 매퍼에서 음수로 변환.

- [ ] **Step 1: failing test**

`SettlementAdjustmentTest.java`:
```java
package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class SettlementAdjustmentTest {

    @Test
    @DisplayName("정상 생성: status=PENDING, amount는 양수로 보관")
    void create_ok() {
        SettlementAdjustment adj = SettlementAdjustment.forRefund(
                100L, 200L, new BigDecimal("30000"), LocalDate.of(2026, 4, 26));

        assertThat(adj.getSettlementId()).isEqualTo(100L);
        assertThat(adj.getRefundId()).isEqualTo(200L);
        assertThat(adj.getAmount()).isEqualByComparingTo("30000");
        assertThat(adj.getStatus()).isEqualTo(SettlementAdjustmentStatus.PENDING);
        assertThat(adj.getAdjustmentDate()).isEqualTo(LocalDate.of(2026, 4, 26));
    }

    @Test
    @DisplayName("amount 0/음수 거부")
    void amount_must_be_positive() {
        assertThatThrownBy(() -> SettlementAdjustment.forRefund(
                100L, 200L, BigDecimal.ZERO, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementAdjustment.forRefund(
                100L, 200L, new BigDecimal("-1"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PENDING → CONFIRMED")
    void confirm_from_pending() {
        SettlementAdjustment adj = SettlementAdjustment.forRefund(
                100L, 200L, new BigDecimal("30000"), LocalDate.now());
        adj.confirm();
        assertThat(adj.getStatus()).isEqualTo(SettlementAdjustmentStatus.CONFIRMED);
        assertThat(adj.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 CONFIRMED 상태에서 재호출 거부")
    void confirm_twice_rejected() {
        SettlementAdjustment adj = SettlementAdjustment.forRefund(
                100L, 200L, new BigDecimal("30000"), LocalDate.now());
        adj.confirm();
        assertThatThrownBy(adj::confirm).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: 테스트 → FAIL 확인**

```bash
./gradlew test --tests "github.lms.lemuel.settlement.domain.SettlementAdjustmentTest"
```
Expected: 컴파일 에러.

- [ ] **Step 3: 도메인 작성**

`SettlementAdjustmentStatus.java`:
```java
package github.lms.lemuel.settlement.domain;

public enum SettlementAdjustmentStatus { PENDING, CONFIRMED, CANCELED }
```

`SettlementAdjustment.java`:
```java
package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class SettlementAdjustment {

    private Long id;
    private Long settlementId;
    private Long refundId;
    private BigDecimal amount;            // 양수로 보관 (DB는 음수로 저장)
    private SettlementAdjustmentStatus status;
    private LocalDate adjustmentDate;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private SettlementAdjustment() {}

    public static SettlementAdjustment forRefund(Long settlementId, Long refundId,
                                                  BigDecimal amount, LocalDate adjustmentDate) {
        if (settlementId == null || refundId == null || adjustmentDate == null) {
            throw new IllegalArgumentException("settlementId/refundId/adjustmentDate required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        SettlementAdjustment adj = new SettlementAdjustment();
        adj.settlementId = settlementId;
        adj.refundId = refundId;
        adj.amount = amount;
        adj.status = SettlementAdjustmentStatus.PENDING;
        adj.adjustmentDate = adjustmentDate;
        adj.createdAt = LocalDateTime.now();
        adj.updatedAt = adj.createdAt;
        return adj;
    }

    public void confirm() {
        if (this.status != SettlementAdjustmentStatus.PENDING) {
            throw new IllegalStateException("Cannot confirm. status=" + this.status);
        }
        this.status = SettlementAdjustmentStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
        this.updatedAt = this.confirmedAt;
    }

    // Reconstitution
    public SettlementAdjustment(Long id, Long settlementId, Long refundId,
                                 BigDecimal amount, SettlementAdjustmentStatus status,
                                 LocalDate adjustmentDate, LocalDateTime confirmedAt,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.refundId = refundId;
        this.amount = amount;
        this.status = status;
        this.adjustmentDate = adjustmentDate;
        this.confirmedAt = confirmedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id already assigned");
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public Long getRefundId() { return refundId; }
    public BigDecimal getAmount() { return amount; }
    public SettlementAdjustmentStatus getStatus() { return status; }
    public LocalDate getAdjustmentDate() { return adjustmentDate; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: 테스트 PASS**

```bash
./gradlew test --tests "github.lms.lemuel.settlement.domain.SettlementAdjustmentTest"
```
Expected: 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/domain/SettlementAdjustmentStatus.java \
        src/main/java/github/lms/lemuel/settlement/domain/SettlementAdjustment.java \
        src/test/java/github/lms/lemuel/settlement/domain/SettlementAdjustmentTest.java
git commit -m "feat(settlement): add SettlementAdjustment aggregate (immutable adjustment pattern)"
```

---

### Task 3.2: SettlementAdjustment JPA 영속화

**Files:**
- Create: `src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SpringDataSettlementAdjustmentJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentMapper.java`
- Create: `src/main/java/github/lms/lemuel/settlement/application/port/out/SaveSettlementAdjustmentPort.java`
- Create: `src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentPersistenceAdapter.java`

- [ ] **Step 1: JPA 엔티티**

`SettlementAdjustmentJpaEntity.java`:
```java
package github.lms.lemuel.settlement.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_adjustments",
       uniqueConstraints = @UniqueConstraint(
           name = "idx_adjustments_refund_id_unique",
           columnNames = "refund_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SettlementAdjustmentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "refund_id", nullable = false)
    private Long refundId;

    /** DB constraint: amount < 0. 도메인은 양수, 매퍼에서 부호 변환. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "adjustment_date", nullable = false)
    private LocalDate adjustmentDate;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 리포지토리 + 매퍼**

`SpringDataSettlementAdjustmentJpaRepository.java`:
```java
package github.lms.lemuel.settlement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSettlementAdjustmentJpaRepository
        extends JpaRepository<SettlementAdjustmentJpaEntity, Long> {
    boolean existsByRefundId(Long refundId);
}
```

`SettlementAdjustmentMapper.java`:
```java
package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementAdjustmentStatus;

public final class SettlementAdjustmentMapper {
    private SettlementAdjustmentMapper() {}

    public static SettlementAdjustmentJpaEntity toJpa(SettlementAdjustment d) {
        return SettlementAdjustmentJpaEntity.builder()
                .id(d.getId())
                .settlementId(d.getSettlementId())
                .refundId(d.getRefundId())
                .amount(d.getAmount().negate())   // DB는 음수
                .status(d.getStatus().name())
                .adjustmentDate(d.getAdjustmentDate())
                .confirmedAt(d.getConfirmedAt())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }

    public static SettlementAdjustment toDomain(SettlementAdjustmentJpaEntity e) {
        return new SettlementAdjustment(
                e.getId(), e.getSettlementId(), e.getRefundId(),
                e.getAmount().abs(),
                SettlementAdjustmentStatus.valueOf(e.getStatus()),
                e.getAdjustmentDate(), e.getConfirmedAt(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
```

- [ ] **Step 3: 포트 + 어댑터**

`SaveSettlementAdjustmentPort.java`:
```java
package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.SettlementAdjustment;

public interface SaveSettlementAdjustmentPort {
    SettlementAdjustment save(SettlementAdjustment adjustment);
}
```

`SettlementAdjustmentPersistenceAdapter.java`:
```java
package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementAdjustmentPersistenceAdapter implements SaveSettlementAdjustmentPort {

    private final SpringDataSettlementAdjustmentJpaRepository repository;

    @Override
    public SettlementAdjustment save(SettlementAdjustment adjustment) {
        SettlementAdjustmentJpaEntity saved =
                repository.save(SettlementAdjustmentMapper.toJpa(adjustment));
        if (adjustment.getId() == null && saved.getId() != null) {
            adjustment.assignId(saved.getId());
        }
        return SettlementAdjustmentMapper.toDomain(saved);
    }
}
```

- [ ] **Step 4: 컴파일**

```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentJpaEntity.java \
        src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SpringDataSettlementAdjustmentJpaRepository.java \
        src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentMapper.java \
        src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementAdjustmentPersistenceAdapter.java \
        src/main/java/github/lms/lemuel/settlement/application/port/out/SaveSettlementAdjustmentPort.java
git commit -m "feat(settlement): SettlementAdjustment persistence (JPA + adapter)"
```

---

### Task 3.3: `AdjustSettlementForRefundService` 재작성 — Adjustment + Ledger

**Files:**
- Modify: `src/main/java/github/lms/lemuel/settlement/application/port/in/AdjustSettlementForRefundUseCase.java`
- Modify: `src/main/java/github/lms/lemuel/settlement/application/service/AdjustSettlementForRefundService.java`
- Create: `src/test/java/github/lms/lemuel/settlement/application/service/AdjustSettlementForRefundServiceTest.java`

비례 수수료 환급 공식: `commissionReversal = settlement.commission * (refundAmount / settlement.paymentAmount)`. HALF_UP, scale 2.

- [ ] **Step 1: failing test**

`AdjustSettlementForRefundServiceTest.java`:
```java
package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdjustSettlementForRefundServiceTest {

    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    @Mock private RecordJournalEntryUseCase recordJournalEntryUseCase;

    @InjectMocks private AdjustSettlementForRefundService service;

    private Settlement existingSettlement(BigDecimal payAmount, BigDecimal commission) {
        return new Settlement(
                500L, 10L, 100L, payAmount, BigDecimal.ZERO, commission,
                payAmount.subtract(commission),
                SettlementStatus.DONE, LocalDate.of(2026, 4, 25),
                null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("부분환불 30%: SettlementAdjustment INSERT, 원 Settlement 변경 없음, Ledger 분개 호출")
    void partial_refund_30pct() {
        Settlement settlement = existingSettlement(new BigDecimal("100000"), new BigDecimal("3000"));
        settlement.setSellerId(42L);
        given(loadSettlementPort.findByPaymentId(10L)).willReturn(Optional.of(settlement));
        given(saveSettlementAdjustmentPort.save(any())).willAnswer(inv -> {
            SettlementAdjustment adj = inv.getArgument(0);
            adj.assignId(7L);
            return adj;
        });

        service.adjustSettlementForRefund(99L, 10L, new BigDecimal("30000"));

        // (1) Adjustment INSERT 검증
        ArgumentCaptor<SettlementAdjustment> adjCap = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(saveSettlementAdjustmentPort).save(adjCap.capture());
        SettlementAdjustment adj = adjCap.getValue();
        assertThat(adj.getRefundId()).isEqualTo(99L);
        assertThat(adj.getSettlementId()).isEqualTo(500L);
        assertThat(adj.getAmount()).isEqualByComparingTo("30000");

        // (2) 원 Settlement 변경 없음 (refundedAmount = 0 그대로)
        assertThat(settlement.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.DONE);

        // (3) Ledger 분개 호출 — commissionReversal = 3000 * (30000/100000) = 900
        verify(recordJournalEntryUseCase).recordRefundProcessed(
                eq(99L), eq(42L),
                eq(Money.krw(new BigDecimal("30000"))),
                eq(Money.krw(new BigDecimal("900"))));
    }

    @Test
    @DisplayName("Settlement 없음: SettlementNotFoundException")
    void settlement_not_found() {
        given(loadSettlementPort.findByPaymentId(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.adjustSettlementForRefund(99L, 10L, new BigDecimal("30000")))
                .isInstanceOf(SettlementNotFoundException.class);

        verifyNoInteractions(saveSettlementAdjustmentPort, recordJournalEntryUseCase);
    }

    @Test
    @DisplayName("정확한 비율 계산: 1/3 환불 시 단수처리(HALF_UP)")
    void rounding_check() {
        Settlement settlement = existingSettlement(new BigDecimal("100000"), new BigDecimal("3333"));
        settlement.setSellerId(42L);
        given(loadSettlementPort.findByPaymentId(10L)).willReturn(Optional.of(settlement));
        given(saveSettlementAdjustmentPort.save(any())).willAnswer(inv -> {
            SettlementAdjustment a = inv.getArgument(0); a.assignId(1L); return a;
        });

        service.adjustSettlementForRefund(99L, 10L, new BigDecimal("33333"));

        // 3333 * (33333/100000) = 1111.099889 → 1111.10 (HALF_UP, scale 2)
        verify(recordJournalEntryUseCase).recordRefundProcessed(
                eq(99L), eq(42L),
                eq(Money.krw(new BigDecimal("33333"))),
                eq(Money.krw(new BigDecimal("1111.10"))));
    }
}
```

- [ ] **Step 2: 테스트 → FAIL 확인**

```bash
./gradlew test --tests "github.lms.lemuel.settlement.application.service.AdjustSettlementForRefundServiceTest"
```
Expected: 컴파일 실패 또는 옛 동작 실패.

- [ ] **Step 3: `AdjustSettlementForRefundUseCase` 시그니처 정리**

`AdjustSettlementForRefundUseCase.java` 전체 교체:
```java
package github.lms.lemuel.settlement.application.port.in;

import java.math.BigDecimal;

public interface AdjustSettlementForRefundUseCase {

    /**
     * 환불 1건당 SettlementAdjustment INSERT + Ledger recordRefundProcessed 분개 기록.
     * 원 Settlement는 변경하지 않는다 (audit immutability).
     *
     * @param refundId      환불 ID (Adjustment의 refund_id 외래키 + Ledger idempotency)
     * @param paymentId     결제 ID (Settlement 조회용)
     * @param refundAmount  환불 금액 (양수)
     */
    void adjustSettlementForRefund(Long refundId, Long paymentId, BigDecimal refundAmount);
}
```

> deprecated 메서드 제거. 호출자는 `RefundPaymentUseCase` 1곳뿐 (Chunk 2에서 새 시그니처 사용 중).

- [ ] **Step 4: 서비스 재작성**

`AdjustSettlementForRefundService.java` 전체 교체:
```java
package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AdjustSettlementForRefundService implements AdjustSettlementForRefundUseCase {

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    private final RecordJournalEntryUseCase recordJournalEntryUseCase;

    @Override
    public void adjustSettlementForRefund(Long refundId, Long paymentId, BigDecimal refundAmount) {
        Settlement settlement = loadSettlementPort.findByPaymentId(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "Settlement not found for paymentId=" + paymentId));

        // 1. Adjustment INSERT (원 Settlement는 mutate하지 않음)
        SettlementAdjustment adjustment = SettlementAdjustment.forRefund(
                settlement.getId(), refundId, refundAmount, LocalDate.now());
        SettlementAdjustment savedAdjustment = saveSettlementAdjustmentPort.save(adjustment);

        // 2. 비례 수수료 환급 계산
        BigDecimal commissionReversal = settlement.getCommission()
                .multiply(refundAmount)
                .divide(settlement.getPaymentAmount(), 2, RoundingMode.HALF_UP);

        // 3. Ledger 분개 기록 (refundId로 멱등 보장)
        recordJournalEntryUseCase.recordRefundProcessed(
                refundId,
                settlement.getSellerId(),
                Money.krw(refundAmount),
                Money.krw(commissionReversal)
        );

        log.info("Refund adjustment recorded. settlementId={}, adjustmentId={}, refundId={}, refund={}, commissionReversal={}",
                settlement.getId(), savedAdjustment.getId(), refundId, refundAmount, commissionReversal);
    }
}
```

> 변경 포인트:
> - 원 `Settlement`를 mutate하지 않음 (`adjustForRefund` 호출 제거)
> - `SettlementAdjustment` INSERT
> - `RecordJournalEntryUseCase.recordRefundProcessed` 호출 (sellerId 사용)

- [ ] **Step 5: 테스트 → PASS 확인**

```bash
./gradlew test --tests "github.lms.lemuel.settlement.application.service.AdjustSettlementForRefundServiceTest"
```
Expected: 3 tests passed.

전체 테스트도 통과해야 함:
```bash
./gradlew test
```
Expected: 모두 통과 (RefundPaymentUseCaseTest는 mock이므로 영향 없음, CreateDailySettlementsServiceLedgerTest도 mock).

- [ ] **Step 6: `Settlement.adjustForRefund` 처리**

이제 `Settlement.adjustForRefund(BigDecimal)` 메서드는 production 호출자가 0건. 도메인을 mutate해서 audit immutability를 깨뜨리는 위험한 메서드이므로 **삭제**.

`Settlement.java`에서 line 202-229 (`// ========== 환불 처리 ==========` ~ `adjustForRefund` 메서드 끝)을 제거.

- [ ] **Step 7: 전체 테스트**

```bash
./gradlew test
```
Expected: 모두 통과.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/application/port/in/AdjustSettlementForRefundUseCase.java \
        src/main/java/github/lms/lemuel/settlement/application/service/AdjustSettlementForRefundService.java \
        src/main/java/github/lms/lemuel/settlement/domain/Settlement.java \
        src/test/java/github/lms/lemuel/settlement/application/service/AdjustSettlementForRefundServiceTest.java
git commit -m "feat(settlement): refund adjustment via SettlementAdjustment + ledger posting"
```

---

## Chunk 4: End-to-End Verification + Cleanup

목표: 전체 흐름이 통합 환경에서 동작 검증. 데드 코드 정리. README/문서 업데이트.

### Task 4.1: 통합 테스트 — Refund 전체 흐름

**Files:**
- Create: `src/test/java/github/lms/lemuel/payment/RefundFlowIntegrationTest.java`

> 통합 테스트는 `@SpringBootTest` + 임베디드 또는 testcontainers PostgreSQL을 사용. 본 프로젝트의 기존 통합 테스트 인프라 확인 후 동일 패턴 사용. 기존 통합 테스트가 없거나 Testcontainers 미설정인 경우, 본 task는 **스킵**하고 Task 4.2의 단위 슬라이스 테스트로 대체.

- [ ] **Step 1: 기존 통합 테스트 인프라 확인**

```bash
./gradlew test --tests "*IntegrationTest" --info
```
또는
```
./gradlew dependencies | grep testcontainers
```

Testcontainers/Spring Boot Test 슬라이스 사용 가능 여부 확인.

- [ ] **Step 2: 가능하면 통합 테스트 작성**

```java
@SpringBootTest
@Transactional
class RefundFlowIntegrationTest {

    @Autowired RefundPaymentPort refundPaymentPort;
    @Autowired SpringDataRefundJpaRepository refundRepo;
    @Autowired SpringDataSettlementAdjustmentJpaRepository adjustmentRepo;
    @Autowired SpringDataJournalEntryJpaRepository journalRepo;
    // + 결제/정산 사전 데이터 셋업용 픽스처

    @Test
    @DisplayName("부분환불 → Refund INSERT + SettlementAdjustment INSERT + JournalEntry INSERT")
    void partial_refund_full_flow() {
        // given: CAPTURED Payment + DONE Settlement 사전 셋업
        Long paymentId = ...;
        Long settlementId = ...;

        // when
        Refund refund = refundPaymentPort.refund(
                new RefundCommand(paymentId, new BigDecimal("30000"), "K1", "test"));

        // then
        assertThat(refundRepo.findById(refund.getId())).isPresent();
        assertThat(adjustmentRepo.existsByRefundId(refund.getId())).isTrue();
        assertThat(journalRepo.findByIdempotencyKey("REFUND_PROCESSED:" + refund.getId())).isPresent();
    }

    @Test
    @DisplayName("동일 idempotencyKey 재요청: 새 Refund 안 생기고 기존 반환")
    void idempotent_replay() { ... }

    @Test
    @DisplayName("초과 환불: RefundExceedsPaymentException")
    void over_refund() { ... }
}
```

- [ ] **Step 3: 테스트 실행**

```bash
./gradlew test --tests "github.lms.lemuel.payment.RefundFlowIntegrationTest"
```

- [ ] **Step 4: Commit**

```bash
git add src/test/java/github/lms/lemuel/payment/RefundFlowIntegrationTest.java
git commit -m "test(payment): end-to-end integration test for partial refund flow"
```

---

### Task 4.2: Dead Code 청소 + 회귀 테스트

**Files:**
- Modify: `src/main/java/github/lms/lemuel/payment/domain/PaymentDomain.java` (deprecated `refund()` 제거)
- Modify: 호출자가 있다면 함께 수정

- [ ] **Step 1: deprecated `refund()` 호출자 검색**

```bash
git grep -n "\.refund()" -- 'src/main/**/*.java'
```

호출이 있다면 모두 `requestRefund(getRefundableAmount())`로 교체.

- [ ] **Step 2: `PaymentDomain.refund()` 제거**

`PaymentDomain.java`에서 deprecated `refund()` 메서드 삭제.

- [ ] **Step 3: 전체 빌드 + 테스트**

```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL, all tests passed.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/github/lms/lemuel/payment/domain/PaymentDomain.java
git commit -m "refactor(payment): remove deprecated PaymentDomain.refund() in favor of requestRefund(amount)"
```

---

### Task 4.3: 문서 업데이트

**Files:**
- Create or Modify: `docs/adr/ADR-XXX-refund-as-immutable-adjustment.md`

- [ ] **Step 1: ADR 작성**

`docs/adr/ADR-007-refund-as-immutable-adjustment.md` (번호는 디렉토리 확인 후 결정):
```markdown
# ADR-007: 환불을 정산 원본 mutation 대신 SettlementAdjustment + Ledger 분개로 처리

## Status
Accepted (2026-04-26)

## Context
정산이 DONE 상태가 된 이후 발생하는 환불은 회계 원장(audit log)의
immutability 원칙을 깨뜨리지 않고 처리되어야 한다. 이전 구조는
`Settlement.adjustForRefund()`가 원 정산 레코드를 in-place 수정하여:
- 정산 합계의 시계열 추적 불가
- 음수 정산 표현 불가
- 회계감사 시 "최초 정산 금액"이 무엇이었는지 알 수 없음

## Decision
1. 환불 1건당 `SettlementAdjustment` 레코드를 신규 INSERT
2. 원 `Settlement`는 변경하지 않음 (immutable after creation)
3. 정산 실 잔액은 `Settlement.netAmount + SUM(SettlementAdjustment.amount)`
   집계로 도출 (DB 음수 amount + 도메인 양수)
4. 환불 시 수수료 비례 환급(`commission * refund/payment`)을
   `RecordJournalEntryUseCase.recordRefundProcessed`로 Ledger 분개 기록
5. 분개 멱등성은 `refundId`를 idempotencyKey로 사용

## Consequences
- (+) 회계 무결성: 원본 정산 immutable
- (+) 차변/대변 균형 검증이 분개 단위로 보장
- (+) refundId 기반 멱등성으로 동일 환불 재처리 시 분개 중복 방지
- (-) 정산 실 잔액 조회 시 JOIN 또는 집계 필요 (Phase 후속에서 read model 도입)
- (-) Settlement 도메인의 `adjustForRefund()` 제거로 외부 호출자가 있었다면 깨짐
  (현 시점 호출자 0건)
```

- [ ] **Step 2: README 또는 docs/superpowers/specs 갱신**

기존 ledger spec 문서가 있다면 "환불 처리"섹션을 본 ADR로 링크.

- [ ] **Step 3: Commit**

```bash
git add docs/adr/ADR-007-refund-as-immutable-adjustment.md
git commit -m "docs(adr): refund as immutable settlement adjustment + ledger posting"
```

---

## Verification Checklist (전체 plan 완료 후)

- [ ] `./gradlew build` 성공
- [ ] `./gradlew test` — 신규 테스트 모두 통과:
  - `RefundTest` (7)
  - `PaymentDomainRefundTest` (6)
  - `RefundPaymentUseCaseTest` (3+)
  - `SettlementAdjustmentTest` (4)
  - `AdjustSettlementForRefundServiceTest` (3)
- [ ] 기존 테스트 회귀 0건 (`CreateDailySettlementsServiceSellerTest`, `CreateDailySettlementsServiceLedgerTest`, `CommissionCalculationTest` 통과)
- [ ] 수동 검증 (또는 통합 테스트): Verification Anchor 시나리오 (이 문서 상단) 통과
- [ ] `git grep "TODO.*refund" -- 'src/main/**/*.java'` — 결과 0건 (원래 있던 `RefundController`의 TODO 제거됨)
- [ ] `git grep "RefundExceedsPaymentException" -- 'src/main/**/*.java'` — `PaymentDomain.requestRefund` 1곳에서 throw 확인
- [ ] `git grep "settlement_adjustments\|SettlementAdjustment" -- 'src/main/**/*.java'` — 도메인/JPA/매퍼/어댑터/서비스에서 참조 확인 (dead schema 해소)

## Out-of-Scope Reminders

본 plan은 **부분환불+역정산+ledger 호출**에 집중한다. 다음 항목은 별도 plan으로 추진:
- Toss API 멱등성 헤더 실 전달 + ALREADY_PROCESSED_PAYMENT 처리
- VAT/원천징수 분리
- 영업일/공휴일 보정
- WEEKLY/MONTHLY 정산 사이클 BillingPeriod
- SettlementStatus enum 11개 정리
- 송금/이체 어댑터 (펌뱅킹 mock 포함)
- Spring Batch chunk-oriented 전환
- ES 진짜 bulk + 인덱스 큐 워커
- Outbox 패턴 / Kafka 비동기

면접 질문 시 위 항목들은 "다음 단계로 계획되어 있음"으로 명시 (모르는 척 X, 우선순위 결정의 결과 O).
