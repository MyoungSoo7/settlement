package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.GetLedgerTrialBalanceUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerPeriodPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerPeriodPort;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerPeriod;
import github.lms.lemuel.ledger.domain.LedgerTrialBalance;
import github.lms.lemuel.ledger.domain.exception.LedgerPeriodImbalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloseLedgerPeriodServiceTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    private FakeTrialBalance trialBalance;
    private FakePeriodStore periods;
    private CloseLedgerPeriodService service;

    @BeforeEach
    void setUp() {
        trialBalance = new FakeTrialBalance();
        periods = new FakePeriodStore();
        service = new CloseLedgerPeriodService(trialBalance, periods, periods);
    }

    @Test
    void 균형_기간은_마감되고_합계_스냅샷_저장() {
        trialBalance.set(MARCH, balanced("10000"));

        LedgerPeriod closed = service.close(MARCH, "admin");

        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getClosedBy()).isEqualTo("admin");
        assertThat(closed.getTotalDebit()).isEqualByComparingTo("10000.00");
        assertThat(closed.getTotalCredit()).isEqualByComparingTo("10000.00");
        assertThat(periods.savedCount).isEqualTo(1);
        assertThat(periods.isClosed(MARCH)).isTrue();
    }

    @Test
    void 이미_마감된_기간_재마감은_기존_스냅샷_반환_no_op() {
        // 선-마감 상태로 저장.
        trialBalance.set(MARCH, balanced("10000"));
        service.close(MARCH, "admin");
        int savedAfterFirst = periods.savedCount;

        // 재마감 — 시산표 재산출/재저장 없이 기존 반환.
        trialBalance.markPoison(MARCH); // 재산출되면 예외 → no-op 이면 호출 안 됨
        LedgerPeriod again = service.close(MARCH, "admin2");

        assertThat(again.isClosed()).isTrue();
        assertThat(again.getClosedBy()).isEqualTo("admin"); // 최초 마감자 유지
        assertThat(periods.savedCount).isEqualTo(savedAfterFirst); // 추가 저장 없음
    }

    @Test
    void 불균형_시산표는_마감_거부_저장없음() {
        trialBalance.set(MARCH, imbalanced("10000", "9000"));

        assertThatThrownBy(() -> service.close(MARCH, "admin"))
                .isInstanceOf(LedgerPeriodImbalanceException.class);
        assertThat(periods.savedCount).isZero();
        assertThat(periods.isClosed(MARCH)).isFalse();
    }

    @Test
    void 필수인자_검증() {
        assertThatThrownBy(() -> service.close(null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.close(MARCH, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ──
    private static LedgerTrialBalance balanced(String amount) {
        Map<AccountType, BigDecimal> d = new EnumMap<>(AccountType.class);
        Map<AccountType, BigDecimal> c = new EnumMap<>(AccountType.class);
        d.put(AccountType.ACCOUNTS_PAYABLE, new BigDecimal(amount));
        c.put(AccountType.REVENUE, new BigDecimal(amount));
        return LedgerTrialBalance.of(MARCH, d, c);
    }

    private static LedgerTrialBalance imbalanced(String debit, String credit) {
        Map<AccountType, BigDecimal> d = new EnumMap<>(AccountType.class);
        Map<AccountType, BigDecimal> c = new EnumMap<>(AccountType.class);
        d.put(AccountType.ACCOUNTS_PAYABLE, new BigDecimal(debit));
        c.put(AccountType.REVENUE, new BigDecimal(credit));
        return LedgerTrialBalance.of(MARCH, d, c);
    }

    // ── fakes ──
    private static class FakeTrialBalance implements GetLedgerTrialBalanceUseCase {
        private final Map<YearMonth, LedgerTrialBalance> store = new HashMap<>();
        private final java.util.Set<YearMonth> poison = new java.util.HashSet<>();

        void set(YearMonth ym, LedgerTrialBalance tb) { store.put(ym, tb); }
        void markPoison(YearMonth ym) { poison.add(ym); }

        @Override
        public LedgerTrialBalance getForPeriod(YearMonth period) {
            if (poison.contains(period)) {
                throw new IllegalStateException("재산출되면 안 됨 (멱등 no-op 위반)");
            }
            return store.get(period);
        }
    }

    private static class FakePeriodStore implements LoadLedgerPeriodPort, SaveLedgerPeriodPort {
        private final Map<String, LedgerPeriod> store = new HashMap<>();
        private final AtomicLong idSeq = new AtomicLong(1);
        int savedCount = 0;

        @Override
        public Optional<LedgerPeriod> findByPeriod(YearMonth period) {
            return Optional.ofNullable(store.get(period.toString()));
        }

        @Override
        public boolean isClosed(YearMonth period) {
            LedgerPeriod p = store.get(period.toString());
            return p != null && p.isClosed();
        }

        @Override
        public LedgerPeriod save(LedgerPeriod period) {
            savedCount++;
            if (period.getId() == null) {
                period.assignId(idSeq.getAndIncrement());
            }
            store.put(period.getPeriodYm(), period);
            return period;
        }
    }
}
