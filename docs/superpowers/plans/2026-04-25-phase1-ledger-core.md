# Phase 1: Double-Entry Ledger Core Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a double-entry bookkeeping ledger that records all settlement financial movements as balanced journal entries (debit sum == credit sum), with a Money value object, account balance calculation via snapshot+delta, and integration with the existing settlement creation flow.

**Architecture:** Hexagonal architecture following existing patterns (Domain POJO + JPA Entity + MapStruct + Port/Adapter). The Ledger is a new Bounded Context (`ledger` package) with its own domain, ports, adapters. It integrates with the Settlement context via an application-level orchestrator that calls both Settlement and Ledger ports within the same transaction.

**Tech Stack:** Java 21, Spring Boot 3.5.10, PostgreSQL, Spring Data JPA, QueryDSL, MapStruct, Flyway, JUnit5 + H2

**Spec Reference:** `docs/superpowers/specs/2026-04-24-ledger-settlement-withdrawal-design.md` Sections 4 and 10

---

## File Structure

### New Files

```
src/main/java/github/lms/lemuel/ledger/
  domain/
    Money.java                           — Value Object: amount + currency, arithmetic ops
    Currency.java                        — Enum: KRW (default)
    DebitCredit.java                     — Enum: DEBIT, CREDIT
    AccountType.java                     — Enum: ASSET, LIABILITY, REVENUE, EXPENSE
    Account.java                         — Domain entity: chart of accounts
    LedgerLine.java                      — Domain entity: single debit/credit line
    JournalEntry.java                    — Aggregate Root: balanced set of LedgerLines
    exception/
      UnbalancedJournalEntryException.java
      AccountNotFoundException.java
      DuplicateJournalEntryException.java
  application/
    port/in/
      RecordJournalEntryUseCase.java     — Inbound port: record a new journal entry
      GetAccountBalanceUseCase.java      — Inbound port: query account balance
      GetTrialBalanceUseCase.java        — Inbound port: trial balance report
    port/out/
      SaveJournalEntryPort.java          — Outbound port: persist journal entries
      LoadJournalEntryPort.java          — Outbound port: load journal entries
      LoadAccountPort.java               — Outbound port: load/create accounts
      SaveAccountBalanceSnapshotPort.java — Outbound port: snapshot persistence
      LoadAccountBalancePort.java        — Outbound port: balance queries
    service/
      LedgerService.java                 — UseCase implementation: record entries
      AccountBalanceService.java         — UseCase implementation: balance queries
  adapter/
    out/persistence/
      AccountJpaEntity.java              — JPA entity for accounts table
      JournalEntryJpaEntity.java         — JPA entity for journal_entries table
      LedgerLineJpaEntity.java           — JPA entity for ledger_lines table
      AccountBalanceSnapshotJpaEntity.java — JPA entity for snapshots
      SpringDataAccountJpaRepository.java
      SpringDataJournalEntryJpaRepository.java
      SpringDataLedgerLineJpaRepository.java
      SpringDataAccountBalanceSnapshotJpaRepository.java
      LedgerPersistenceAdapter.java      — Implements Save/Load ports
      AccountPersistenceAdapter.java     — Implements account ports
      AccountBalancePersistenceAdapter.java — Implements balance ports
      LedgerPersistenceMapper.java       — MapStruct mapper

src/main/resources/db/migration/
  V35__create_ledger_tables.sql          — accounts, journal_entries, ledger_lines, snapshots

src/test/java/github/lms/lemuel/ledger/
  domain/
    MoneyTest.java
    JournalEntryTest.java
    AccountTest.java
  application/service/
    LedgerServiceTest.java
    AccountBalanceServiceTest.java
  adapter/out/persistence/
    LedgerPersistenceAdapterTest.java
```

### Modified Files

```
src/main/java/github/lms/lemuel/settlement/application/service/
  CreateDailySettlementsService.java     — Add ledger journal entry recording after settlement creation
```

---

## Chunk 1: Domain Layer (Money VO + Account + JournalEntry)

### Task 1: Money Value Object

**Files:**
- Create: `src/main/java/github/lms/lemuel/ledger/domain/Money.java`
- Create: `src/main/java/github/lms/lemuel/ledger/domain/Currency.java`
- Test: `src/test/java/github/lms/lemuel/ledger/domain/MoneyTest.java`

- [ ] **Step 1: Write Money tests**

```java
package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class MoneyTest {

    @Nested
    @DisplayName("생성")
    class Creation {
        @Test
        void 금액과_통화로_생성한다() {
            Money money = Money.of(new BigDecimal("10000"), Currency.KRW);
            assertThat(money.amount()).isEqualByComparingTo("10000.00");
            assertThat(money.currency()).isEqualTo(Currency.KRW);
        }

        @Test
        void KRW_기본_통화로_생성한다() {
            Money money = Money.krw(new BigDecimal("5000"));
            assertThat(money.currency()).isEqualTo(Currency.KRW);
            assertThat(money.amount()).isEqualByComparingTo("5000.00");
        }

        @Test
        void ZERO_상수는_0원이다() {
            assertThat(Money.ZERO.amount()).isEqualByComparingTo("0.00");
            assertThat(Money.ZERO.currency()).isEqualTo(Currency.KRW);
        }

        @Test
        void null_금액은_예외를_던진다() {
            assertThatThrownBy(() -> Money.of(null, Currency.KRW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 소수점_셋째자리는_반올림된다() {
            Money money = Money.krw(new BigDecimal("100.005"));
            assertThat(money.amount()).isEqualByComparingTo("100.01");
        }
    }

    @Nested
    @DisplayName("산술연산")
    class Arithmetic {
        @Test
        void 더하기() {
            Money a = Money.krw(new BigDecimal("10000"));
            Money b = Money.krw(new BigDecimal("5000"));
            assertThat(a.add(b).amount()).isEqualByComparingTo("15000.00");
        }

        @Test
        void 빼기() {
            Money a = Money.krw(new BigDecimal("10000"));
            Money b = Money.krw(new BigDecimal("3000"));
            assertThat(a.subtract(b).amount()).isEqualByComparingTo("7000.00");
        }

        @Test
        void 곱하기_비율() {
            Money money = Money.krw(new BigDecimal("10000"));
            Money result = money.multiply(new BigDecimal("0.03"));
            assertThat(result.amount()).isEqualByComparingTo("300.00");
        }

        @Test
        void 다른_통화_연산은_예외를_던진다() {
            Money krw = Money.krw(new BigDecimal("10000"));
            Money usd = Money.of(new BigDecimal("100"), Currency.USD);
            assertThatThrownBy(() -> krw.add(usd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currency");
        }
    }

    @Nested
    @DisplayName("비교")
    class Comparison {
        @Test
        void isGreaterThan() {
            assertThat(Money.krw(new BigDecimal("10000")).isGreaterThan(Money.krw(new BigDecimal("5000")))).isTrue();
            assertThat(Money.krw(new BigDecimal("5000")).isGreaterThan(Money.krw(new BigDecimal("10000")))).isFalse();
        }

        @Test
        void isPositive() {
            assertThat(Money.krw(new BigDecimal("100")).isPositive()).isTrue();
            assertThat(Money.ZERO.isPositive()).isFalse();
            assertThat(Money.krw(new BigDecimal("-100")).isPositive()).isFalse();
        }

        @Test
        void equals_동일_금액_동일_통화() {
            Money a = Money.krw(new BigDecimal("10000.00"));
            Money b = Money.krw(new BigDecimal("10000"));
            assertThat(a).isEqualTo(b);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.domain.MoneyTest" --info 2>&1 | tail -20`
Expected: Compilation error — Money class does not exist

- [ ] **Step 3: Create Currency enum**

```java
package github.lms.lemuel.ledger.domain;

public enum Currency {
    KRW,
    USD
}
```

- [ ] **Step 4: Implement Money**

```java
package github.lms.lemuel.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), Currency.KRW);

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money krw(BigDecimal amount) {
        return new Money(amount, Currency.KRW);
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

    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    private void requireSameCurrency(Money other) {
        if (this.currency != other.currency) {
            throw new IllegalArgumentException(
                    String.format("Currency mismatch: %s vs %s", this.currency, other.currency));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        return this.amount.compareTo(other.amount) == 0 && this.currency == other.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }
}
```

- [ ] **Step 5: Run tests and verify all pass**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.domain.MoneyTest" --info 2>&1 | tail -20`
Expected: All 10 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/github/lms/lemuel/ledger/domain/Money.java \
        src/main/java/github/lms/lemuel/ledger/domain/Currency.java \
        src/test/java/github/lms/lemuel/ledger/domain/MoneyTest.java
git commit -m "feat(ledger): add Money value object with currency support"
```

---

### Task 2: Account Domain + Enums

**Files:**
- Create: `src/main/java/github/lms/lemuel/ledger/domain/AccountType.java`
- Create: `src/main/java/github/lms/lemuel/ledger/domain/DebitCredit.java`
- Create: `src/main/java/github/lms/lemuel/ledger/domain/Account.java`
- Test: `src/test/java/github/lms/lemuel/ledger/domain/AccountTest.java`

- [ ] **Step 1: Write Account tests**

```java
package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AccountTest {

    @Test
    @DisplayName("계정을 코드와 이름, 타입으로 생성한다")
    void 계정_생성() {
        Account account = Account.create("PLATFORM_CASH", "플랫폼 보유 현금", AccountType.ASSET);
        assertThat(account.getCode()).isEqualTo("PLATFORM_CASH");
        assertThat(account.getName()).isEqualTo("플랫폼 보유 현금");
        assertThat(account.getType()).isEqualTo(AccountType.ASSET);
    }

    @Test
    @DisplayName("판매자별 동적 계정 코드를 생성한다")
    void 판매자_계정_코드() {
        Account account = Account.createSellerPayable(42L);
        assertThat(account.getCode()).isEqualTo("SELLER_PAYABLE:42");
        assertThat(account.getType()).isEqualTo(AccountType.LIABILITY);
    }

    @Test
    @DisplayName("빈 코드로 생성하면 예외가 발생한다")
    void 빈_코드_예외() {
        assertThatThrownBy(() -> Account.create("", "name", AccountType.ASSET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 타입으로 생성하면 예외가 발생한다")
    void null_타입_예외() {
        assertThatThrownBy(() -> Account.create("CODE", "name", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ASSET 계정은 DEBIT이 정상 방향이다")
    void ASSET_정상방향_DEBIT() {
        assertThat(AccountType.ASSET.normalSide()).isEqualTo(DebitCredit.DEBIT);
    }

    @Test
    @DisplayName("LIABILITY 계정은 CREDIT이 정상 방향이다")
    void LIABILITY_정상방향_CREDIT() {
        assertThat(AccountType.LIABILITY.normalSide()).isEqualTo(DebitCredit.CREDIT);
    }

    @Test
    @DisplayName("REVENUE 계정은 CREDIT이 정상 방향이다")
    void REVENUE_정상방향_CREDIT() {
        assertThat(AccountType.REVENUE.normalSide()).isEqualTo(DebitCredit.CREDIT);
    }

    @Test
    @DisplayName("EXPENSE 계정은 DEBIT이 정상 방향이다")
    void EXPENSE_정상방향_DEBIT() {
        assertThat(AccountType.EXPENSE.normalSide()).isEqualTo(DebitCredit.DEBIT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.domain.AccountTest" --info 2>&1 | tail -20`
Expected: Compilation error

- [ ] **Step 3: Implement DebitCredit enum**

```java
package github.lms.lemuel.ledger.domain;

public enum DebitCredit {
    DEBIT,
    CREDIT
}
```

- [ ] **Step 4: Implement AccountType enum**

```java
package github.lms.lemuel.ledger.domain;

public enum AccountType {
    ASSET(DebitCredit.DEBIT),
    LIABILITY(DebitCredit.CREDIT),
    REVENUE(DebitCredit.CREDIT),
    EXPENSE(DebitCredit.DEBIT);

    private final DebitCredit normalSide;

    AccountType(DebitCredit normalSide) {
        this.normalSide = normalSide;
    }

    public DebitCredit normalSide() {
        return normalSide;
    }
}
```

- [ ] **Step 5: Implement Account**

```java
package github.lms.lemuel.ledger.domain;

import java.time.LocalDateTime;

public class Account {

    private Long id;
    private String code;
    private String name;
    private AccountType type;
    private LocalDateTime createdAt;

    private Account() {}

    public static Account create(String code, String name, AccountType type) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Account code cannot be empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Account type cannot be null");
        }
        Account account = new Account();
        account.code = code;
        account.name = name;
        account.type = type;
        account.createdAt = LocalDateTime.now();
        return account;
    }

    public static Account createSellerPayable(Long sellerId) {
        return create("SELLER_PAYABLE:" + sellerId, "판매자 지급 의무 #" + sellerId, AccountType.LIABILITY);
    }

    public static Account createPlatformCash() {
        return create("PLATFORM_CASH", "플랫폼 보유 현금", AccountType.ASSET);
    }

    public static Account createPlatformCommission() {
        return create("PLATFORM_COMMISSION", "플랫폼 수수료 수익", AccountType.REVENUE);
    }

    // Reconstitution constructor for persistence
    public Account(Long id, String code, String name, AccountType type, LocalDateTime createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.type = type;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public AccountType getType() { return type; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 6: Run tests and verify all pass**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.domain.AccountTest" --info 2>&1 | tail -20`
Expected: All 8 tests PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/github/lms/lemuel/ledger/domain/AccountType.java \
        src/main/java/github/lms/lemuel/ledger/domain/DebitCredit.java \
        src/main/java/github/lms/lemuel/ledger/domain/Account.java \
        src/test/java/github/lms/lemuel/ledger/domain/AccountTest.java
git commit -m "feat(ledger): add Account domain with AccountType and DebitCredit enums"
```

---

### Task 3: LedgerLine + JournalEntry (Aggregate Root)

**Files:**
- Create: `src/main/java/github/lms/lemuel/ledger/domain/LedgerLine.java`
- Create: `src/main/java/github/lms/lemuel/ledger/domain/JournalEntry.java`
- Create: `src/main/java/github/lms/lemuel/ledger/domain/exception/UnbalancedJournalEntryException.java`
- Test: `src/test/java/github/lms/lemuel/ledger/domain/JournalEntryTest.java`

- [ ] **Step 1: Write JournalEntry tests**

```java
package github.lms.lemuel.ledger.domain;

import github.lms.lemuel.ledger.domain.exception.UnbalancedJournalEntryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class JournalEntryTest {

    private Account platformCash = new Account(1L, "PLATFORM_CASH", "플랫폼 현금", AccountType.ASSET, null);
    private Account sellerPayable = new Account(2L, "SELLER_PAYABLE:42", "판매자 지급", AccountType.LIABILITY, null);
    private Account commission = new Account(3L, "PLATFORM_COMMISSION", "수수료", AccountType.REVENUE, null);

    @Nested
    @DisplayName("정상 분개 생성")
    class ValidCreation {

        @Test
        @DisplayName("차변 합계 == 대변 합계이면 분개가 생성된다")
        void 균형_분개_생성() {
            Money amount = Money.krw(new BigDecimal("10000"));

            JournalEntry entry = JournalEntry.create(
                    "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                    List.of(
                            LedgerLine.debit(platformCash, amount),
                            LedgerLine.credit(sellerPayable, amount)
                    ),
                    "SETTLEMENT:1",
                    "정산 생성 분개"
            );

            assertThat(entry.getEntryType()).isEqualTo("SETTLEMENT_CREATED");
            assertThat(entry.getReferenceType()).isEqualTo("SETTLEMENT");
            assertThat(entry.getReferenceId()).isEqualTo(1L);
            assertThat(entry.getLines()).hasSize(2);
            assertThat(entry.getIdempotencyKey()).isEqualTo("SETTLEMENT:1");
        }

        @Test
        @DisplayName("3개 라인으로 균형 분개가 생성된다 (환불: 2 debit + 1 credit)")
        void 세개_라인_균형_분개() {
            Money refundTotal = Money.krw(new BigDecimal("3000"));
            Money sellerDeduction = Money.krw(new BigDecimal("2910"));
            Money commissionReversal = Money.krw(new BigDecimal("90"));

            JournalEntry entry = JournalEntry.create(
                    "REFUND_PROCESSED", "REFUND", 1L,
                    List.of(
                            LedgerLine.debit(sellerPayable, sellerDeduction),
                            LedgerLine.debit(commission, commissionReversal),
                            LedgerLine.credit(platformCash, refundTotal)
                    ),
                    "REFUND:1",
                    "환불 분개"
            );

            assertThat(entry.getLines()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("불균형 분개 거부")
    class InvalidCreation {

        @Test
        @DisplayName("차변 != 대변이면 UnbalancedJournalEntryException이 발생한다")
        void 불균형_예외() {
            Money debitAmount = Money.krw(new BigDecimal("10000"));
            Money creditAmount = Money.krw(new BigDecimal("9000"));

            assertThatThrownBy(() -> JournalEntry.create(
                    "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                    List.of(
                            LedgerLine.debit(platformCash, debitAmount),
                            LedgerLine.credit(sellerPayable, creditAmount)
                    ),
                    "SETTLEMENT:1",
                    "불균형"
            )).isInstanceOf(UnbalancedJournalEntryException.class);
        }

        @Test
        @DisplayName("라인이 2개 미만이면 예외가 발생한다")
        void 라인_최소_2개() {
            assertThatThrownBy(() -> JournalEntry.create(
                    "TEST", "TEST", 1L,
                    List.of(LedgerLine.debit(platformCash, Money.krw(new BigDecimal("100")))),
                    "TEST:1",
                    "단일 라인"
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("음수 금액 LedgerLine은 생성할 수 없다")
        void 음수_금액_거부() {
            assertThatThrownBy(() -> LedgerLine.debit(platformCash, Money.krw(new BigDecimal("-100"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("0원 LedgerLine은 생성할 수 없다")
        void 제로_금액_거부() {
            assertThatThrownBy(() -> LedgerLine.debit(platformCash, Money.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("합계 계산")
    class Totals {
        @Test
        @DisplayName("총 차변 합계를 계산한다")
        void 총_차변() {
            Money amount = Money.krw(new BigDecimal("10000"));
            JournalEntry entry = JournalEntry.create(
                    "TEST", "TEST", 1L,
                    List.of(
                            LedgerLine.debit(platformCash, amount),
                            LedgerLine.credit(sellerPayable, amount)
                    ),
                    "TEST:1", "test"
            );
            assertThat(entry.totalDebit()).isEqualTo(amount);
            assertThat(entry.totalCredit()).isEqualTo(amount);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.domain.JournalEntryTest" --info 2>&1 | tail -20`
Expected: Compilation error

- [ ] **Step 3: Implement UnbalancedJournalEntryException**

```java
package github.lms.lemuel.ledger.domain.exception;

import github.lms.lemuel.ledger.domain.Money;

public class UnbalancedJournalEntryException extends RuntimeException {
    private final Money totalDebit;
    private final Money totalCredit;

    public UnbalancedJournalEntryException(Money totalDebit, Money totalCredit) {
        super(String.format("Journal entry is unbalanced: debit=%s, credit=%s",
                totalDebit.amount(), totalCredit.amount()));
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
    }

    public Money getTotalDebit() { return totalDebit; }
    public Money getTotalCredit() { return totalCredit; }
}
```

- [ ] **Step 4: Implement LedgerLine**

```java
package github.lms.lemuel.ledger.domain;

import java.time.LocalDateTime;

public class LedgerLine {

    private Long id;
    private Account account;
    private DebitCredit side;
    private Money amount;
    private LocalDateTime createdAt;

    private LedgerLine() {}

    public static LedgerLine debit(Account account, Money amount) {
        return create(account, DebitCredit.DEBIT, amount);
    }

    public static LedgerLine credit(Account account, Money amount) {
        return create(account, DebitCredit.CREDIT, amount);
    }

    private static LedgerLine create(Account account, DebitCredit side, Money amount) {
        if (account == null) {
            throw new IllegalArgumentException("Account cannot be null");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("LedgerLine amount must be positive");
        }
        LedgerLine line = new LedgerLine();
        line.account = account;
        line.side = side;
        line.amount = amount;
        line.createdAt = LocalDateTime.now();
        return line;
    }

    // Reconstitution constructor
    public LedgerLine(Long id, Account account, DebitCredit side, Money amount, LocalDateTime createdAt) {
        this.id = id;
        this.account = account;
        this.side = side;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public DebitCredit getSide() { return side; }
    public Money getAmount() { return amount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: Implement JournalEntry**

```java
package github.lms.lemuel.ledger.domain;

import github.lms.lemuel.ledger.domain.exception.UnbalancedJournalEntryException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

public class JournalEntry {

    private Long id;
    private String entryType;
    private String referenceType;
    private Long referenceId;
    private List<LedgerLine> lines;
    private String description;
    private String idempotencyKey;
    private LocalDateTime createdAt;

    private JournalEntry() {}

    public static JournalEntry create(String entryType, String referenceType,
                                       Long referenceId, List<LedgerLine> lines,
                                       String idempotencyKey, String description) {
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("Journal entry must have at least 2 lines");
        }

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

        JournalEntry entry = new JournalEntry();
        entry.entryType = entryType;
        entry.referenceType = referenceType;
        entry.referenceId = referenceId;
        entry.lines = List.copyOf(lines);
        entry.idempotencyKey = idempotencyKey;
        entry.description = description;
        entry.createdAt = LocalDateTime.now();
        return entry;
    }

    public Money totalDebit() {
        return lines.stream()
                .filter(l -> l.getSide() == DebitCredit.DEBIT)
                .map(LedgerLine::getAmount)
                .reduce(Money.ZERO, Money::add);
    }

    public Money totalCredit() {
        return lines.stream()
                .filter(l -> l.getSide() == DebitCredit.CREDIT)
                .map(LedgerLine::getAmount)
                .reduce(Money.ZERO, Money::add);
    }

    // Reconstitution constructor
    public JournalEntry(Long id, String entryType, String referenceType, Long referenceId,
                         List<LedgerLine> lines, String description, String idempotencyKey,
                         LocalDateTime createdAt) {
        this.id = id;
        this.entryType = entryType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.lines = lines;
        this.description = description;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getEntryType() { return entryType; }
    public String getReferenceType() { return referenceType; }
    public Long getReferenceId() { return referenceId; }
    public List<LedgerLine> getLines() { return Collections.unmodifiableList(lines); }
    public String getDescription() { return description; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 6: Run tests and verify all pass**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.domain.JournalEntryTest" --info 2>&1 | tail -20`
Expected: All 7 tests PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/github/lms/lemuel/ledger/domain/LedgerLine.java \
        src/main/java/github/lms/lemuel/ledger/domain/JournalEntry.java \
        src/main/java/github/lms/lemuel/ledger/domain/exception/UnbalancedJournalEntryException.java \
        src/test/java/github/lms/lemuel/ledger/domain/JournalEntryTest.java
git commit -m "feat(ledger): add JournalEntry aggregate root with balanced entry invariant"
```

---

## Chunk 2: Persistence Layer (Flyway + JPA Entities + MapStruct + Adapters)

### Task 4: Flyway Migration

**Files:**
- Create: `src/main/resources/db/migration/V35__create_ledger_tables.sql`

- [ ] **Step 1: Create migration file**

```sql
-- V35: Double-Entry Ledger tables
-- accounts: Chart of Accounts
CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    type        VARCHAR(20) NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_type ON accounts(type);
CREATE INDEX idx_accounts_code ON accounts(code);

-- journal_entries: 분개 전표 (Aggregate Root)
CREATE TABLE journal_entries (
    id              BIGSERIAL PRIMARY KEY,
    entry_type      VARCHAR(50) NOT NULL,
    reference_type  VARCHAR(30) NOT NULL,
    reference_id    BIGINT NOT NULL,
    description     TEXT,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_journal_entries_reference ON journal_entries(reference_type, reference_id);
CREATE INDEX idx_journal_entries_entry_type ON journal_entries(entry_type);
CREATE INDEX idx_journal_entries_created_at ON journal_entries(created_at);

-- ledger_lines: 차변/대변 개별 라인
CREATE TABLE ledger_lines (
    id               BIGSERIAL PRIMARY KEY,
    journal_entry_id BIGINT NOT NULL REFERENCES journal_entries(id),
    account_id       BIGINT NOT NULL REFERENCES accounts(id),
    side             VARCHAR(6) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount           NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_lines_journal_entry ON ledger_lines(journal_entry_id);
CREATE INDEX idx_ledger_lines_account ON ledger_lines(account_id);
CREATE INDEX idx_ledger_lines_account_created ON ledger_lines(account_id, created_at);

-- account_balance_snapshots: 잔액 스냅샷 (snapshot + delta 패턴)
CREATE TABLE account_balance_snapshots (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT NOT NULL REFERENCES accounts(id),
    balance     NUMERIC(15,2) NOT NULL,
    snapshot_at DATE NOT NULL,
    CONSTRAINT uq_account_snapshot UNIQUE (account_id, snapshot_at)
);

CREATE INDEX idx_balance_snapshots_account ON account_balance_snapshots(account_id, snapshot_at DESC);

-- Seed: 기본 플랫폼 계정 생성
INSERT INTO accounts (code, name, type) VALUES
    ('PLATFORM_CASH', '플랫폼 보유 현금', 'ASSET'),
    ('PLATFORM_COMMISSION', '플랫폼 수수료 수익', 'REVENUE'),
    ('BANK_TRANSFER_PENDING', '은행 이체 진행중', 'ASSET'),
    ('REFUND_EXPENSE', '환불 비용', 'EXPENSE');
```

- [ ] **Step 2: Verify migration compiles with Flyway**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew flywayValidate 2>&1 | tail -10`
Expected: Validation success (or app context loads if no flywayValidate task — verify by `./gradlew build -x test 2>&1 | tail -10`)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V35__create_ledger_tables.sql
git commit -m "feat(ledger): add Flyway V35 migration for ledger tables"
```

---

### Task 5: JPA Entities

**Files:**
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/AccountJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/JournalEntryJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/LedgerLineJpaEntity.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/AccountBalanceSnapshotJpaEntity.java`

- [ ] **Step 1: Implement AccountJpaEntity**

```java
package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor
public class AccountJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AccountJpaEntity(String code, String name, String type) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.createdAt = LocalDateTime.now();
    }

    public AccountJpaEntity(Long id, String code, String name, String type, LocalDateTime createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.type = type;
        this.createdAt = createdAt;
    }
}
```

- [ ] **Step 2: Implement JournalEntryJpaEntity**

```java
package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "journal_entries")
@Getter
@NoArgsConstructor
public class JournalEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_type", nullable = false, length = 50)
    private String entryType;

    @Column(name = "reference_type", nullable = false, length = 30)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column
    private String description;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerLineJpaEntity> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public JournalEntryJpaEntity(String entryType, String referenceType, Long referenceId,
                                  String description, String idempotencyKey) {
        this.entryType = entryType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.description = description;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = LocalDateTime.now();
    }

    public void addLine(LedgerLineJpaEntity line) {
        lines.add(line);
        line.setJournalEntry(this);
    }
}
```

- [ ] **Step 3: Implement LedgerLineJpaEntity**

```java
package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ledger_lines")
@Getter
@NoArgsConstructor
public class LedgerLineJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    @Setter
    private JournalEntryJpaEntity journalEntry;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 6)
    private String side;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public LedgerLineJpaEntity(Long accountId, String side, BigDecimal amount) {
        this.accountId = accountId;
        this.side = side;
        this.amount = amount;
        this.createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 4: Implement AccountBalanceSnapshotJpaEntity**

```java
package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "account_balance_snapshots")
@Getter
@NoArgsConstructor
public class AccountBalanceSnapshotJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @Column(name = "snapshot_at", nullable = false)
    private LocalDate snapshotAt;

    public AccountBalanceSnapshotJpaEntity(Long accountId, BigDecimal balance, LocalDate snapshotAt) {
        this.accountId = accountId;
        this.balance = balance;
        this.snapshotAt = snapshotAt;
    }
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/AccountJpaEntity.java \
        src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/JournalEntryJpaEntity.java \
        src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/LedgerLineJpaEntity.java \
        src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/AccountBalanceSnapshotJpaEntity.java
git commit -m "feat(ledger): add JPA entities for ledger tables"
```

---

### Task 6: Spring Data Repositories + MapStruct Mapper

**Files:**
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/SpringDataAccountJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/SpringDataJournalEntryJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/SpringDataLedgerLineJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/SpringDataAccountBalanceSnapshotJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/LedgerPersistenceMapper.java`

- [ ] **Step 1: Implement repositories**

```java
// SpringDataAccountJpaRepository.java
package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SpringDataAccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {
    Optional<AccountJpaEntity> findByCode(String code);
    boolean existsByCode(String code);
}
```

```java
// SpringDataJournalEntryJpaRepository.java
package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SpringDataJournalEntryJpaRepository extends JpaRepository<JournalEntryJpaEntity, Long> {
    boolean existsByIdempotencyKey(String idempotencyKey);
    List<JournalEntryJpaEntity> findByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
}
```

```java
// SpringDataLedgerLineJpaRepository.java
package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface SpringDataLedgerLineJpaRepository extends JpaRepository<LedgerLineJpaEntity, Long> {

    @Query(nativeQuery = true, value = """
        SELECT COALESCE(SUM(CASE
            WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'DEBIT' THEN ll.amount
            WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'CREDIT' THEN -ll.amount
            WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'CREDIT' THEN ll.amount
            WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'DEBIT' THEN -ll.amount
            ELSE 0 END), 0)
        FROM ledger_lines ll
        JOIN accounts a ON a.id = ll.account_id
        WHERE ll.account_id = :accountId
        AND ll.created_at > :since
        """)
    BigDecimal calculateBalanceDeltaSince(@Param("accountId") Long accountId,
                                           @Param("since") LocalDateTime since);

    @Query(nativeQuery = true, value = """
        SELECT COALESCE(SUM(CASE
            WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'DEBIT' THEN ll.amount
            WHEN a.type IN ('ASSET','EXPENSE') AND ll.side = 'CREDIT' THEN -ll.amount
            WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'CREDIT' THEN ll.amount
            WHEN a.type IN ('LIABILITY','REVENUE') AND ll.side = 'DEBIT' THEN -ll.amount
            ELSE 0 END), 0)
        FROM ledger_lines ll
        JOIN accounts a ON a.id = ll.account_id
        WHERE ll.account_id = :accountId
        """)
    BigDecimal calculateFullBalance(@Param("accountId") Long accountId);
}
```

```java
// SpringDataAccountBalanceSnapshotJpaRepository.java
package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SpringDataAccountBalanceSnapshotJpaRepository
        extends JpaRepository<AccountBalanceSnapshotJpaEntity, Long> {

    Optional<AccountBalanceSnapshotJpaEntity> findTopByAccountIdOrderBySnapshotAtDesc(Long accountId);
}
```

- [ ] **Step 2: Implement MapStruct mapper**

```java
package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class LedgerPersistenceMapper {

    // Account mapping
    default Account toDomain(AccountJpaEntity entity) {
        if (entity == null) return null;
        return new Account(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                AccountType.valueOf(entity.getType()),
                entity.getCreatedAt()
        );
    }

    default AccountJpaEntity toJpaEntity(Account domain) {
        if (domain == null) return null;
        return new AccountJpaEntity(
                domain.getId(),
                domain.getCode(),
                domain.getName(),
                domain.getType().name(),
                domain.getCreatedAt()
        );
    }

    // JournalEntry → JpaEntity (lines added separately via addLine)
    default JournalEntryJpaEntity toJpaEntity(JournalEntry domain) {
        if (domain == null) return null;
        JournalEntryJpaEntity entity = new JournalEntryJpaEntity(
                domain.getEntryType(),
                domain.getReferenceType(),
                domain.getReferenceId(),
                domain.getDescription(),
                domain.getIdempotencyKey()
        );
        for (LedgerLine line : domain.getLines()) {
            LedgerLineJpaEntity lineEntity = new LedgerLineJpaEntity(
                    line.getAccount().getId(),
                    line.getSide().name(),
                    line.getAmount().amount()
            );
            entity.addLine(lineEntity);
        }
        return entity;
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/SpringData*.java \
        src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/LedgerPersistenceMapper.java
git commit -m "feat(ledger): add Spring Data repositories and MapStruct mapper"
```

---

### Task 7: Ports + Persistence Adapters

**Files:**
- Create: `src/main/java/github/lms/lemuel/ledger/application/port/out/SaveJournalEntryPort.java`
- Create: `src/main/java/github/lms/lemuel/ledger/application/port/out/LoadJournalEntryPort.java`
- Create: `src/main/java/github/lms/lemuel/ledger/application/port/out/LoadAccountPort.java`
- Create: `src/main/java/github/lms/lemuel/ledger/application/port/out/LoadAccountBalancePort.java`
- Create: `src/main/java/github/lms/lemuel/ledger/application/port/out/SaveAccountBalanceSnapshotPort.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/LedgerPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/AccountPersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/AccountBalancePersistenceAdapter.java`
- Create: `src/main/java/github/lms/lemuel/ledger/domain/exception/AccountNotFoundException.java`
- Create: `src/main/java/github/lms/lemuel/ledger/domain/exception/DuplicateJournalEntryException.java`

- [ ] **Step 1: Implement outbound ports**

```java
// SaveJournalEntryPort.java
package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.JournalEntry;

public interface SaveJournalEntryPort {
    JournalEntry save(JournalEntry journalEntry);
    boolean existsByIdempotencyKey(String idempotencyKey);
}
```

```java
// LoadJournalEntryPort.java
package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.JournalEntry;
import java.util.List;

public interface LoadJournalEntryPort {
    List<JournalEntry> findByReference(String referenceType, Long referenceId);
}
```

```java
// LoadAccountPort.java
package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.Account;
import java.util.Optional;

public interface LoadAccountPort {
    Optional<Account> findByCode(String code);
    Account getOrCreate(Account account);
}
```

```java
// LoadAccountBalancePort.java
package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.Money;

public interface LoadAccountBalancePort {
    Money getBalance(Long accountId);
}
```

```java
// SaveAccountBalanceSnapshotPort.java
package github.lms.lemuel.ledger.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface SaveAccountBalanceSnapshotPort {
    void saveSnapshot(Long accountId, BigDecimal balance, LocalDate snapshotAt);
}
```

- [ ] **Step 2: Implement domain exceptions**

```java
// AccountNotFoundException.java
package github.lms.lemuel.ledger.domain.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String code) {
        super("Account not found: " + code);
    }
}
```

```java
// DuplicateJournalEntryException.java
package github.lms.lemuel.ledger.domain.exception;

public class DuplicateJournalEntryException extends RuntimeException {
    public DuplicateJournalEntryException(String idempotencyKey) {
        super("Journal entry already exists with idempotency key: " + idempotencyKey);
    }
}
```

- [ ] **Step 3: Implement LedgerPersistenceAdapter**

```java
package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadJournalEntryPort;
import github.lms.lemuel.ledger.application.port.out.SaveJournalEntryPort;
import github.lms.lemuel.ledger.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class LedgerPersistenceAdapter implements SaveJournalEntryPort, LoadJournalEntryPort {

    private final SpringDataJournalEntryJpaRepository journalEntryRepository;
    private final SpringDataAccountJpaRepository accountRepository;
    private final LedgerPersistenceMapper mapper;

    @Override
    public JournalEntry save(JournalEntry journalEntry) {
        JournalEntryJpaEntity entity = mapper.toJpaEntity(journalEntry);
        JournalEntryJpaEntity saved = journalEntryRepository.save(entity);

        // Reconstitute domain with generated IDs
        List<LedgerLine> lines = saved.getLines().stream()
                .map(lineEntity -> {
                    AccountJpaEntity accountEntity = accountRepository.findById(lineEntity.getAccountId())
                            .orElseThrow();
                    Account account = mapper.toDomain(accountEntity);
                    return new LedgerLine(
                            lineEntity.getId(),
                            account,
                            DebitCredit.valueOf(lineEntity.getSide()),
                            Money.krw(lineEntity.getAmount()),
                            lineEntity.getCreatedAt()
                    );
                })
                .toList();

        return new JournalEntry(
                saved.getId(),
                saved.getEntryType(),
                saved.getReferenceType(),
                saved.getReferenceId(),
                lines,
                saved.getDescription(),
                saved.getIdempotencyKey(),
                saved.getCreatedAt()
        );
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return journalEntryRepository.existsByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<JournalEntry> findByReference(String referenceType, Long referenceId) {
        return journalEntryRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId)
                .stream()
                .map(entity -> {
                    List<LedgerLine> lines = entity.getLines().stream()
                            .map(lineEntity -> {
                                AccountJpaEntity accountEntity = accountRepository.findById(lineEntity.getAccountId())
                                        .orElseThrow();
                                return new LedgerLine(
                                        lineEntity.getId(),
                                        mapper.toDomain(accountEntity),
                                        DebitCredit.valueOf(lineEntity.getSide()),
                                        Money.krw(lineEntity.getAmount()),
                                        lineEntity.getCreatedAt()
                                );
                            })
                            .toList();
                    return new JournalEntry(
                            entity.getId(), entity.getEntryType(), entity.getReferenceType(),
                            entity.getReferenceId(), lines, entity.getDescription(),
                            entity.getIdempotencyKey(), entity.getCreatedAt()
                    );
                })
                .toList();
    }
}
```

- [ ] **Step 4: Implement AccountPersistenceAdapter**

```java
package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.domain.Account;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AccountPersistenceAdapter implements LoadAccountPort {

    private final SpringDataAccountJpaRepository accountRepository;
    private final LedgerPersistenceMapper mapper;

    @Override
    public Optional<Account> findByCode(String code) {
        return accountRepository.findByCode(code).map(mapper::toDomain);
    }

    @Override
    public Account getOrCreate(Account account) {
        return accountRepository.findByCode(account.getCode())
                .map(mapper::toDomain)
                .orElseGet(() -> {
                    AccountJpaEntity entity = new AccountJpaEntity(
                            account.getCode(), account.getName(), account.getType().name());
                    AccountJpaEntity saved = accountRepository.save(entity);
                    return mapper.toDomain(saved);
                });
    }
}
```

- [ ] **Step 5: Implement AccountBalancePersistenceAdapter**

```java
package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadAccountBalancePort;
import github.lms.lemuel.ledger.application.port.out.SaveAccountBalanceSnapshotPort;
import github.lms.lemuel.ledger.domain.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Repository
@RequiredArgsConstructor
public class AccountBalancePersistenceAdapter implements LoadAccountBalancePort, SaveAccountBalanceSnapshotPort {

    private final SpringDataLedgerLineJpaRepository ledgerLineRepository;
    private final SpringDataAccountBalanceSnapshotJpaRepository snapshotRepository;

    @Override
    public Money getBalance(Long accountId) {
        // Snapshot + delta approach
        var latestSnapshot = snapshotRepository.findTopByAccountIdOrderBySnapshotAtDesc(accountId);

        if (latestSnapshot.isPresent()) {
            var snapshot = latestSnapshot.get();
            BigDecimal delta = ledgerLineRepository.calculateBalanceDeltaSince(
                    accountId, snapshot.getSnapshotAt().atStartOfDay());
            return Money.krw(snapshot.getBalance().add(delta));
        }

        // No snapshot: full calculation
        BigDecimal fullBalance = ledgerLineRepository.calculateFullBalance(accountId);
        return Money.krw(fullBalance);
    }

    @Override
    public void saveSnapshot(Long accountId, BigDecimal balance, LocalDate snapshotAt) {
        AccountBalanceSnapshotJpaEntity entity =
                new AccountBalanceSnapshotJpaEntity(accountId, balance, snapshotAt);
        snapshotRepository.save(entity);
    }
}
```

- [ ] **Step 6: Verify compilation**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/github/lms/lemuel/ledger/application/port/ \
        src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/LedgerPersistenceAdapter.java \
        src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/AccountPersistenceAdapter.java \
        src/main/java/github/lms/lemuel/ledger/adapter/out/persistence/AccountBalancePersistenceAdapter.java \
        src/main/java/github/lms/lemuel/ledger/domain/exception/
git commit -m "feat(ledger): add ports, persistence adapters, and domain exceptions"
```

---

## Chunk 3: Application Services + Integration with Settlement

### Task 8: LedgerService (RecordJournalEntryUseCase)

**Files:**
- Create: `src/main/java/github/lms/lemuel/ledger/application/port/in/RecordJournalEntryUseCase.java`
- Create: `src/main/java/github/lms/lemuel/ledger/application/service/LedgerService.java`
- Test: `src/test/java/github/lms/lemuel/ledger/application/service/LedgerServiceTest.java`

- [ ] **Step 1: Write LedgerService tests**

```java
package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.application.port.out.SaveJournalEntryPort;
import github.lms.lemuel.ledger.domain.*;
import github.lms.lemuel.ledger.domain.exception.DuplicateJournalEntryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private SaveJournalEntryPort saveJournalEntryPort;

    @Mock
    private LoadAccountPort loadAccountPort;

    @InjectMocks
    private LedgerService ledgerService;

    private final Account platformCash = new Account(1L, "PLATFORM_CASH", "현금", AccountType.ASSET, null);
    private final Account sellerPayable = new Account(2L, "SELLER_PAYABLE:42", "판매자", AccountType.LIABILITY, null);
    private final Account commission = new Account(3L, "PLATFORM_COMMISSION", "수수료", AccountType.REVENUE, null);

    @Test
    @DisplayName("정산 생성 분개를 기록한다")
    void 정산_분개_기록() {
        given(saveJournalEntryPort.existsByIdempotencyKey(any())).willReturn(false);
        given(loadAccountPort.getOrCreate(any())).willAnswer(inv -> inv.getArgument(0));
        given(saveJournalEntryPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        Money amount = Money.krw(new BigDecimal("10000"));
        JournalEntry entry = JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "SETTLEMENT:1", "정산 생성"
        );

        JournalEntry result = ledgerService.recordJournalEntry(entry);

        verify(saveJournalEntryPort).save(any());
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("중복 idempotencyKey는 DuplicateJournalEntryException을 던진다")
    void 중복_멱등키_예외() {
        given(saveJournalEntryPort.existsByIdempotencyKey("SETTLEMENT:1")).willReturn(true);

        Money amount = Money.krw(new BigDecimal("10000"));
        JournalEntry entry = JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "SETTLEMENT:1", "정산 생성"
        );

        assertThatThrownBy(() -> ledgerService.recordJournalEntry(entry))
                .isInstanceOf(DuplicateJournalEntryException.class);

        verify(saveJournalEntryPort, never()).save(any());
    }

    @Test
    @DisplayName("정산+수수료 분개를 한 번에 기록한다")
    void 정산_수수료_분개() {
        given(saveJournalEntryPort.existsByIdempotencyKey(any())).willReturn(false);
        given(loadAccountPort.getOrCreate(any())).willAnswer(inv -> inv.getArgument(0));
        given(saveJournalEntryPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        Money paymentAmount = Money.krw(new BigDecimal("10000"));
        Money commissionAmount = Money.krw(new BigDecimal("300"));
        Money netAmount = Money.krw(new BigDecimal("9700"));

        ledgerService.recordSettlementCreated(1L, 42L, paymentAmount, commissionAmount);

        // 2번 호출: 정산 분개 + 수수료 분개
        verify(saveJournalEntryPort, times(2)).save(any());
    }

    @Test
    @DisplayName("환불 분개를 기록한다 (3-line: 판매자 차감 + 수수료 역산 + 현금 유출)")
    void 환불_분개_기록() {
        given(saveJournalEntryPort.existsByIdempotencyKey(any())).willReturn(false);
        given(loadAccountPort.getOrCreate(any())).willAnswer(inv -> inv.getArgument(0));
        given(saveJournalEntryPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        Money refundAmount = Money.krw(new BigDecimal("3000"));
        Money commissionReversal = Money.krw(new BigDecimal("90"));

        ledgerService.recordRefundProcessed(1L, 42L, refundAmount, commissionReversal);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(saveJournalEntryPort).save(captor.capture());

        JournalEntry entry = captor.getValue();
        assertThat(entry.getLines()).hasSize(3);
        assertThat(entry.getEntryType()).isEqualTo("REFUND_PROCESSED");
        assertThat(entry.totalDebit()).isEqualTo(refundAmount);
        assertThat(entry.totalCredit()).isEqualTo(refundAmount);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.application.service.LedgerServiceTest" --info 2>&1 | tail -20`
Expected: Compilation error

- [ ] **Step 3: Implement RecordJournalEntryUseCase**

```java
package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.JournalEntry;
import github.lms.lemuel.ledger.domain.Money;

public interface RecordJournalEntryUseCase {
    JournalEntry recordJournalEntry(JournalEntry entry);
    void recordSettlementCreated(Long settlementId, Long sellerId, Money paymentAmount, Money commissionAmount);
    void recordRefundProcessed(Long refundId, Long sellerId, Money refundAmount, Money commissionReversal);
}
```

- [ ] **Step 4: Implement LedgerService**

```java
package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.application.port.out.SaveJournalEntryPort;
import github.lms.lemuel.ledger.domain.*;
import github.lms.lemuel.ledger.domain.exception.DuplicateJournalEntryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class LedgerService implements RecordJournalEntryUseCase {

    private final SaveJournalEntryPort saveJournalEntryPort;
    private final LoadAccountPort loadAccountPort;

    @Override
    public JournalEntry recordJournalEntry(JournalEntry entry) {
        if (saveJournalEntryPort.existsByIdempotencyKey(entry.getIdempotencyKey())) {
            throw new DuplicateJournalEntryException(entry.getIdempotencyKey());
        }
        JournalEntry saved = saveJournalEntryPort.save(entry);
        log.info("분개 기록 완료: type={}, ref={}:{}, idempotencyKey={}",
                saved.getEntryType(), saved.getReferenceType(), saved.getReferenceId(),
                saved.getIdempotencyKey());
        return saved;
    }

    @Override
    public void recordSettlementCreated(Long settlementId, Long sellerId,
                                         Money paymentAmount, Money commissionAmount) {
        Account platformCash = loadAccountPort.getOrCreate(Account.createPlatformCash());
        Account sellerPayable = loadAccountPort.getOrCreate(Account.createSellerPayable(sellerId));
        Account platformCommission = loadAccountPort.getOrCreate(Account.createPlatformCommission());

        // 1. 정산 생성 분개: 현금 유입 + 판매자 지급 의무
        recordJournalEntry(JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", settlementId,
                List.of(
                        LedgerLine.debit(platformCash, paymentAmount),
                        LedgerLine.credit(sellerPayable, paymentAmount)
                ),
                "SETTLEMENT_CREATED:" + settlementId,
                "결제 캡처 → 정산 생성"
        ));

        // 2. 수수료 차감 분개: 판매자 지급액에서 수수료 차감
        recordJournalEntry(JournalEntry.create(
                "COMMISSION_DEDUCTED", "SETTLEMENT", settlementId,
                List.of(
                        LedgerLine.debit(sellerPayable, commissionAmount),
                        LedgerLine.credit(platformCommission, commissionAmount)
                ),
                "COMMISSION_DEDUCTED:" + settlementId,
                "수수료 차감"
        ));

        log.info("정산 Ledger 분개 완료: settlementId={}, payment={}, commission={}",
                settlementId, paymentAmount, commissionAmount);
    }

    @Override
    public void recordRefundProcessed(Long refundId, Long sellerId,
                                       Money refundAmount, Money commissionReversal) {
        Account platformCash = loadAccountPort.getOrCreate(Account.createPlatformCash());
        Account sellerPayable = loadAccountPort.getOrCreate(Account.createSellerPayable(sellerId));
        Account platformCommission = loadAccountPort.getOrCreate(Account.createPlatformCommission());

        Money sellerDeduction = refundAmount.subtract(commissionReversal);

        recordJournalEntry(JournalEntry.create(
                "REFUND_PROCESSED", "REFUND", refundId,
                List.of(
                        LedgerLine.debit(sellerPayable, sellerDeduction),
                        LedgerLine.debit(platformCommission, commissionReversal),
                        LedgerLine.credit(platformCash, refundAmount)
                ),
                "REFUND_PROCESSED:" + refundId,
                "환불 처리 (수수료 비례 역산 포함)"
        ));

        log.info("환불 Ledger 분개 완료: refundId={}, refundAmount={}, commissionReversal={}",
                refundId, refundAmount, commissionReversal);
    }
}
```

- [ ] **Step 5: Run tests and verify all pass**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.application.service.LedgerServiceTest" --info 2>&1 | tail -20`
Expected: All 4 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/github/lms/lemuel/ledger/application/port/in/RecordJournalEntryUseCase.java \
        src/main/java/github/lms/lemuel/ledger/application/service/LedgerService.java \
        src/test/java/github/lms/lemuel/ledger/application/service/LedgerServiceTest.java
git commit -m "feat(ledger): add LedgerService with settlement and refund journal entry recording"
```

---

### Task 9: AccountBalanceService (GetAccountBalanceUseCase + GetTrialBalanceUseCase)

**Files:**
- Create: `src/main/java/github/lms/lemuel/ledger/application/port/in/GetAccountBalanceUseCase.java`
- Create: `src/main/java/github/lms/lemuel/ledger/application/port/in/GetTrialBalanceUseCase.java`
- Create: `src/main/java/github/lms/lemuel/ledger/application/service/AccountBalanceService.java`
- Test: `src/test/java/github/lms/lemuel/ledger/application/service/AccountBalanceServiceTest.java`

- [ ] **Step 1: Write AccountBalanceService tests**

```java
package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadAccountBalancePort;
import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.domain.*;
import github.lms.lemuel.ledger.domain.exception.AccountNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AccountBalanceServiceTest {

    @Mock
    private LoadAccountPort loadAccountPort;

    @Mock
    private LoadAccountBalancePort loadAccountBalancePort;

    @InjectMocks
    private AccountBalanceService accountBalanceService;

    @Test
    @DisplayName("계정 코드로 잔액을 조회한다")
    void 잔액_조회() {
        Account account = new Account(1L, "SELLER_PAYABLE:42", "판매자", AccountType.LIABILITY, null);
        given(loadAccountPort.findByCode("SELLER_PAYABLE:42")).willReturn(Optional.of(account));
        given(loadAccountBalancePort.getBalance(1L)).willReturn(Money.krw(new BigDecimal("9700")));

        Money balance = accountBalanceService.getBalance("SELLER_PAYABLE:42");

        assertThat(balance.amount()).isEqualByComparingTo("9700.00");
    }

    @Test
    @DisplayName("존재하지 않는 계정 코드는 예외를 던진다")
    void 존재하지_않는_계정() {
        given(loadAccountPort.findByCode("NONEXISTENT")).willReturn(Optional.empty());

        assertThatThrownBy(() -> accountBalanceService.getBalance("NONEXISTENT"))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.application.service.AccountBalanceServiceTest" --info 2>&1 | tail -20`
Expected: Compilation error

- [ ] **Step 3: Implement ports**

```java
// GetAccountBalanceUseCase.java
package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.Money;

public interface GetAccountBalanceUseCase {
    Money getBalance(String accountCode);
}
```

```java
// GetTrialBalanceUseCase.java
package github.lms.lemuel.ledger.application.port.in;

import java.util.List;

public interface GetTrialBalanceUseCase {
    record TrialBalanceEntry(String accountCode, String accountType,
                              java.math.BigDecimal debit, java.math.BigDecimal credit,
                              java.math.BigDecimal balance) {}
    List<TrialBalanceEntry> getTrialBalance();
}
```

- [ ] **Step 4: Implement AccountBalanceService**

```java
package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.GetAccountBalanceUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadAccountBalancePort;
import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.domain.Account;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.ledger.domain.exception.AccountNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountBalanceService implements GetAccountBalanceUseCase {

    private final LoadAccountPort loadAccountPort;
    private final LoadAccountBalancePort loadAccountBalancePort;

    @Override
    public Money getBalance(String accountCode) {
        Account account = loadAccountPort.findByCode(accountCode)
                .orElseThrow(() -> new AccountNotFoundException(accountCode));
        return loadAccountBalancePort.getBalance(account.getId());
    }
}
```

- [ ] **Step 5: Run tests and verify all pass**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.application.service.AccountBalanceServiceTest" --info 2>&1 | tail -20`
Expected: All 2 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/github/lms/lemuel/ledger/application/port/in/GetAccountBalanceUseCase.java \
        src/main/java/github/lms/lemuel/ledger/application/port/in/GetTrialBalanceUseCase.java \
        src/main/java/github/lms/lemuel/ledger/application/service/AccountBalanceService.java \
        src/test/java/github/lms/lemuel/ledger/application/service/AccountBalanceServiceTest.java
git commit -m "feat(ledger): add AccountBalanceService with snapshot+delta balance query"
```

---

### Task 10: Integration — CreateDailySettlementsService + Ledger

**Files:**
- Modify: `src/main/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsService.java`
- Test: `src/test/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsServiceLedgerTest.java`

- [ ] **Step 1: Write integration test**

```java
package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementCommand;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementResult;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateDailySettlementsServiceLedgerTest {

    @Mock
    private LoadCapturedPaymentsPort loadCapturedPaymentsPort;

    @Mock
    private SaveSettlementPort saveSettlementPort;

    @Mock
    private SettlementSearchIndexPort settlementSearchIndexPort;

    @Mock
    private RecordJournalEntryUseCase recordJournalEntryUseCase;

    @InjectMocks
    private CreateDailySettlementsService service;

    @Test
    @DisplayName("정산 생성 시 Ledger 분개도 함께 기록된다")
    void 정산_생성_시_레저_분개() {
        LocalDate targetDate = LocalDate.of(2026, 4, 25);
        CapturedPaymentInfo payment = new CapturedPaymentInfo(
                1L, 100L, new BigDecimal("10000"), LocalDateTime.now());

        given(loadCapturedPaymentsPort.findCapturedPaymentsByDate(targetDate))
                .willReturn(List.of(payment));
        given(saveSettlementPort.save(any())).willAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        given(settlementSearchIndexPort.isSearchEnabled()).willReturn(false);

        service.createDailySettlements(new CreateSettlementCommand(targetDate));

        verify(recordJournalEntryUseCase).recordSettlementCreated(
                eq(1L),                                         // settlementId
                anyLong(),                                      // sellerId (0L for now)
                any(Money.class),                               // paymentAmount
                any(Money.class)                                // commission
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.settlement.application.service.CreateDailySettlementsServiceLedgerTest" --info 2>&1 | tail -20`
Expected: FAIL — CreateDailySettlementsService has no RecordJournalEntryUseCase dependency

- [ ] **Step 3: Modify CreateDailySettlementsService to integrate Ledger**

Add `RecordJournalEntryUseCase` dependency and call it after each settlement is saved.

In `CreateDailySettlementsService.java`, add the new dependency and ledger recording after save:

```java
// Add to fields:
private final RecordJournalEntryUseCase recordJournalEntryUseCase;

// After saveSettlementPort.save(settlement) in the stream, add:
try {
    recordJournalEntryUseCase.recordSettlementCreated(
            savedSettlement.getId(),
            0L, // TODO: Phase 2에서 sellerId 연결
            Money.krw(savedSettlement.getPaymentAmount()),
            Money.krw(savedSettlement.getCommission())
    );
} catch (Exception e) {
    log.error("Ledger 분개 실패 (정산 생성은 성공): settlementId={}", savedSettlement.getId(), e);
}
```

- [ ] **Step 4: Run tests and verify all pass**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.settlement.application.service.*" --info 2>&1 | tail -20`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsService.java \
        src/test/java/github/lms/lemuel/settlement/application/service/CreateDailySettlementsServiceLedgerTest.java
git commit -m "feat(ledger): integrate Ledger journal entry recording into settlement creation flow"
```

---

## Chunk 4: Persistence Integration Test

### Task 11: Ledger Persistence Integration Test

**Files:**
- Test: `src/test/java/github/lms/lemuel/ledger/adapter/out/persistence/LedgerPersistenceAdapterTest.java`

- [ ] **Step 1: Write integration test**

```java
package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@Import({LedgerPersistenceAdapter.class, AccountPersistenceAdapter.class,
         AccountBalancePersistenceAdapter.class, LedgerPersistenceMapper.class})
@EntityScan(basePackages = "github.lms.lemuel.ledger.adapter.out.persistence")
@EnableJpaRepositories(basePackages = "github.lms.lemuel.ledger.adapter.out.persistence")
class LedgerPersistenceAdapterTest {

    @Autowired
    private LedgerPersistenceAdapter ledgerAdapter;

    @Autowired
    private AccountPersistenceAdapter accountAdapter;

    @Autowired
    private AccountBalancePersistenceAdapter balanceAdapter;

    @Autowired
    private SpringDataAccountJpaRepository accountRepository;

    private Account platformCash;
    private Account sellerPayable;

    @BeforeEach
    void setUp() {
        platformCash = accountAdapter.getOrCreate(Account.createPlatformCash());
        sellerPayable = accountAdapter.getOrCreate(Account.createSellerPayable(42L));
    }

    @Test
    @DisplayName("JournalEntry를 저장하고 ID가 생성된다")
    void 분개_저장() {
        Money amount = Money.krw(new BigDecimal("10000"));
        JournalEntry entry = JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "TEST:1", "테스트"
        );

        JournalEntry saved = ledgerAdapter.save(entry);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getLines()).hasSize(2);
        assertThat(saved.getLines().get(0).getId()).isNotNull();
    }

    @Test
    @DisplayName("idempotencyKey 중복 여부를 확인한다")
    void 멱등키_확인() {
        Money amount = Money.krw(new BigDecimal("10000"));
        JournalEntry entry = JournalEntry.create(
                "TEST", "TEST", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "IDEM:1", "test"
        );
        ledgerAdapter.save(entry);

        assertThat(ledgerAdapter.existsByIdempotencyKey("IDEM:1")).isTrue();
        assertThat(ledgerAdapter.existsByIdempotencyKey("IDEM:2")).isFalse();
    }

    @Test
    @DisplayName("referenceType과 referenceId로 분개를 조회한다")
    void 참조_조회() {
        Money amount = Money.krw(new BigDecimal("10000"));
        ledgerAdapter.save(JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 99L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "REF_TEST:1", "test"
        ));

        List<JournalEntry> entries = ledgerAdapter.findByReference("SETTLEMENT", 99L);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getEntryType()).isEqualTo("SETTLEMENT_CREATED");
    }

    @Test
    @DisplayName("계정 잔액을 정확하게 계산한다")
    void 잔액_계산() {
        Money amount = Money.krw(new BigDecimal("10000"));
        ledgerAdapter.save(JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", 1L,
                List.of(
                        LedgerLine.debit(platformCash, amount),
                        LedgerLine.credit(sellerPayable, amount)
                ),
                "BALANCE_TEST:1", "test"
        ));

        // PLATFORM_CASH (ASSET): debit increases → balance = +10000
        Money cashBalance = balanceAdapter.getBalance(platformCash.getId());
        assertThat(cashBalance.amount()).isEqualByComparingTo("10000.00");

        // SELLER_PAYABLE (LIABILITY): credit increases → balance = +10000
        Money payableBalance = balanceAdapter.getBalance(sellerPayable.getId());
        assertThat(payableBalance.amount()).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("getOrCreate은 기존 계정을 재사용한다")
    void 계정_재사용() {
        Account first = accountAdapter.getOrCreate(Account.createPlatformCash());
        Account second = accountAdapter.getOrCreate(Account.createPlatformCash());

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(accountRepository.count()).isEqualTo(2); // platformCash + sellerPayable (setUp)
    }
}
```

- [ ] **Step 2: Create test application properties (if not exists)**

Ensure `src/test/resources/application-test.yml` exists:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
  flyway:
    enabled: false
```

- [ ] **Step 3: Run integration tests**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --tests "github.lms.lemuel.ledger.adapter.out.persistence.LedgerPersistenceAdapterTest" --info 2>&1 | tail -30`
Expected: All 5 tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/github/lms/lemuel/ledger/adapter/out/persistence/LedgerPersistenceAdapterTest.java \
        src/test/resources/application-test.yml
git commit -m "test(ledger): add persistence adapter integration tests with H2"
```

---

### Task 12: Run All Tests + Final Verification

- [ ] **Step 1: Run full test suite**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew test --info 2>&1 | tail -30`
Expected: All tests PASS

- [ ] **Step 2: Verify build**

Run: `cd /c/Users/iamip/IdeaProjects/kubenetis/settlement && ./gradlew build 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Final commit if any pending changes**

```bash
git status
# If any uncommitted changes, commit them
```
