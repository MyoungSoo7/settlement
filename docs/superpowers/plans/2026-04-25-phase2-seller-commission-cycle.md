# Phase 2: Seller Commission & Settlement Cycle Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add seller-specific commission rates and settlement cycles (DAILY/WEEKLY/MONTHLY) so each seller gets settled with their own rate on their own schedule, replacing the hardcoded 3% flat rate.

**Architecture:** Extend the existing `Seller` domain with `SettlementCycle` and `CommissionCalculation`. Modify `CapturedPaymentInfo` to carry seller context. Update `CreateDailySettlementsService` to filter by settlement-due sellers and apply per-seller commission rates. Wire seller_id into Settlement + Ledger.

**Tech Stack:** Java 21, Spring Boot 3.5.10, PostgreSQL, Spring Data JPA, Flyway, JUnit5 + Mockito

**Spec Reference:** `docs/superpowers/specs/2026-04-24-ledger-settlement-withdrawal-design.md` Section 5

---

## File Structure

### New Files

```
src/main/java/github/lms/lemuel/seller/domain/
  SettlementCycle.java                    — Enum: DAILY, WEEKLY, MONTHLY

src/main/java/github/lms/lemuel/settlement/domain/
  CommissionCalculation.java              — Value Object: paymentAmount × commissionRate

src/main/resources/db/migration/
  V36__add_seller_settlement_cycle.sql    — ALTER sellers + settlements

src/test/java/github/lms/lemuel/seller/domain/
  SellerSettlementCycleTest.java          — Tests for isSettlementDueOn()

src/test/java/github/lms/lemuel/settlement/domain/
  CommissionCalculationTest.java          — Tests for commission calculation

src/test/java/github/lms/lemuel/settlement/application/service/
  CreateDailySettlementsServiceSellerTest.java — Tests for seller-aware settlement
```

### Modified Files

```
src/main/java/github/lms/lemuel/seller/domain/Seller.java
  — Add settlementCycle, weeklySettlementDay, monthlySettlementDay, minimumWithdrawalAmount fields
  — Add isSettlementDueOn(LocalDate) method

src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SellerJpaEntity.java
  — Add 4 new columns

src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SellerPersistenceMapper.java
  — Map new fields

src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementPersistenceMapper.java
  — MapStruct: sellerId auto-maps (same field name in domain + entity)

src/main/java/github/lms/lemuel/settlement/domain/Settlement.java
  — Add sellerId field, overloaded createFromPayment with sellerId + commissionRate

src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementJpaEntity.java
  — Add seller_id column

src/main/java/github/lms/lemuel/settlement/application/port/out/LoadCapturedPaymentsPort.java
  — Add sellerId to CapturedPaymentInfo record

src/main/java/github/lms/lemuel/settlement/adapter/out/payment/CapturedPaymentsAdapter.java
  — Resolve sellerId via order → product → seller chain

src/main/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsService.java
  — Filter sellers by settlement cycle, apply per-seller commission, pass sellerId to Ledger

src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementPersistenceMapper.java
  — Map sellerId field
```

---

## Chunk 1: Domain Layer (SettlementCycle + CommissionCalculation + Seller Extension)

### Task 1: SettlementCycle Enum

**Files:**
- Create: `src/main/java/github/lms/lemuel/seller/domain/SettlementCycle.java`
- Test: `src/test/java/github/lms/lemuel/seller/domain/SellerSettlementCycleTest.java`

- [ ] **Step 1: Write SettlementCycle tests**

```java
package github.lms.lemuel.seller.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class SellerSettlementCycleTest {

    @Nested
    @DisplayName("DAILY 정산 주기")
    class Daily {
        @Test
        void 매일_정산_대상이다() {
            Seller seller = createSeller(SettlementCycle.DAILY, null, null);
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 25))).isTrue();
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 26))).isTrue();
        }
    }

    @Nested
    @DisplayName("WEEKLY 정산 주기")
    class Weekly {
        @Test
        void 지정된_요일에만_정산_대상이다() {
            Seller seller = createSeller(SettlementCycle.WEEKLY, DayOfWeek.MONDAY, null);
            // 2026-04-27 = MONDAY
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 27))).isTrue();
            // 2026-04-25 = FRIDAY
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 25))).isFalse();
        }
    }

    @Nested
    @DisplayName("MONTHLY 정산 주기")
    class Monthly {
        @Test
        void 지정된_날짜에만_정산_대상이다() {
            Seller seller = createSeller(SettlementCycle.MONTHLY, null, 15);
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 15))).isTrue();
            assertThat(seller.isSettlementDueOn(LocalDate.of(2026, 4, 16))).isFalse();
        }

        @Test
        void 유효하지_않은_날짜는_예외를_던진다() {
            assertThatThrownBy(() -> createSeller(SettlementCycle.MONTHLY, null, 29))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 유효하지_않은_날짜_0도_예외를_던진다() {
            assertThatThrownBy(() -> createSeller(SettlementCycle.MONTHLY, null, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Seller createSeller(SettlementCycle cycle, DayOfWeek weeklyDay, Integer monthlyDay) {
        Seller seller = Seller.create(1L, "테스트상점", "1234567890", "홍길동", "010-1234-5678", "test@test.com");
        seller.updateSettlementCycle(cycle, weeklyDay, monthlyDay);
        return seller;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.seller.domain.SellerSettlementCycleTest" 2>&1 | tail -10`
Expected: Compilation error — SettlementCycle doesn't exist

- [ ] **Step 3: Create SettlementCycle enum**

```java
package github.lms.lemuel.seller.domain;

public enum SettlementCycle {
    DAILY,
    WEEKLY,
    MONTHLY
}
```

- [ ] **Step 4: Extend Seller domain with settlement cycle fields and methods**

Add these fields to `src/main/java/github/lms/lemuel/seller/domain/Seller.java` after `updatedAt`:

```java
    private SettlementCycle settlementCycle;
    private java.time.DayOfWeek weeklySettlementDay;
    private Integer monthlySettlementDay;
    private BigDecimal minimumWithdrawalAmount;
```

Modify the `create()` factory to set default values — add after `seller.updatedAt = LocalDateTime.now();`:

```java
        seller.settlementCycle = SettlementCycle.DAILY;
        seller.minimumWithdrawalAmount = new BigDecimal("1000");
```

Add these methods before the getters section:

```java
    public void updateSettlementCycle(SettlementCycle cycle, java.time.DayOfWeek weeklyDay, Integer monthlyDay) {
        if (cycle == null) {
            throw new IllegalArgumentException("정산 주기는 필수입니다.");
        }
        if (cycle == SettlementCycle.MONTHLY) {
            if (monthlyDay == null || monthlyDay < 1 || monthlyDay > 28) {
                throw new IllegalArgumentException("월 정산일은 1~28 사이여야 합니다.");
            }
        }
        this.settlementCycle = cycle;
        this.weeklySettlementDay = weeklyDay;
        this.monthlySettlementDay = monthlyDay;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isSettlementDueOn(java.time.LocalDate date) {
        if (settlementCycle == null) return true; // default DAILY
        return switch (settlementCycle) {
            case DAILY -> true;
            case WEEKLY -> date.getDayOfWeek() == weeklySettlementDay;
            case MONTHLY -> date.getDayOfMonth() == monthlySettlementDay;
        };
    }
```

Add getters/setters for the new fields at the end of the getters section:

```java
    public SettlementCycle getSettlementCycle()          { return settlementCycle; }
    public void setSettlementCycle(SettlementCycle c)    { this.settlementCycle = c; }

    public java.time.DayOfWeek getWeeklySettlementDay()  { return weeklySettlementDay; }
    public void setWeeklySettlementDay(java.time.DayOfWeek d) { this.weeklySettlementDay = d; }

    public Integer getMonthlySettlementDay()             { return monthlySettlementDay; }
    public void setMonthlySettlementDay(Integer d)       { this.monthlySettlementDay = d; }

    public BigDecimal getMinimumWithdrawalAmount()       { return minimumWithdrawalAmount; }
    public void setMinimumWithdrawalAmount(BigDecimal a) { this.minimumWithdrawalAmount = a; }
```

- [ ] **Step 5: Run tests and verify all pass**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.seller.domain.SellerSettlementCycleTest" 2>&1 | tail -10`
Expected: All 5 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/github/lms/lemuel/seller/domain/SettlementCycle.java \
        src/main/java/github/lms/lemuel/seller/domain/Seller.java \
        src/test/java/github/lms/lemuel/seller/domain/SellerSettlementCycleTest.java
git commit -m "feat(seller): add SettlementCycle enum and isSettlementDueOn() to Seller domain"
```

---

### Task 2: CommissionCalculation Value Object

**Files:**
- Create: `src/main/java/github/lms/lemuel/settlement/domain/CommissionCalculation.java`
- Test: `src/test/java/github/lms/lemuel/settlement/domain/CommissionCalculationTest.java`

- [ ] **Step 1: Write CommissionCalculation tests**

```java
package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class CommissionCalculationTest {

    @Test
    @DisplayName("기본 수수료율 3%로 수수료를 계산한다")
    void 기본_수수료_계산() {
        CommissionCalculation calc = CommissionCalculation.calculate(
                new BigDecimal("10000"), new BigDecimal("0.03"));

        assertThat(calc.paymentAmount()).isEqualByComparingTo("10000");
        assertThat(calc.commissionRate()).isEqualByComparingTo("0.03");
        assertThat(calc.commissionAmount()).isEqualByComparingTo("300.00");
        assertThat(calc.netAmount()).isEqualByComparingTo("9700.00");
    }

    @Test
    @DisplayName("VIP 수수료율 2.5%로 수수료를 계산한다")
    void VIP_수수료_계산() {
        CommissionCalculation calc = CommissionCalculation.calculate(
                new BigDecimal("10000"), new BigDecimal("0.025"));

        assertThat(calc.commissionAmount()).isEqualByComparingTo("250.00");
        assertThat(calc.netAmount()).isEqualByComparingTo("9750.00");
    }

    @Test
    @DisplayName("소수점 반올림 처리")
    void 소수점_반올림() {
        CommissionCalculation calc = CommissionCalculation.calculate(
                new BigDecimal("333"), new BigDecimal("0.03"));

        // 333 * 0.03 = 9.99
        assertThat(calc.commissionAmount()).isEqualByComparingTo("9.99");
        assertThat(calc.netAmount()).isEqualByComparingTo("323.01");
    }

    @Test
    @DisplayName("금액이 0이하이면 예외를 던진다")
    void 금액_검증() {
        assertThatThrownBy(() -> CommissionCalculation.calculate(
                BigDecimal.ZERO, new BigDecimal("0.03")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("수수료율이 범위 밖이면 예외를 던진다")
    void 수수료율_검증() {
        assertThatThrownBy(() -> CommissionCalculation.calculate(
                new BigDecimal("10000"), new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.settlement.domain.CommissionCalculationTest" 2>&1 | tail -10`
Expected: Compilation error

- [ ] **Step 3: Implement CommissionCalculation**

```java
package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CommissionCalculation(
    BigDecimal paymentAmount,
    BigDecimal commissionRate,
    BigDecimal commissionAmount,
    BigDecimal netAmount
) {
    public static CommissionCalculation calculate(BigDecimal paymentAmount,
                                                   BigDecimal commissionRate) {
        if (paymentAmount == null || paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다.");
        }
        if (commissionRate == null || commissionRate.compareTo(BigDecimal.ZERO) < 0
                || commissionRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("수수료율은 0 이상 1 미만이어야 합니다.");
        }
        BigDecimal commission = paymentAmount.multiply(commissionRate)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = paymentAmount.subtract(commission)
                .setScale(2, RoundingMode.HALF_UP);
        return new CommissionCalculation(paymentAmount, commissionRate, commission, net);
    }
}
```

- [ ] **Step 4: Run tests and verify all pass**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.settlement.domain.CommissionCalculationTest" 2>&1 | tail -10`
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/domain/CommissionCalculation.java \
        src/test/java/github/lms/lemuel/settlement/domain/CommissionCalculationTest.java
git commit -m "feat(settlement): add CommissionCalculation value object with per-seller rate support"
```

---

## Chunk 2: Persistence Layer (Migration + JPA Entity Updates + Mapper Updates)

### Task 3: Flyway Migration V36

**Files:**
- Create: `src/main/resources/db/migration/V36__add_seller_settlement_cycle.sql`

- [ ] **Step 1: Create migration file**

```sql
-- V36: Seller settlement cycle + Settlement seller linkage

-- 1. Add settlement cycle columns to sellers
ALTER TABLE sellers
    ADD COLUMN IF NOT EXISTS settlement_cycle VARCHAR(10) NOT NULL DEFAULT 'DAILY',
    ADD COLUMN IF NOT EXISTS weekly_settlement_day VARCHAR(10),
    ADD COLUMN IF NOT EXISTS monthly_settlement_day INT,
    ADD COLUMN IF NOT EXISTS minimum_withdrawal_amount NUMERIC(12,2) NOT NULL DEFAULT 1000;

ALTER TABLE sellers ADD CONSTRAINT chk_monthly_day
    CHECK (monthly_settlement_day IS NULL OR (monthly_settlement_day >= 1 AND monthly_settlement_day <= 28));

-- 2. Add seller_id to settlements (nullable for backward compatibility)
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS seller_id BIGINT REFERENCES sellers(id);
CREATE INDEX IF NOT EXISTS idx_settlements_seller_id ON settlements(seller_id);

```

- [ ] **Step 2: Verify build**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V36__add_seller_settlement_cycle.sql
git commit -m "feat(seller): add Flyway V36 migration for settlement cycle and seller linkage"
```

---

### Task 4: JPA Entity & Mapper Updates (Seller + Settlement)

**Files:**
- Modify: `src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SellerJpaEntity.java`
- Modify: `src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SellerPersistenceMapper.java`
- Modify: `src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementJpaEntity.java`
- Modify: `src/main/java/github/lms/lemuel/settlement/domain/Settlement.java`

- [ ] **Step 1: Add settlement cycle columns to SellerJpaEntity**

Add these fields after `updatedAt` in `SellerJpaEntity.java`:

```java
    @Column(name = "settlement_cycle", nullable = false, length = 10)
    private String settlementCycle = "DAILY";

    @Column(name = "weekly_settlement_day", length = 10)
    private String weeklySettlementDay;

    @Column(name = "monthly_settlement_day")
    private Integer monthlySettlementDay;

    @Column(name = "minimum_withdrawal_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal minimumWithdrawalAmount = new BigDecimal("1000");
```

- [ ] **Step 2: Update SellerPersistenceMapper for new fields**

Add these lines to the `toDomain()` method in `SellerPersistenceMapper.java`, before `return seller;`:

```java
        seller.setSettlementCycle(
            entity.getSettlementCycle() != null
                ? github.lms.lemuel.seller.domain.SettlementCycle.valueOf(entity.getSettlementCycle())
                : github.lms.lemuel.seller.domain.SettlementCycle.DAILY);
        if (entity.getWeeklySettlementDay() != null) {
            seller.setWeeklySettlementDay(java.time.DayOfWeek.valueOf(entity.getWeeklySettlementDay()));
        }
        seller.setMonthlySettlementDay(entity.getMonthlySettlementDay());
        seller.setMinimumWithdrawalAmount(entity.getMinimumWithdrawalAmount());
```

Add these lines to the `toEntity()` method, before `return entity;`:

```java
        entity.setSettlementCycle(
            domain.getSettlementCycle() != null ? domain.getSettlementCycle().name() : "DAILY");
        entity.setWeeklySettlementDay(
            domain.getWeeklySettlementDay() != null ? domain.getWeeklySettlementDay().name() : null);
        entity.setMonthlySettlementDay(domain.getMonthlySettlementDay());
        entity.setMinimumWithdrawalAmount(domain.getMinimumWithdrawalAmount());
```

- [ ] **Step 3: Add seller_id to SettlementJpaEntity**

Add after `orderId` field in `SettlementJpaEntity.java`:

```java
    @Column(name = "seller_id")
    private Long sellerId;
```

- [ ] **Step 4: Add sellerId to Settlement domain**

Add after `orderId` field in `Settlement.java`:

```java
    private Long sellerId;
```

Add an overloaded factory method after `createFromPayment`:

```java
    public static Settlement createFromPayment(Long paymentId, Long orderId, Long sellerId,
                                               BigDecimal paymentAmount, BigDecimal commissionRate,
                                               LocalDate settlementDate) {
        Settlement settlement = new Settlement();
        settlement.setPaymentId(paymentId);
        settlement.setOrderId(orderId);
        settlement.setSellerId(sellerId);
        settlement.setPaymentAmount(paymentAmount);
        settlement.setSettlementDate(settlementDate);

        settlement.validatePaymentId();
        settlement.validateAmount();
        settlement.validateSettlementDate();

        CommissionCalculation calc = CommissionCalculation.calculate(paymentAmount, commissionRate);
        settlement.commission = calc.commissionAmount();
        settlement.netAmount = calc.netAmount();

        return settlement;
    }
```

Add getter/setter for sellerId in the getters section:

```java
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
```

- [ ] **Step 5: Verify SettlementPersistenceMapper auto-maps sellerId**

`SettlementPersistenceMapper` is a MapStruct `@Mapper` interface at `src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementPersistenceMapper.java`. Since both `Settlement.sellerId` and `SettlementJpaEntity.sellerId` have the same field name, MapStruct will auto-map them — no code change needed. Verify this compiles without unmapped-property warnings for `sellerId`.

- [ ] **Step 6: Verify compilation**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SellerJpaEntity.java \
        src/main/java/github/lms/lemuel/seller/adapter/out/persistence/SellerPersistenceMapper.java \
        src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementJpaEntity.java \
        src/main/java/github/lms/lemuel/settlement/domain/Settlement.java
git commit -m "feat: add settlement cycle to Seller JPA + sellerId to Settlement domain and JPA"
```

---

## Chunk 3: Port + Adapter Changes (Payment-to-Seller Resolution)

> **Note:** No new port method needed for seller lookup. `LoadSellerPort.findByStatus(SellerStatus.APPROVED)` already exists and will be used in the service layer.

### Task 5: Extend CapturedPaymentInfo with sellerId

**Files:**
- Modify: `src/main/java/github/lms/lemuel/settlement/application/port/out/LoadCapturedPaymentsPort.java`
- Modify: `src/main/java/github/lms/lemuel/settlement/adapter/out/payment/CapturedPaymentsAdapter.java`

- [ ] **Step 1: Add sellerId to CapturedPaymentInfo**

Replace the `CapturedPaymentInfo` record in `LoadCapturedPaymentsPort.java`:

```java
    record CapturedPaymentInfo(
            Long paymentId,
            Long orderId,
            Long sellerId,
            BigDecimal amount,
            LocalDateTime capturedAt
    ) {}
```

- [ ] **Step 2: Add findCapturedPaymentsByDateAndSeller method to the port**

Add this method to `LoadCapturedPaymentsPort`:

```java
    List<CapturedPaymentInfo> findCapturedPaymentsByDateAndSeller(LocalDate settlementDate, Long sellerId);
```

- [ ] **Step 3: Update CapturedPaymentsAdapter**

The adapter needs to resolve `sellerId` from the `order → product → seller` chain. Update `CapturedPaymentsAdapter.java`:

First, find the product repository/entity that has `seller_id`:

The product table already has `seller_id` column (from V31 migration: `ALTER TABLE products ADD COLUMN IF NOT EXISTS seller_id BIGINT REFERENCES sellers(id);`).

Update the adapter to inject the needed repository and resolve sellerId. Replace the existing `findCapturedPaymentsByDate` method and add the new method:

```java
package github.lms.lemuel.settlement.adapter.out.payment;

import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaEntity;
import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaRepository;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CapturedPaymentsAdapter implements LoadCapturedPaymentsPort {

    private final PaymentJpaRepository paymentJpaRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<CapturedPaymentInfo> findCapturedPaymentsByDate(LocalDate settlementDate) {
        LocalDateTime startDateTime = settlementDate.atStartOfDay();
        LocalDateTime endDateTime = settlementDate.plusDays(1).atStartOfDay();

        List<PaymentJpaEntity> payments = paymentJpaRepository
                .findByCapturedAtBetweenAndStatus(startDateTime, endDateTime, "CAPTURED");

        return payments.stream()
                .map(payment -> {
                    Long sellerId = resolveSellerId(payment.getOrderId());
                    return new CapturedPaymentInfo(
                            payment.getId(),
                            payment.getOrderId(),
                            sellerId,
                            payment.getAmount(),
                            payment.getCapturedAt()
                    );
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<CapturedPaymentInfo> findCapturedPaymentsByDateAndSeller(
            LocalDate settlementDate, Long sellerId) {
        return findCapturedPaymentsByDate(settlementDate).stream()
                .filter(p -> sellerId.equals(p.sellerId()))
                .collect(Collectors.toList());
    }

    private Long resolveSellerId(Long orderId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT p.seller_id FROM orders o JOIN products p ON o.product_id = p.id WHERE o.id = ?",
                    Long.class, orderId);
        } catch (Exception e) {
            return null; // seller not linked yet
        }
    }
}
```

- [ ] **Step 4: Fix any compilation errors**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew compileJava 2>&1 | tail -20`

Any test that constructs `CapturedPaymentInfo` with 4 args will need a 5th `sellerId` argument. Fix them:

In `CreateDailySettlementsServiceLedgerTest.java`, update the `CapturedPaymentInfo` constructor call:
```java
CapturedPaymentInfo payment = new CapturedPaymentInfo(
        1L, 100L, 42L, new BigDecimal("10000"), LocalDateTime.now());
```

- [ ] **Step 5: Run tests**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/application/port/out/LoadCapturedPaymentsPort.java \
        src/main/java/github/lms/lemuel/settlement/adapter/out/payment/CapturedPaymentsAdapter.java \
        src/test/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsServiceLedgerTest.java
git commit -m "feat(settlement): extend CapturedPaymentInfo with sellerId and add seller resolution"
```

---

## Chunk 4: Service Integration (Seller-Aware Settlement Creation)

### Task 6: Refactor CreateDailySettlementsService for Seller-Aware Settlement

**Files:**
- Modify: `src/main/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsService.java`
- Create: `src/test/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsServiceSellerTest.java`

- [ ] **Step 1: Write seller-aware settlement test**

```java
package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;
import github.lms.lemuel.seller.domain.SettlementCycle;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementCommand;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort.CapturedPaymentInfo;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
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
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateDailySettlementsServiceSellerTest {

    @Mock
    private LoadCapturedPaymentsPort loadCapturedPaymentsPort;

    @Mock
    private SaveSettlementPort saveSettlementPort;

    @Mock
    private SettlementSearchIndexPort settlementSearchIndexPort;

    @Mock
    private RecordJournalEntryUseCase recordJournalEntryUseCase;

    @Mock
    private LoadSellerPort loadSellerPort;

    @InjectMocks
    private CreateDailySettlementsService service;

    @Test
    @DisplayName("판매자별 수수료율을 적용하여 정산을 생성한다")
    void 판매자별_수수료_적용() {
        LocalDate targetDate = LocalDate.of(2026, 4, 25);

        Seller seller = Seller.create(1L, "테스트상점", "1234567890", "홍길동", "010-1234-5678", "test@test.com");
        seller.setId(42L);
        seller.approve();
        seller.updateCommissionRate(new BigDecimal("0.05")); // 5% 수수료

        CapturedPaymentInfo payment = new CapturedPaymentInfo(
                1L, 100L, 42L, new BigDecimal("10000"), LocalDateTime.now());

        given(loadSellerPort.findByStatus(SellerStatus.APPROVED)).willReturn(List.of(seller));
        given(loadCapturedPaymentsPort.findCapturedPaymentsByDateAndSeller(targetDate, 42L))
                .willReturn(List.of(payment));
        given(saveSettlementPort.save(any())).willAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        given(settlementSearchIndexPort.isSearchEnabled()).willReturn(false);

        service.createDailySettlements(new CreateSettlementCommand(targetDate));

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(saveSettlementPort).save(captor.capture());

        Settlement saved = captor.getValue();
        assertThat(saved.getSellerId()).isEqualTo(42L);
        assertThat(saved.getCommission()).isEqualByComparingTo("500.00"); // 10000 * 5%
        assertThat(saved.getNetAmount()).isEqualByComparingTo("9500.00");
    }

    @Test
    @DisplayName("WEEKLY 판매자는 해당 요일이 아니면 정산하지 않는다")
    void WEEKLY_판매자_필터링() {
        // 2026-04-25 = FRIDAY
        LocalDate targetDate = LocalDate.of(2026, 4, 25);

        Seller seller = Seller.create(1L, "테스트상점", "1234567890", "홍길동", "010-1234-5678", "test@test.com");
        seller.setId(42L);
        seller.approve();
        seller.updateSettlementCycle(SettlementCycle.WEEKLY, java.time.DayOfWeek.MONDAY, null);

        given(loadSellerPort.findByStatus(SellerStatus.APPROVED)).willReturn(List.of(seller));

        service.createDailySettlements(new CreateSettlementCommand(targetDate));

        // seller filtered out → no payment lookup, no save
        verify(loadCapturedPaymentsPort, never()).findCapturedPaymentsByDateAndSeller(any(), any());
        verify(saveSettlementPort, never()).save(any());
    }

    @Test
    @DisplayName("Ledger 분개에 실제 sellerId가 전달된다")
    void 레저_분개에_셀러ID_전달() {
        LocalDate targetDate = LocalDate.of(2026, 4, 25);

        Seller seller = Seller.create(1L, "테스트상점", "1234567890", "홍길동", "010-1234-5678", "test@test.com");
        seller.setId(42L);
        seller.approve();

        CapturedPaymentInfo payment = new CapturedPaymentInfo(
                1L, 100L, 42L, new BigDecimal("10000"), LocalDateTime.now());

        given(loadSellerPort.findByStatus(SellerStatus.APPROVED)).willReturn(List.of(seller));
        given(loadCapturedPaymentsPort.findCapturedPaymentsByDateAndSeller(targetDate, 42L))
                .willReturn(List.of(payment));
        given(saveSettlementPort.save(any())).willAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        given(settlementSearchIndexPort.isSearchEnabled()).willReturn(false);

        service.createDailySettlements(new CreateSettlementCommand(targetDate));

        verify(recordJournalEntryUseCase).recordSettlementCreated(
                eq(1L),           // settlementId
                eq(42L),          // sellerId (no longer 0L!)
                any(Money.class), // paymentAmount
                any(Money.class)  // commission
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.settlement.application.service.CreateDailySettlementsServiceSellerTest" 2>&1 | tail -20`
Expected: FAIL — `loadSellerPort` field not in `CreateDailySettlementsService`

- [ ] **Step 3: Refactor CreateDailySettlementsService**

Replace the entire content of `CreateDailySettlementsService.java` with:

```java
package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort.CapturedPaymentInfo;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreateDailySettlementsService implements CreateDailySettlementsUseCase {

    private final LoadCapturedPaymentsPort loadCapturedPaymentsPort;
    private final SaveSettlementPort saveSettlementPort;
    private final SettlementSearchIndexPort settlementSearchIndexPort;
    private final RecordJournalEntryUseCase recordJournalEntryUseCase;
    private final LoadSellerPort loadSellerPort;

    @Override
    public CreateSettlementResult createDailySettlements(CreateSettlementCommand command) {
        log.info("일일 정산 생성 시작: targetDate={}", command.targetDate());

        // 1. 승인된 판매자 중 오늘 정산 대상인 판매자 필터링
        List<Seller> dueSellers = loadSellerPort.findByStatus(SellerStatus.APPROVED).stream()
                .filter(seller -> seller.isSettlementDueOn(command.targetDate()))
                .toList();

        if (dueSellers.isEmpty()) {
            log.info("정산 대상 판매자 없음: targetDate={}", command.targetDate());
            return new CreateSettlementResult(command.targetDate(), 0, 0);
        }

        log.info("정산 대상 판매자 {}명", dueSellers.size());

        List<Settlement> allSaved = new ArrayList<>();
        int totalPayments = 0;

        // 2. 판매자별 결제 조회 + 정산 생성
        for (Seller seller : dueSellers) {
            List<CapturedPaymentInfo> payments =
                    loadCapturedPaymentsPort.findCapturedPaymentsByDateAndSeller(
                            command.targetDate(), seller.getId());

            if (payments.isEmpty()) continue;

            totalPayments += payments.size();

            for (CapturedPaymentInfo payment : payments) {
                Settlement settlement = Settlement.createFromPayment(
                        payment.paymentId(),
                        payment.orderId(),
                        seller.getId(),
                        payment.amount(),
                        seller.getCommissionRate(),
                        command.targetDate()
                );

                Settlement saved = saveSettlementPort.save(settlement);

                // 3. Ledger 분개 기록
                try {
                    recordJournalEntryUseCase.recordSettlementCreated(
                            saved.getId(),
                            seller.getId(),
                            Money.krw(saved.getPaymentAmount()),
                            Money.krw(saved.getCommission())
                    );
                } catch (Exception e) {
                    log.error("Ledger 분개 실패 (정산 생성은 성공): settlementId={}", saved.getId(), e);
                }

                allSaved.add(saved);

                log.debug("정산 생성: sellerId={}, paymentId={}, amount={}, commission={}, net={}",
                        seller.getId(), payment.paymentId(), payment.amount(),
                        saved.getCommission(), saved.getNetAmount());
            }
        }

        log.info("정산 {}건 저장 완료 (결제 {}건)", allSaved.size(), totalPayments);

        // 4. Elasticsearch 비동기 인덱싱
        if (settlementSearchIndexPort.isSearchEnabled() && !allSaved.isEmpty()) {
            try {
                settlementSearchIndexPort.bulkIndexSettlements(allSaved);
            } catch (Exception e) {
                log.error("Elasticsearch 인덱싱 실패: targetDate={}", command.targetDate(), e);
            }
        }

        return new CreateSettlementResult(command.targetDate(), totalPayments, allSaved.size());
    }
}
```

- [ ] **Step 4: Fix existing tests**

The `CreateDailySettlementsServiceLedgerTest` needs the new `@Mock LoadSellerPort loadSellerPort;` field. Also, the test needs to provide seller data. Update `CreateDailySettlementsServiceLedgerTest.java`:

Add mock field:
```java
    @Mock
    private LoadSellerPort loadSellerPort;
```

Add imports:
```java
import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;
```

Update the test method `정산_생성_시_레저_분개()` to set up seller mock:

```java
    @Test
    @DisplayName("정산 생성 시 Ledger 분개도 함께 기록된다")
    void 정산_생성_시_레저_분개() {
        LocalDate targetDate = LocalDate.of(2026, 4, 25);

        Seller seller = Seller.create(1L, "테스트상점", "1234567890", "홍길동", "010-1234-5678", "test@test.com");
        seller.setId(42L);
        seller.approve();

        CapturedPaymentInfo payment = new CapturedPaymentInfo(
                1L, 100L, 42L, new BigDecimal("10000"), LocalDateTime.now());

        given(loadSellerPort.findByStatus(SellerStatus.APPROVED)).willReturn(List.of(seller));
        given(loadCapturedPaymentsPort.findCapturedPaymentsByDateAndSeller(targetDate, 42L))
                .willReturn(List.of(payment));
        given(saveSettlementPort.save(any())).willAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        given(settlementSearchIndexPort.isSearchEnabled()).willReturn(false);

        service.createDailySettlements(new CreateSettlementCommand(targetDate));

        verify(recordJournalEntryUseCase).recordSettlementCreated(
                eq(1L),
                eq(42L),
                any(Money.class),
                any(Money.class)
        );
    }
```

- [ ] **Step 5: Run all tests**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsService.java \
        src/test/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsServiceSellerTest.java \
        src/test/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsServiceLedgerTest.java
git commit -m "feat(settlement): refactor settlement creation to be seller-aware with per-seller commission"
```

---

### Task 7: Full Test Suite + Build Verification

- [ ] **Step 1: Run full test suite**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --info 2>&1 | tail -30`
Expected: All tests PASS

- [ ] **Step 2: Verify build**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew build 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Final commit if any pending changes**

```bash
git status
```
