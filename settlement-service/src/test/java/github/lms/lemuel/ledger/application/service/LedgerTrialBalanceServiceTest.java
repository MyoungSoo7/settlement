package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerTrialBalancePort;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerTrialBalance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTrialBalanceServiceTest {

    @Test
    void 월경계_1일부터_말일까지_집계하고_조립한다() {
        CapturingPort port = new CapturingPort();
        port.debit.put(AccountType.ACCOUNTS_PAYABLE, new BigDecimal("9700"));
        port.credit.put(AccountType.REVENUE, new BigDecimal("9700"));
        LedgerTrialBalanceService service = new LedgerTrialBalanceService(port);

        LedgerTrialBalance tb = service.getForPeriod(YearMonth.of(2026, 2));

        // 2026-02 은 28일까지 → 경계 검증.
        assertThat(port.from).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(port.to).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(tb.isBalanced()).isTrue();
        assertThat(tb.getTotalDebit()).isEqualByComparingTo("9700.00");
    }

    @Test
    void period_null_이면_예외() {
        LedgerTrialBalanceService service = new LedgerTrialBalanceService(new CapturingPort());
        assertThatThrownBy(() -> service.getForPeriod(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static class CapturingPort implements LoadLedgerTrialBalancePort {
        LocalDate from;
        LocalDate to;
        final Map<AccountType, BigDecimal> debit = new EnumMap<>(AccountType.class);
        final Map<AccountType, BigDecimal> credit = new EnumMap<>(AccountType.class);

        @Override
        public Map<AccountType, BigDecimal> sumPostedDebitByAccount(LocalDate from, LocalDate to) {
            this.from = from;
            this.to = to;
            return debit;
        }

        @Override
        public Map<AccountType, BigDecimal> sumPostedCreditByAccount(LocalDate from, LocalDate to) {
            return credit;
        }
    }
}
