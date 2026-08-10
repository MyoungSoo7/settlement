package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.InsufficientDepositException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * SellerDepositAccount 도메인 불변식 단위 테스트.
 *
 * <p>검증 대상:
 * <ul>
 *   <li>total = available + locked (항등식)
 *   <li>available >= 0, locked >= 0, total >= 0 (음수 금지)
 *   <li>입금(credit)·출금(debit)·잠금(lock)·해제(release)·캡처(captureFromLocked) 정상 경로
 *   <li>잔고 부족 시 예외 (부족 경로)
 * </ul>
 */
class SellerDepositAccountTest {

    private static final Long SELLER_ID = 42L;

    private SellerDepositAccount newAccount() {
        return SellerDepositAccount.open(SELLER_ID);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 초기 상태
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("계좌 생성 시 all balances = 0 이고 total = available + locked 성립")
    void open_initialBalancesAreZero() {
        SellerDepositAccount account = newAccount();

        assertThat(account.getAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getLocked()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertInvariant(account);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // credit (입금)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("credit — 입금")
    class CreditTests {

        @Test
        @DisplayName("정산 확정 입금 후 available 증가, locked 불변, total = available + locked")
        void credit_increasesAvailable() {
            SellerDepositAccount account = newAccount();

            account.credit(new BigDecimal("100000.00"));

            assertThat(account.getAvailable()).isEqualByComparingTo("100000.00");
            assertThat(account.getLocked()).isEqualByComparingTo("0.00");
            assertThat(account.getTotal()).isEqualByComparingTo("100000.00");
            assertInvariant(account);
        }

        @Test
        @DisplayName("복수 입금 누적")
        void credit_accumulates() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("50000"));
            account.credit(new BigDecimal("30000.50"));

            assertThat(account.getAvailable()).isEqualByComparingTo("80000.50");
            assertInvariant(account);
        }

        @Test
        @DisplayName("1원 입금 — 경계값")
        void credit_oneWon() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("1"));

            assertThat(account.getAvailable()).isEqualByComparingTo("1");
            assertInvariant(account);
        }

        @Test
        @DisplayName("0원 입금 → 예외")
        void credit_zero_throws() {
            SellerDepositAccount account = newAccount();
            assertThatThrownBy(() -> account.credit(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("음수 입금 → 예외")
        void credit_negative_throws() {
            SellerDepositAccount account = newAccount();
            assertThatThrownBy(() -> account.credit(new BigDecimal("-1000")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // debit (출금)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("debit — 출금")
    class DebitTests {

        @Test
        @DisplayName("정상 출금 후 available 감소, total 감소")
        void debit_decreasesAvailable() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("200000"));

            account.debit(new BigDecimal("80000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("120000");
            assertThat(account.getTotal()).isEqualByComparingTo("120000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("정확히 잔액만큼 출금 — available = 0 이 되어도 유효")
        void debit_exactAmount_availableBecomesZero() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("50000"));

            account.debit(new BigDecimal("50000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("0");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertThat(account.getTotal()).isEqualByComparingTo("0");
            assertInvariant(account);
        }

        @Test
        @DisplayName("잔고 부족 출금 — 예외, 잔고 불변")
        void debit_insufficient_throwsAndNoChange() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("10000"));

            assertThatThrownBy(() -> account.debit(new BigDecimal("20000")))
                    .isInstanceOf(InsufficientDepositException.class);

            // 잔고 변경 없음
            assertThat(account.getAvailable()).isEqualByComparingTo("10000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("available = 0 인 상태에서 1원 출금 → 예외")
        void debit_fromZeroBalance_throws() {
            SellerDepositAccount account = newAccount();

            assertThatThrownBy(() -> account.debit(new BigDecimal("1")))
                    .isInstanceOf(InsufficientDepositException.class);
            assertInvariant(account);
        }

        @Test
        @DisplayName("0원 출금 → 예외")
        void debit_zero_throws() {
            SellerDepositAccount account = newAccount();
            assertThatThrownBy(() -> account.debit(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // lock (잠금)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("lock — hold 에 의한 잠금")
    class LockTests {

        @Test
        @DisplayName("잠금 후 available 감소·locked 증가·total 불변")
        void lock_movesFromAvailableToLocked() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("300000"));

            account.lock(new BigDecimal("100000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("200000");
            assertThat(account.getLocked()).isEqualByComparingTo("100000");
            assertThat(account.getTotal()).isEqualByComparingTo("300000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("복수 잠금 누적")
        void lock_multiple_accumulates() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("500000"));
            account.lock(new BigDecimal("100000"));
            account.lock(new BigDecimal("150000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("250000");
            assertThat(account.getLocked()).isEqualByComparingTo("250000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("잔고 부족 잠금 → 예외, 잔고 불변")
        void lock_insufficient_throws() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("50000"));

            assertThatThrownBy(() -> account.lock(new BigDecimal("60000")))
                    .isInstanceOf(InsufficientDepositException.class);

            assertThat(account.getAvailable()).isEqualByComparingTo("50000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertInvariant(account);
        }

        @Test
        @DisplayName("정확히 가용 잔액만큼 잠금 → available = 0")
        void lock_fullAvailable() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("100000"));

            account.lock(new BigDecimal("100000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("0");
            assertThat(account.getLocked()).isEqualByComparingTo("100000");
            assertInvariant(account);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // release (잠금 해제)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("release — hold 만료·취소에 의한 잠금 해제")
    class ReleaseTests {

        @Test
        @DisplayName("해제 후 locked 감소·available 증가·total 불변")
        void release_movesFromLockedToAvailable() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("200000"));
            account.lock(new BigDecimal("80000"));

            account.release(new BigDecimal("80000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("200000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertInvariant(account);
        }

        @Test
        @DisplayName("부분 해제")
        void release_partial() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("300000"));
            account.lock(new BigDecimal("150000"));

            account.release(new BigDecimal("50000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("200000");
            assertThat(account.getLocked()).isEqualByComparingTo("100000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("locked 보다 많은 해제 → 예외")
        void release_exceedsLocked_throws() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("100000"));
            account.lock(new BigDecimal("50000"));

            assertThatThrownBy(() -> account.release(new BigDecimal("60000")))
                    .isInstanceOf(InsufficientDepositException.class);

            assertThat(account.getLocked()).isEqualByComparingTo("50000");
            assertInvariant(account);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // captureFromLocked (hold capture: locked 에서 직접 차감)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("captureFromLocked — hold 에서 상계(offset)")
    class CaptureFromLockedTests {

        @Test
        @DisplayName("locked 에서 전액 capture → locked 감소·available 불변·total 감소")
        void captureFromLocked_fullCapture() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("500000"));
            account.lock(new BigDecimal("200000"));

            account.captureFromLocked(new BigDecimal("200000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("300000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertThat(account.getTotal()).isEqualByComparingTo("300000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("부분 capture 후 잔여 locked 해제 시 available 복구")
        void captureFromLocked_partial_thenRelease() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("500000"));
            account.lock(new BigDecimal("200000"));

            // 100,000 capture 후 나머지 100,000 release
            account.captureFromLocked(new BigDecimal("100000"));
            account.release(new BigDecimal("100000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("400000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertThat(account.getTotal()).isEqualByComparingTo("400000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("locked 를 초과한 capture → 예외")
        void captureFromLocked_exceedsLocked_throws() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("300000"));
            account.lock(new BigDecimal("100000"));

            assertThatThrownBy(() -> account.captureFromLocked(new BigDecimal("150000")))
                    .isInstanceOf(InsufficientDepositException.class);

            assertThat(account.getLocked()).isEqualByComparingTo("100000");
            assertInvariant(account);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // debitAvailable (available 에서 직접 차감 — hold 없는 늦은 청구)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("debitAvailable — hold 없는 직접 상계")
    class DebitAvailableTests {

        @Test
        @DisplayName("available 에서 직접 차감 → available 감소·locked 불변·total 감소")
        void debitAvailable_normal() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("400000"));

            account.debitAvailable(new BigDecimal("150000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("250000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertThat(account.getTotal()).isEqualByComparingTo("250000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("available 부족 → 예외, 잔고 불변")
        void debitAvailable_insufficient_throws() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("50000"));

            assertThatThrownBy(() -> account.debitAvailable(new BigDecimal("60000")))
                    .isInstanceOf(InsufficientDepositException.class);

            assertThat(account.getAvailable()).isEqualByComparingTo("50000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("locked 가 있어도 available 에서만 차감")
        void debitAvailable_withLockedBalance() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("200000"));
            account.lock(new BigDecimal("100000")); // locked = 100,000

            account.debitAvailable(new BigDecimal("80000")); // available was 100,000

            assertThat(account.getAvailable()).isEqualByComparingTo("20000");
            assertThat(account.getLocked()).isEqualByComparingTo("100000");
            assertThat(account.getTotal()).isEqualByComparingTo("120000");
            assertInvariant(account);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 복합 시나리오
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("복합 시나리오")
    class CompositeTests {

        @Test
        @DisplayName("입금 → 잠금 → 상계 전체 흐름")
        void fullFlow_creditLockCapture() {
            SellerDepositAccount account = newAccount();

            account.credit(new BigDecimal("1000000"));   // available=1,000,000
            account.lock(new BigDecimal("300000"));       // available=700,000 locked=300,000
            account.captureFromLocked(new BigDecimal("200000")); // locked=100,000 total=800,000
            account.release(new BigDecimal("100000"));    // locked=0 available=800,000

            assertThat(account.getAvailable()).isEqualByComparingTo("800000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertThat(account.getTotal()).isEqualByComparingTo("800000");
            assertInvariant(account);
        }

        @Test
        @DisplayName("available=0 상태에서 locked 도 있을 때 debit → 예외 (available 만 본다)")
        void debit_whenOnlyLockedRemains_throws() {
            SellerDepositAccount account = newAccount();
            account.credit(new BigDecimal("100000"));
            account.lock(new BigDecimal("100000")); // available=0, locked=100,000

            assertThatThrownBy(() -> account.debit(new BigDecimal("1")))
                    .isInstanceOf(InsufficientDepositException.class);

            assertInvariant(account);
        }

        @Test
        @DisplayName("대규모 금액 래핑 — total = available + locked 항등식 유지")
        void largeAmounts_invariantHolds() {
            SellerDepositAccount account = newAccount();

            account.credit(new BigDecimal("999999999.99"));
            account.lock(new BigDecimal("500000000.00"));
            account.captureFromLocked(new BigDecimal("100000000.00"));

            assertInvariant(account);
            assertThat(account.getAvailable())
                    .isEqualByComparingTo("499999999.99");
            assertThat(account.getLocked())
                    .isEqualByComparingTo("400000000.00");
            assertThat(account.getTotal())
                    .isEqualByComparingTo("899999999.99");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 잔고 불변식 단언.
     * <ul>
     *   <li>available >= 0
     *   <li>locked >= 0
     *   <li>total >= 0
     *   <li>total == available + locked
     * </ul>
     */
    private void assertInvariant(SellerDepositAccount account) {
        BigDecimal available = account.getAvailable();
        BigDecimal locked = account.getLocked();
        BigDecimal total = account.getTotal();

        assertThat(available)
                .as("available must be >= 0")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(locked)
                .as("locked must be >= 0")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(total)
                .as("total must be >= 0")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(total)
                .as("total must equal available + locked")
                .isEqualByComparingTo(available.add(locked));
    }
}
