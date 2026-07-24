package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerTrialBalanceTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    private static Map<AccountType, BigDecimal> map(Object... kv) {
        Map<AccountType, BigDecimal> m = new EnumMap<>(AccountType.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((AccountType) kv[i], new BigDecimal((String) kv[i + 1]));
        }
        return m;
    }

    @Test
    void 정산확정_분개는_차대_균형_총합_일치() {
        // Dr AP/Cr REVENUE 9700, Dr COMM_EXP/Cr COMM_REV 300
        var debit = map(AccountType.ACCOUNTS_PAYABLE, "9700", AccountType.COMMISSION_EXPENSE, "300");
        var credit = map(AccountType.REVENUE, "9700", AccountType.COMMISSION_REVENUE, "300");

        LedgerTrialBalance tb = LedgerTrialBalance.of(MARCH, debit, credit);

        assertThat(tb.getTotalDebit()).isEqualByComparingTo("10000.00");
        assertThat(tb.getTotalCredit()).isEqualByComparingTo("10000.00");
        assertThat(tb.isBalanced()).isTrue();
        assertThat(tb.getPeriodYm()).isEqualTo("2026-03");
        // 활동 계정 4종만 라인으로 나온다 (AR·SALES_REFUND·CASH 는 0 → 생략).
        assertThat(tb.getLines()).hasSize(4);
    }

    @Test
    void 계정별_순액은_차_minus_대() {
        var debit = map(AccountType.ACCOUNTS_PAYABLE, "9700");
        var credit = map(AccountType.REVENUE, "9700");

        LedgerTrialBalance tb = LedgerTrialBalance.of(MARCH, debit, credit);

        LedgerTrialBalance.Line ap = tb.getLines().stream()
                .filter(l -> l.account() == AccountType.ACCOUNTS_PAYABLE).findFirst().orElseThrow();
        LedgerTrialBalance.Line rev = tb.getLines().stream()
                .filter(l -> l.account() == AccountType.REVENUE).findFirst().orElseThrow();

        assertThat(ap.net()).isEqualByComparingTo("9700.00");
        assertThat(rev.net()).isEqualByComparingTo("-9700.00");
    }

    @Test
    void 차대_불일치는_balanced_false_로_감지() {
        var debit = map(AccountType.ACCOUNTS_PAYABLE, "100");
        var credit = map(AccountType.REVENUE, "90");

        LedgerTrialBalance tb = LedgerTrialBalance.of(MARCH, debit, credit);

        assertThat(tb.isBalanced()).isFalse();
        assertThat(tb.getTotalDebit()).isEqualByComparingTo("100.00");
        assertThat(tb.getTotalCredit()).isEqualByComparingTo("90.00");
    }

    @Test
    void 빈_분개는_0총합_균형_라인없음() {
        LedgerTrialBalance tb = LedgerTrialBalance.of(MARCH, Map.of(), Map.of());

        assertThat(tb.getLines()).isEmpty();
        assertThat(tb.getTotalDebit()).isEqualByComparingTo("0.00");
        assertThat(tb.getTotalCredit()).isEqualByComparingTo("0.00");
        assertThat(tb.isBalanced()).isTrue();
    }

    @Test
    void null_맵도_안전하게_처리() {
        LedgerTrialBalance tb = LedgerTrialBalance.of(MARCH, null, null);

        assertThat(tb.getLines()).isEmpty();
        assertThat(tb.isBalanced()).isTrue();
    }
}
