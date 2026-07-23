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
                AccountEntry.settlementCreatedImmediate("77", "S1", new BigDecimal("100000")),
                AccountEntry.settlementConfirmed("77", "S1b", new BigDecimal("40000"))));

        assertThat(balanceOf(s, GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("60000");
        assertThat(s.balances()).filteredOn(b -> b.account() == GlAccount.SELLER_PAYABLE)
                .allMatch(b -> b.side() == AccountSide.CREDIT);
    }

    @Test
    void SETTLEMENT_SCHEDULED_는_차변성이라_역사적_예정금은_DR빼기CR() {
        // Option A 이전(역사적) 정산생성은 DR SETTLEMENT_SCHEDULED 였다 — 차변성 잔액 계산을 reconstitute 로 검증.
        // (Option A 정산생성은 DR CASH 로 바뀌어 더는 SCHEDULED 를 건드리지 않는다.)
        AccountSummary s = AccountSummary.of(OwnerType.SELLER, "77", List.of(
                AccountEntry.reconstitute(null, OwnerType.SELLER, "77",
                        GlAccount.SETTLEMENT_SCHEDULED, GlAccount.SELLER_PAYABLE, new BigDecimal("100000"),
                        "SETTLEMENT_CREATED", "S1", "lemuel.settlement.created", java.time.LocalDateTime.now()),
                AccountEntry.settlementConfirmed("77", "S1b", new BigDecimal("40000"))));
        // SCHEDULED: DR 10만 - CR 4만(확정 상계) = 6만
        assertThat(balanceOf(s, GlAccount.SETTLEMENT_SCHEDULED)).isEqualByComparingTo("60000");
    }

    @Test
    void OptionA_정산생성은_CASH_차변성_유입과_SELLER_PAYABLE_대변성_증가() {
        AccountSummary s = AccountSummary.of(OwnerType.SELLER, "77", List.of(
                AccountEntry.settlementCreatedImmediate("77", "S1", new BigDecimal("100000"))));
        assertThat(balanceOf(s, GlAccount.CASH)).isEqualByComparingTo("100000");          // DR CASH
        assertThat(balanceOf(s, GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("100000"); // CR SELLER_PAYABLE
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

    @Test
    void fullySettled_세_통제계정이_모두_0이면_참() {
        // 즉시분 인식(CR SELLER_PAYABLE) → 지급(DR SELLER_PAYABLE) : SELLER_PAYABLE 순잔액 0
        // 유보 인식(CR HOLDBACK_PAYABLE) → 취소 소멸(DR HOLDBACK_PAYABLE) : HOLDBACK_PAYABLE 순잔액 0
        // 회수 발생(DR RECEIVABLE) → 상계(CR RECEIVABLE, 상대변 DR SELLER_PAYABLE) : RECEIVABLE 0
        // 상계는 신규 정산 즉시분(CR SELLER_PAYABLE 200)을 소비하므로 이를 공급하는 created 를 함께 둔다.
        AccountSummary s = AccountSummary.of(OwnerType.SELLER, "77", List.of(
                AccountEntry.settlementCreatedImmediate("77", "A", new BigDecimal("700")),
                AccountEntry.payoutCompleted("77", "payA", new BigDecimal("700")),
                AccountEntry.settlementHoldbackRecognized("77", "A", new BigDecimal("300")),
                AccountEntry.settlementCanceledHoldback("77", "A", new BigDecimal("300")),
                AccountEntry.settlementCreatedImmediate("77", "C", new BigDecimal("200")), // 상계 재원 공급
                AccountEntry.recoveryOpened("77", "r1", new BigDecimal("200")),
                AccountEntry.recoveryOffset("77", "al1", new BigDecimal("200"))));
        assertThat(s.fullySettled()).isTrue();
    }

    @Test
    void fullySettled_통제계정_잔액이_남으면_거짓() {
        AccountSummary s = AccountSummary.of(OwnerType.SELLER, "77", List.of(
                AccountEntry.settlementCreatedImmediate("77", "A", new BigDecimal("700")))); // 미지급 700 잔존
        assertThat(s.fullySettled()).isFalse();
    }
}
