package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerPeriodPort;
import github.lms.lemuel.ledger.domain.LedgerPeriod;
import github.lms.lemuel.ledger.domain.exception.LedgerPeriodClosedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPeriodGuardTest {

    private FakePeriodPort periods;
    private LedgerPeriodGuard guard;

    @BeforeEach
    void setUp() {
        periods = new FakePeriodPort();
        guard = new LedgerPeriodGuard(periods);
    }

    @Test
    void assertOpenForNewEntry_OPEN_기간은_일자_그대로_반환() {
        LocalDate date = LocalDate.of(2026, 6, 10);
        assertThat(guard.assertOpenForNewEntry(date)).isEqualTo(date);
    }

    @Test
    void assertOpenForNewEntry_CLOSED_기간은_예외() {
        periods.markClosed(YearMonth.of(2026, 3));
        assertThatThrownBy(() -> guard.assertOpenForNewEntry(LocalDate.of(2026, 3, 5)))
                .isInstanceOf(LedgerPeriodClosedException.class);
    }

    @Test
    void assertOpenForNewEntry_null_이면_예외() {
        assertThatThrownBy(() -> guard.assertOpenForNewEntry(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveOpenPostingDate_OPEN_기간은_요청일자_그대로() {
        LocalDate date = LocalDate.of(2026, 6, 15);
        assertThat(guard.resolveOpenPostingDate(date)).isEqualTo(date);
    }

    @Test
    void resolveOpenPostingDate_CLOSED_기간은_다음_OPEN_월_1일로_재지정() {
        periods.markClosed(YearMonth.of(2026, 3));
        assertThat(guard.resolveOpenPostingDate(LocalDate.of(2026, 3, 20)))
                .isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void resolveOpenPostingDate_연속_CLOSED_는_그다음_OPEN_으로_건너뜀() {
        periods.markClosed(YearMonth.of(2026, 3));
        periods.markClosed(YearMonth.of(2026, 4));
        periods.markClosed(YearMonth.of(2026, 5));
        assertThat(guard.resolveOpenPostingDate(LocalDate.of(2026, 3, 1)))
                .isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void resolveOpenPostingDate_null_이면_예외() {
        assertThatThrownBy(() -> guard.resolveOpenPostingDate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static class FakePeriodPort implements LoadLedgerPeriodPort {
        private final Set<YearMonth> closed = new HashSet<>();
        void markClosed(YearMonth ym) { closed.add(ym); }
        @Override public Optional<LedgerPeriod> findByPeriod(YearMonth period) { return Optional.empty(); }
        @Override public boolean isClosed(YearMonth period) { return closed.contains(period); }
    }
}
