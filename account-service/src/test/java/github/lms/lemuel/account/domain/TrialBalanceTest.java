package github.lms.lemuel.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrialBalanceTest {

    @Test
    void 빈_전표목록은_균형이며_총합0() {
        TrialBalance tb = TrialBalance.of(List.of());
        assertThat(tb.lines()).isEmpty();
        assertThat(tb.totalDebit()).isEqualByComparingTo("0");
        assertThat(tb.totalCredit()).isEqualByComparingTo("0");
        assertThat(tb.balanced()).isTrue();
    }

    @Test
    void 총차변합은_항상_총대변합과_같아_균형이다() {
        TrialBalance tb = TrialBalance.of(List.of(
                AccountEntry.loanDisbursed("1", "L1", new BigDecimal("800000")),
                AccountEntry.investmentExecuted("1", "O1", new BigDecimal("250000")),
                AccountEntry.settlementCreated("1", "S1", new BigDecimal("43425"))));
        assertThat(tb.totalDebit()).isEqualByComparingTo("1093425");
        assertThat(tb.totalCredit()).isEqualByComparingTo("1093425");
        assertThat(tb.balanced()).isTrue();
    }

    @Test
    void 계정별_차변합_대변합이_누적된다() {
        // CASH 는 loanDisbursed(대변 80만) + investment(대변 25만) → 대변합 105만
        TrialBalance tb = TrialBalance.of(List.of(
                AccountEntry.loanDisbursed("1", "L1", new BigDecimal("800000")),
                AccountEntry.investmentExecuted("1", "O1", new BigDecimal("250000"))));

        TrialBalance.Line cash = tb.lines().stream()
                .filter(l -> l.account() == GlAccount.CASH).findFirst().orElseThrow();
        assertThat(cash.debitTotal()).isEqualByComparingTo("0");
        assertThat(cash.creditTotal()).isEqualByComparingTo("1050000");

        TrialBalance.Line loan = tb.lines().stream()
                .filter(l -> l.account() == GlAccount.LOAN_RECEIVABLE).findFirst().orElseThrow();
        assertThat(loan.debitTotal()).isEqualByComparingTo("800000");
        assertThat(loan.creditTotal()).isEqualByComparingTo("0");
    }

    @Test
    void 등장하지_않은_계정은_시산표에_노출되지_않는다() {
        TrialBalance tb = TrialBalance.of(List.of(
                AccountEntry.investmentExecuted("1", "O1", new BigDecimal("250000"))));
        // INVESTMENT_ASSET, CASH 만 등장 — SELLER_PAYABLE 등은 없음
        assertThat(tb.lines()).extracting(TrialBalance.Line::account)
                .containsExactlyInAnyOrder(GlAccount.INVESTMENT_ASSET, GlAccount.CASH)
                .doesNotContain(GlAccount.SELLER_PAYABLE, GlAccount.SETTLEMENT_SCHEDULED);
    }
}
