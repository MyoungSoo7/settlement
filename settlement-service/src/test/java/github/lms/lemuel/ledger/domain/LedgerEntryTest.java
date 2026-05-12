package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LedgerEntry 도메인 — 분개 검증·상태 전이 규칙.
 */
class LedgerEntryTest {

    private static final LocalDate TODAY = LocalDate.now();

    private LedgerEntry validEntry() {
        return LedgerEntry.of(
                100L, ReferenceType.SETTLEMENT,
                LedgerEntryType.SETTLEMENT_CONFIRMED,
                AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                new BigDecimal("9700"), TODAY, "정산 확정");
    }

    @Nested
    class 생성 {

        @Test
        void 정상_생성_PENDING_으로_시작() {
            LedgerEntry e = validEntry();

            assertThat(e.getStatus()).isEqualTo(LedgerStatus.PENDING);
            assertThat(e.isPending()).isTrue();
            assertThat(e.getDebitAccount()).isEqualTo(AccountType.ACCOUNTS_PAYABLE);
            assertThat(e.getCreditAccount()).isEqualTo(AccountType.REVENUE);
            assertThat(e.getAmount()).isEqualByComparingTo("9700.00");
            assertThat(e.getMemo()).isEqualTo("정산 확정");
            assertThat(e.getPostedAt()).isNull();
        }

        @Test
        void amount_0이거나_음수면_거부() {
            assertThatThrownBy(() -> LedgerEntry.of(1L, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CREATED,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    BigDecimal.ZERO, TODAY, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("amount");

            assertThatThrownBy(() -> LedgerEntry.of(1L, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CREATED,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    new BigDecimal("-1"), TODAY, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 차변과_대변이_같은_계정이면_거부() {
            assertThatThrownBy(() -> LedgerEntry.of(1L, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CREATED,
                    AccountType.REVENUE, AccountType.REVENUE,
                    new BigDecimal("100"), TODAY, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("debit");
        }

        @Test
        void referenceId_0이거나_음수면_거부() {
            assertThatThrownBy(() -> LedgerEntry.of(0L, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CREATED,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    new BigDecimal("100"), TODAY, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("referenceId");

            assertThatThrownBy(() -> LedgerEntry.of(-5L, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CREATED,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    new BigDecimal("100"), TODAY, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 필수필드_누락_거부() {
            // referenceType
            assertThatThrownBy(() -> LedgerEntry.of(1L, null,
                    LedgerEntryType.SETTLEMENT_CREATED,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    new BigDecimal("100"), TODAY, null))
                    .isInstanceOf(IllegalArgumentException.class);

            // entryType
            assertThatThrownBy(() -> LedgerEntry.of(1L, ReferenceType.SETTLEMENT,
                    null,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    new BigDecimal("100"), TODAY, null))
                    .isInstanceOf(IllegalArgumentException.class);

            // debit account
            assertThatThrownBy(() -> LedgerEntry.of(1L, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CREATED,
                    null, AccountType.REVENUE,
                    new BigDecimal("100"), TODAY, null))
                    .isInstanceOf(IllegalArgumentException.class);

            // settlementDate
            assertThatThrownBy(() -> LedgerEntry.of(1L, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CREATED,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    new BigDecimal("100"), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void amount_는_scale_2_HALF_UP_으로_정규화() {
            LedgerEntry e = LedgerEntry.of(1L, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CREATED,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    new BigDecimal("100.555"), TODAY, null);

            assertThat(e.getAmount()).isEqualByComparingTo("100.56");
        }
    }

    @Nested
    class 상태_전이 {

        @Test
        void PENDING_에서_post_하면_POSTED_postedAt_세팅() {
            LedgerEntry e = validEntry();

            e.post();

            assertThat(e.isPosted()).isTrue();
            assertThat(e.getPostedAt()).isNotNull();
        }

        @Test
        void POSTED_재전기_불가() {
            LedgerEntry e = validEntry();
            e.post();

            assertThatThrownBy(e::post)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("post");
        }

        @Test
        void PENDING_에서_바로_reverse_가능() {
            LedgerEntry e = validEntry();

            e.reverse();

            assertThat(e.isReversed()).isTrue();
        }

        @Test
        void POSTED_에서_reverse_가능() {
            LedgerEntry e = validEntry();
            e.post();

            e.reverse();

            assertThat(e.isReversed()).isTrue();
            // postedAt 은 보존
            assertThat(e.getPostedAt()).isNotNull();
        }

        @Test
        void REVERSED_재전이_모두_불가() {
            LedgerEntry e = validEntry();
            e.reverse();

            assertThatThrownBy(e::post).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(e::reverse).isInstanceOf(IllegalStateException.class);
        }
    }
}
