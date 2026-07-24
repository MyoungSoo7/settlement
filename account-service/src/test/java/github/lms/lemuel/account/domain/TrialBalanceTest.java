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
                AccountEntry.settlementCreatedImmediate("1", "S1", new BigDecimal("43425"))));
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

    // ── ADR 0026 Option A — GL 현금 폐루프 & 시산표 실검증(normalBalanceRespected) ──

    @Test
    void 정산생성_후_지급완료면_CASH_SELLER_PAYABLE_순잔액0_폐루프() {
        // created: DR CASH / CR SELLER_PAYABLE, payoutCompleted: DR SELLER_PAYABLE / CR CASH
        TrialBalance tb = TrialBalance.of(List.of(
                AccountEntry.settlementCreatedImmediate("777", "S1", new BigDecimal("43425")),
                AccountEntry.payoutCompleted("777", "P1", new BigDecimal("43425"))));

        TrialBalance.Line cash = tb.lines().stream()
                .filter(l -> l.account() == GlAccount.CASH).findFirst().orElseThrow();
        TrialBalance.Line payable = tb.lines().stream()
                .filter(l -> l.account() == GlAccount.SELLER_PAYABLE).findFirst().orElseThrow();
        // 순잔액 0 (플랫폼 pass-through)
        assertThat(cash.debitTotal().subtract(cash.creditTotal())).isEqualByComparingTo("0");
        assertThat(payable.creditTotal().subtract(payable.debitTotal())).isEqualByComparingTo("0");
        assertThat(tb.balanced()).isTrue();
        assertThat(tb.normalBalanceRespected()).isTrue();
    }

    @Test
    void 정산생성만_지급전이면_CASH순차변_SELLER_PAYABLE순대변_정상방향() {
        TrialBalance tb = TrialBalance.of(List.of(
                AccountEntry.settlementCreatedImmediate("777", "S1", new BigDecimal("43425"))));
        assertThat(tb.balanced()).isTrue();
        // CASH(차변성) 순차변 + SELLER_PAYABLE(대변성) 순대변 → 둘 다 정상방향 준수
        assertThat(tb.normalBalanceRespected()).isTrue();
    }

    @Test
    void 지급이_인식보다_많으면_SELLER_PAYABLE이_순차변이라_정상방향_위반() {
        // payout 이 created 없이(또는 초과) 발생 → SELLER_PAYABLE 순차변(음수 미지급금) = 이상
        TrialBalance tb = TrialBalance.of(List.of(
                AccountEntry.payoutCompleted("777", "P1", new BigDecimal("43425"))));
        assertThat(tb.balanced()).isTrue();                    // 항등식은 여전히 참
        assertThat(tb.normalBalanceRespected()).isFalse();     // 방향 검증이 이상을 잡는다
    }

    @Test
    void 순잔액0은_정상방향_준수로_본다() {
        TrialBalance tb = TrialBalance.of(List.of(
                AccountEntry.settlementCreatedImmediate("777", "S1", new BigDecimal("100")),
                AccountEntry.payoutCompleted("777", "P1", new BigDecimal("100"))));
        assertThat(tb.normalBalanceRespected()).isTrue();
    }
}
