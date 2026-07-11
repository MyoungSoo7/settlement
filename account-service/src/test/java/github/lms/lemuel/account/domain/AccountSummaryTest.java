package github.lms.lemuel.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountSummaryTest {

    private static BigDecimal balanceOf(AccountSummary s, GlAccount account) {
        return s.balances().stream()
                .filter(b -> b.account() == account)
                .map(AccountSummary.Balance::balance)
                .findFirst().orElseThrow();
    }

    @Test
    void 차변성_계정은_DR빼기CR로_잔액을_낸다() {
        // 선지급(LOAN_RECEIVABLE 차변 80만) + 상환(LOAN_RECEIVABLE 대변 30만) → 잔액 50만
        AccountSummary s = AccountSummary.of(OwnerType.SELLER, "55", List.of(
                AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000")),
                AccountEntry.loanRepaid("55", "S1", new BigDecimal("300000"))));

        assertThat(balanceOf(s, GlAccount.LOAN_RECEIVABLE)).isEqualByComparingTo("500000");
        assertThat(s.balances()).filteredOn(b -> b.account() == GlAccount.LOAN_RECEIVABLE)
                .allMatch(b -> b.side() == AccountSide.DEBIT);
        assertThat(s.entryCount()).isEqualTo(2);
        assertThat(s.ownerId()).isEqualTo("55");
        assertThat(s.ownerType()).isEqualTo(OwnerType.SELLER);
    }

    @Test
    void 대변성_계정은_CR빼기DR로_잔액을_낸다() {
        // 정산생성(SELLER_PAYABLE 대변 10만) + 정산확정(SELLER_PAYABLE 차변 4만) → 잔액 6만
        AccountSummary s = AccountSummary.of(OwnerType.SELLER, "77", List.of(
                AccountEntry.settlementCreated("77", "S1", new BigDecimal("100000")),
                AccountEntry.settlementConfirmed("77", "S1b", new BigDecimal("40000"))));

        assertThat(balanceOf(s, GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("60000");
        assertThat(s.balances()).filteredOn(b -> b.account() == GlAccount.SELLER_PAYABLE)
                .allMatch(b -> b.side() == AccountSide.CREDIT);
    }

    @Test
    void SETTLEMENT_SCHEDULED_차변성_잔액은_생성빼기확정() {
        // 생성(차변 10만) - 확정(대변 4만) → 6만 (pendingScheduled 와 동일 부호)
        AccountSummary s = AccountSummary.of(OwnerType.SELLER, "77", List.of(
                AccountEntry.settlementCreated("77", "S1", new BigDecimal("100000")),
                AccountEntry.settlementConfirmed("77", "S1b", new BigDecimal("40000"))));
        assertThat(balanceOf(s, GlAccount.SETTLEMENT_SCHEDULED)).isEqualByComparingTo("60000");
    }

    @Test
    void 법인_잔액은_CORPORATE_LOAN_RECEIVABLE_차변성() {
        AccountSummary s = AccountSummary.of(OwnerType.CORPORATE, "005930", List.of(
                AccountEntry.corporateLoanDisbursed("005930", "CL1", new BigDecimal("5000000"))));
        assertThat(balanceOf(s, GlAccount.CORPORATE_LOAN_RECEIVABLE)).isEqualByComparingTo("5000000");
        assertThat(balanceOf(s, GlAccount.CASH)).isEqualByComparingTo("-5000000"); // 차변성 CASH: DR0-CR500만
    }

    @Test
    void 전표없으면_빈_요약() {
        AccountSummary s = AccountSummary.of(OwnerType.SELLER, "0", List.of());
        assertThat(s.balances()).isEmpty();
        assertThat(s.entryCount()).isZero();
    }
}
