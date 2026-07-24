package github.lms.lemuel.ledger.domain;

import github.lms.lemuel.ledger.domain.exception.InvalidLedgerPeriodStateException;
import github.lms.lemuel.ledger.domain.exception.LedgerInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPeriodTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Test
    void open_은_OPEN_상태_스냅샷_없음() {
        LedgerPeriod p = LedgerPeriod.open(MARCH);

        assertThat(p.isOpen()).isTrue();
        assertThat(p.isClosed()).isFalse();
        assertThat(p.getPeriodYm()).isEqualTo("2026-03");
        assertThat(p.getClosedAt()).isNull();
        assertThat(p.getClosedBy()).isNull();
        assertThat(p.getTotalDebit()).isNull();
        assertThat(p.getTotalCredit()).isNull();
    }

    @Test
    void open_period_null_이면_예외() {
        assertThatThrownBy(() -> LedgerPeriod.open(null))
                .isInstanceOf(LedgerInvariantViolationException.class);
    }

    @Test
    void close_는_CLOSED_전이_및_합계_스냅샷_못박기() {
        LedgerPeriod p = LedgerPeriod.open(MARCH);

        p.close("admin", new BigDecimal("10000"), new BigDecimal("10000"));

        assertThat(p.isClosed()).isTrue();
        assertThat(p.getClosedBy()).isEqualTo("admin");
        assertThat(p.getClosedAt()).isNotNull();
        assertThat(p.getTotalDebit()).isEqualByComparingTo("10000.00");
        assertThat(p.getTotalCredit()).isEqualByComparingTo("10000.00");
    }

    @Test
    void 이미_CLOSED_인_기간_재마감시도는_상태예외() {
        LedgerPeriod p = LedgerPeriod.open(MARCH);
        p.close("admin", new BigDecimal("10000"), new BigDecimal("10000"));

        assertThatThrownBy(() -> p.close("admin2", new BigDecimal("5000"), new BigDecimal("5000")))
                .isInstanceOf(InvalidLedgerPeriodStateException.class);
    }

    @Test
    void close_closedBy_공백이면_예외() {
        LedgerPeriod p = LedgerPeriod.open(MARCH);
        assertThatThrownBy(() -> p.close("  ", new BigDecimal("1"), new BigDecimal("1")))
                .isInstanceOf(LedgerInvariantViolationException.class);
    }

    @Test
    void close_합계_음수면_예외() {
        LedgerPeriod p = LedgerPeriod.open(MARCH);
        assertThatThrownBy(() -> p.close("admin", new BigDecimal("-1"), new BigDecimal("0")))
                .isInstanceOf(LedgerInvariantViolationException.class);
    }

    @Test
    void close_합계_null_이면_예외() {
        LedgerPeriod p = LedgerPeriod.open(MARCH);
        assertThatThrownBy(() -> p.close("admin", null, new BigDecimal("0")))
                .isInstanceOf(LedgerInvariantViolationException.class);
    }

    @Test
    void covers_는_같은_월의_일자만_true() {
        LedgerPeriod p = LedgerPeriod.open(MARCH);

        assertThat(p.covers(LocalDate.of(2026, 3, 1))).isTrue();
        assertThat(p.covers(LocalDate.of(2026, 3, 31))).isTrue();
        assertThat(p.covers(LocalDate.of(2026, 4, 1))).isFalse();
        assertThat(p.covers(null)).isFalse();
    }

    @Test
    void rehydrate_는_저장상태_그대로_복원() {
        LedgerPeriod p = LedgerPeriod.rehydrate(7L, MARCH, LedgerPeriodStatus.CLOSED,
                java.time.LocalDateTime.now(), "op",
                new BigDecimal("100.00"), new BigDecimal("100.00"), java.time.LocalDateTime.now());

        assertThat(p.getId()).isEqualTo(7L);
        assertThat(p.isClosed()).isTrue();
        assertThat(p.getClosedBy()).isEqualTo("op");
    }

    @Test
    void assignId_는_1회만() {
        LedgerPeriod p = LedgerPeriod.open(MARCH);
        p.assignId(1L);
        assertThatThrownBy(() -> p.assignId(2L)).isInstanceOf(IllegalStateException.class);
    }
}
