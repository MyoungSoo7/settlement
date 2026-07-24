package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerPeriodPort;
import github.lms.lemuel.ledger.domain.LedgerPeriod;
import github.lms.lemuel.ledger.domain.LedgerPeriodStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetLedgerPeriodServiceTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Test
    void 행이_없으면_암묵적_OPEN_반환() {
        GetLedgerPeriodService service = new GetLedgerPeriodService(new FakePort());

        LedgerPeriod p = service.getStatus(MARCH);

        assertThat(p.isOpen()).isTrue();
        assertThat(p.getPeriodYm()).isEqualTo("2026-03");
        assertThat(p.getId()).isNull();
    }

    @Test
    void 저장된_CLOSED_기간은_그대로_반환() {
        FakePort port = new FakePort();
        port.put(LedgerPeriod.rehydrate(5L, MARCH, LedgerPeriodStatus.CLOSED,
                LocalDateTime.now(), "op", new BigDecimal("1.00"), new BigDecimal("1.00"),
                LocalDateTime.now()));
        GetLedgerPeriodService service = new GetLedgerPeriodService(port);

        LedgerPeriod p = service.getStatus(MARCH);

        assertThat(p.isClosed()).isTrue();
        assertThat(p.getId()).isEqualTo(5L);
    }

    @Test
    void period_null_이면_예외() {
        GetLedgerPeriodService service = new GetLedgerPeriodService(new FakePort());
        assertThatThrownBy(() -> service.getStatus(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static class FakePort implements LoadLedgerPeriodPort {
        private final Map<String, LedgerPeriod> store = new HashMap<>();
        void put(LedgerPeriod p) { store.put(p.getPeriodYm(), p); }
        @Override public Optional<LedgerPeriod> findByPeriod(YearMonth period) {
            return Optional.ofNullable(store.get(period.toString()));
        }
        @Override public boolean isClosed(YearMonth period) {
            LedgerPeriod p = store.get(period.toString());
            return p != null && p.isClosed();
        }
    }
}
