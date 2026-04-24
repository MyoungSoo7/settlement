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
