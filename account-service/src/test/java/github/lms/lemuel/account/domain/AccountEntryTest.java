package github.lms.lemuel.account.domain;

import github.lms.lemuel.account.domain.exception.NonPositiveEntryAmountException;
import github.lms.lemuel.account.domain.exception.UnbalancedAccountEntryException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이벤트→분개 매핑(정적 팩토리 6종)이 계정계의 핵심 도메인 규칙이다 — 차/대 계정과목·refType·sourceTopic·owner 를 고정한다.
 */
class AccountEntryTest {

    @Test
    void 정산생성_DR_SETTLEMENT_SCHEDULED_CR_SELLER_PAYABLE() {
        AccountEntry e = AccountEntry.settlementCreated("777", "9001", new BigDecimal("43425"));
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getOwnerId()).isEqualTo("777");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SETTLEMENT_SCHEDULED);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getAmount()).isEqualByComparingTo("43425");
        assertThat(e.getRefType()).isEqualTo("SETTLEMENT_CREATED");
        assertThat(e.getRefId()).isEqualTo("9001");
        assertThat(e.getSourceTopic()).isEqualTo("lemuel.settlement.created");
        assertThat(e.getOccurredAt()).isNotNull();
    }

    @Test
    void 정산확정_DR_SELLER_PAYABLE_CR_SETTLEMENT_SCHEDULED() {
        AccountEntry e = AccountEntry.settlementConfirmed("777", "9001", new BigDecimal("43425"));
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.SELLER_PAYABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SETTLEMENT_SCHEDULED);
        assertThat(e.getRefType()).isEqualTo("SETTLEMENT_CONFIRMED");
        assertThat(e.getSourceTopic()).isEqualTo("lemuel.settlement.confirmed");
    }

    @Test
    void 셀러대출선지급_DR_LOAN_RECEIVABLE_CR_CASH() {
        AccountEntry e = AccountEntry.loanDisbursed("55", "L-1", new BigDecimal("800000"));
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefType()).isEqualTo("LOAN_DISBURSED");
        assertThat(e.getRefId()).isEqualTo("L-1");
        assertThat(e.getSourceTopic()).isEqualTo("lemuel.loan.disbursement_requested");
    }

    @Test
    void 대출상환_DR_CASH_CR_LOAN_RECEIVABLE() {
        AccountEntry e = AccountEntry.loanRepaid("55", "9001", new BigDecimal("12345"));
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
        assertThat(e.getRefType()).isEqualTo("LOAN_REPAID");
        assertThat(e.getRefId()).isEqualTo("9001");
        assertThat(e.getSourceTopic()).isEqualTo("lemuel.loan.repayment_applied");
    }

    @Test
    void 법인대출선지급_CORPORATE_DR_CORPORATE_LOAN_RECEIVABLE_CR_CASH() {
        AccountEntry e = AccountEntry.corporateLoanDisbursed("005930", "CL-9", new BigDecimal("5000000"));
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.CORPORATE);
        assertThat(e.getOwnerId()).isEqualTo("005930");
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.CORPORATE_LOAN_RECEIVABLE);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefType()).isEqualTo("CORP_LOAN_DISBURSED");
        assertThat(e.getSourceTopic()).isEqualTo("lemuel.loan.corporate_loan_disbursed");
    }

    @Test
    void 투자집행_DR_INVESTMENT_ASSET_CR_CASH() {
        AccountEntry e = AccountEntry.investmentExecuted("55", "ORD-3", new BigDecimal("250000"));
        assertThat(e.getOwnerType()).isEqualTo(OwnerType.SELLER);
        assertThat(e.getDebitAccount()).isEqualTo(GlAccount.INVESTMENT_ASSET);
        assertThat(e.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(e.getRefType()).isEqualTo("INVESTMENT_EXECUTED");
        assertThat(e.getRefId()).isEqualTo("ORD-3");
        assertThat(e.getSourceTopic()).isEqualTo("lemuel.investment.executed");
    }

    @Test
    void 각_전표는_차변금액과_대변금액이_같아_구성적으로_균형이다() {
        AccountEntry e = AccountEntry.loanDisbursed("55", "L-1", new BigDecimal("800000"));
        // 한 전표의 차변금액 = 대변금액 = amount
        assertThat(e.getAmount()).isEqualByComparingTo("800000");
        assertThat(e.getDebitAccount()).isNotEqualTo(e.getCreditAccount());
    }

    @Test
    void 금액이_0이하면_예외() {
        assertThatThrownBy(() -> AccountEntry.investmentExecuted("55", "ORD-3", BigDecimal.ZERO))
                .isInstanceOf(NonPositiveEntryAmountException.class);
        assertThatThrownBy(() -> AccountEntry.loanDisbursed("55", "L-1", new BigDecimal("-1")))
                .isInstanceOfSatisfying(NonPositiveEntryAmountException.class,
                        ex -> assertThat(ex.getAmount()).isEqualByComparingTo("-1"));
    }

    @Test
    void 금액이_null이면_예외() {
        assertThatThrownBy(() -> AccountEntry.settlementCreated("1", "2", null))
                .isInstanceOf(NonPositiveEntryAmountException.class);
    }

    @Test
    void reconstitute_는_영속상태를_그대로_복원한다() {
        java.time.LocalDateTime ts = java.time.LocalDateTime.of(2026, 7, 10, 9, 30);
        AccountEntry e = AccountEntry.reconstitute(42L, OwnerType.SELLER, "777",
                GlAccount.SETTLEMENT_SCHEDULED, GlAccount.SELLER_PAYABLE, new BigDecimal("100"),
                "SETTLEMENT_CREATED", "9001", "lemuel.settlement.created", ts);
        assertThat(e.getId()).isEqualTo(42L);
        assertThat(e.getOccurredAt()).isEqualTo(ts);
    }

    @Test
    void 차변과_대변이_같으면_예외() {
        // reconstitute 로 같은 계정을 넣으면 방어 예외 (팩토리 경로에선 발생 불가)
        assertThatThrownBy(() -> AccountEntry.reconstitute(1L, OwnerType.SELLER, "1",
                GlAccount.CASH, GlAccount.CASH, new BigDecimal("1"),
                "X", "1", "t", java.time.LocalDateTime.now()))
                .isInstanceOfSatisfying(UnbalancedAccountEntryException.class,
                        ex -> assertThat(ex.getAccount()).isEqualTo(GlAccount.CASH));
    }
}
