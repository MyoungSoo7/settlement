package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 담보대출 전표 — 보증료 · 중도상환수수료 · 담보 처분 · 대위변제 · 상각.
 *
 * <p>각 전표가 <b>어느 계정 쌍으로 흐르는지</b>를 못 박는다. 회계 방향이 틀리면 시산표는 맞아도
 * 손익이 반대로 잡히므로, 균형만 보는 검증으로는 잡히지 않는다.
 */
class LoanLedgerEntryPhase2Test {

    private static final long LOAN_ID = 7001L;
    private static final BigDecimal AMOUNT = new BigDecimal("1000000");

    // ─── 보증료 (선취 비용) ────────────────────────────────────────────────────

    @Test
    void 보증료는_비용계정_차변과_현금_대변이다() {
        LoanLedgerEntry entry = LoanLedgerEntry.securedGuaranteeFee(LOAN_ID, AMOUNT);

        assertThat(entry.getDebit()).isEqualTo(LedgerAccount.GUARANTEE_FEE_EXPENSE);
        assertThat(entry.getCredit()).isEqualTo(LedgerAccount.CASH);
        assertThat(entry.getRefType()).isEqualTo("SEC_GUARANTEE_FEE");
        assertThat(entry.getRefId()).isEqualTo(LOAN_ID);
        assertThat(entry.getAmount()).isEqualByComparingTo("1000000");
    }

    // ─── 중도상환수수료 (수익) ─────────────────────────────────────────────────

    @Test
    void 중도상환수수료는_현금_차변과_수수료수익_대변이다() {
        LoanLedgerEntry entry = LoanLedgerEntry.securedEarlyRepaymentFee(LOAN_ID, AMOUNT);

        assertThat(entry.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(entry.getCredit()).isEqualTo(LedgerAccount.FEE_INCOME);
        assertThat(entry.getRefType()).isEqualTo("SEC_EARLY_FEE");
    }

    // ─── 담보 처분 ────────────────────────────────────────────────────────────

    @Test
    void 담보처분_회수액은_현금_차변과_대출채권_대변이다() {
        LoanLedgerEntry entry = LoanLedgerEntry.securedCollateralDisposal(LOAN_ID, AMOUNT);

        assertThat(entry.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(entry.getCredit()).isEqualTo(LedgerAccount.LOAN_RECEIVABLE);
        assertThat(entry.getRefType()).isEqualTo("SEC_DISPOSAL");
    }

    @Test
    void 처분부족분은_처분손실_차변과_대출채권_대변이다() {
        // 채권을 털어내면서 손실을 비용으로 인식한다.
        LoanLedgerEntry entry = LoanLedgerEntry.securedDisposalShortfall(LOAN_ID, AMOUNT);

        assertThat(entry.getDebit()).isEqualTo(LedgerAccount.COLLATERAL_DISPOSAL_LOSS);
        assertThat(entry.getCredit()).isEqualTo(LedgerAccount.LOAN_RECEIVABLE);
        assertThat(entry.getRefType()).isEqualTo("SEC_DISPOSAL_LOSS");
    }

    @Test
    void 처분초과분은_현금_차변과_처분이익_대변이다() {
        // 채권액보다 많이 회수되면 초과분은 이익이다(차주 반환 의무는 이 단계 범위 밖).
        LoanLedgerEntry entry = LoanLedgerEntry.securedDisposalSurplus(LOAN_ID, AMOUNT);

        assertThat(entry.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(entry.getCredit()).isEqualTo(LedgerAccount.COLLATERAL_DISPOSAL_GAIN);
        assertThat(entry.getRefType()).isEqualTo("SEC_DISPOSAL_GAIN");
    }

    // ─── 대위변제 ─────────────────────────────────────────────────────────────

    @Test
    void 대위변제_회수는_현금_차변과_대출채권_대변이다() {
        LoanLedgerEntry entry = LoanLedgerEntry.securedSubrogation(LOAN_ID, AMOUNT);

        assertThat(entry.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(entry.getCredit()).isEqualTo(LedgerAccount.LOAN_RECEIVABLE);
        assertThat(entry.getRefType()).isEqualTo("SEC_SUBROGATION");
    }

    // ─── 상각 ────────────────────────────────────────────────────────────────

    @Test
    void 미회수_상각은_대손비용_차변과_대손충당금_대변이다() {
        LoanLedgerEntry entry = LoanLedgerEntry.securedWriteOff(LOAN_ID, AMOUNT);

        assertThat(entry.getDebit()).isEqualTo(LedgerAccount.BAD_DEBT_EXPENSE);
        assertThat(entry.getCredit()).isEqualTo(LedgerAccount.BAD_DEBT_ALLOWANCE);
        assertThat(entry.getRefType()).isEqualTo("SEC_BAD_DEBT");
    }

    // ─── 공통 불변식 ──────────────────────────────────────────────────────────

    @Test
    void 모든_Phase2_전표는_차변과_대변_계정이_다르다() {
        assertThat(LoanLedgerEntry.securedGuaranteeFee(LOAN_ID, AMOUNT).getDebit())
                .isNotEqualTo(LoanLedgerEntry.securedGuaranteeFee(LOAN_ID, AMOUNT).getCredit());
        assertThat(LoanLedgerEntry.securedDisposalShortfall(LOAN_ID, AMOUNT).getDebit())
                .isNotEqualTo(LoanLedgerEntry.securedDisposalShortfall(LOAN_ID, AMOUNT).getCredit());
        assertThat(LoanLedgerEntry.securedSubrogation(LOAN_ID, AMOUNT).getDebit())
                .isNotEqualTo(LoanLedgerEntry.securedSubrogation(LOAN_ID, AMOUNT).getCredit());
    }

    @Test
    void 금액이_0이하인_전표는_만들어지지_않는다() {
        assertThatThrownBy(() -> LoanLedgerEntry.securedGuaranteeFee(LOAN_ID, BigDecimal.ZERO))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> LoanLedgerEntry.securedSubrogation(LOAN_ID, new BigDecimal("-1")))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 계정과목명은_DB_컬럼_길이_30자를_넘지_않는다() {
        // loan_ledger_entries.debit/credit 은 VARCHAR(30) — 계정명이 길어지면 조용히 잘리거나 실패한다.
        for (LedgerAccount account : LedgerAccount.values()) {
            assertThat(account.name().length()).as("%s", account).isLessThanOrEqualTo(30);
        }
    }
}
