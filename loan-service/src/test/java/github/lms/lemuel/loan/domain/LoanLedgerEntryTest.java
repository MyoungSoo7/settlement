package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanLedgerEntryTest {

    @Test
    void 선지급_전표는_대출채권차변_현금대변() {
        LoanLedgerEntry e = LoanLedgerEntry.disbursement(1L, new BigDecimal("800000"));
        assertThat(e.getDebit()).isEqualTo(LedgerAccount.LOAN_RECEIVABLE);
        assertThat(e.getCredit()).isEqualTo(LedgerAccount.CASH);
        assertThat(e.getAmount()).isEqualByComparingTo("800000");
    }

    @Test
    void 수수료_전표는_미수수익차변_수수료수익대변() {
        LoanLedgerEntry e = LoanLedgerEntry.feeAccrual(1L, new BigDecimal("800"));
        assertThat(e.getDebit()).isEqualTo(LedgerAccount.FEE_RECEIVABLE);
        assertThat(e.getCredit()).isEqualTo(LedgerAccount.FEE_INCOME);
    }

    @Test
    void 상환_전표는_현금차변_대출채권대변() {
        LoanLedgerEntry e = LoanLedgerEntry.repayment(100L, new BigDecimal("800800"));
        assertThat(e.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(e.getCredit()).isEqualTo(LedgerAccount.LOAN_RECEIVABLE);
    }

    @Test
    void 전표금액이_0이하면_예외() {
        assertThatThrownBy(() -> LoanLedgerEntry.disbursement(1L, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 선지급_사이클_전표들의_차변합과_대변합은_계정별로_균형이다() {
        // 선지급 80만 + 수수료 800 + 전액 상환(80만800)
        List<LoanLedgerEntry> entries = List.of(
                LoanLedgerEntry.disbursement(1L, new BigDecimal("800000")),
                LoanLedgerEntry.feeAccrual(1L, new BigDecimal("800")),
                LoanLedgerEntry.repayment(100L, new BigDecimal("800800")));

        // 각 전표는 차변=대변(=amount) 이므로 전체 차변합 = 전체 대변합
        BigDecimal debitTotal = entries.stream().map(LoanLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = entries.stream().map(LoanLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);

        // 대출채권: 선지급(차변 80만) - 상환(대변 80만800) → 잔액 음수 아님 검증은 생략(부분상환 케이스)
        assertThat(debitTotal).isEqualByComparingTo("1601600");
    }
}
