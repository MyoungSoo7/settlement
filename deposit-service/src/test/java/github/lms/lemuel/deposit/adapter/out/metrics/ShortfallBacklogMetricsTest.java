package github.lms.lemuel.deposit.adapter.out.metrics;

import github.lms.lemuel.deposit.application.port.in.ManageShortfallUseCase;
import github.lms.lemuel.deposit.domain.DepositHolderType;
import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 부족분 적체 지표 계약.
 *
 * <p>부족분은 자동으로 해소되지 않으므로, 이 지표가 적체를 밖으로 꺼내는 유일한 창이다.
 * 지표가 틀리면 "적체 0"으로 보이는데 실제로는 쌓여 있는 상태가 된다 — 그 조용한 실패를 막는다.
 */
class ShortfallBacklogMetricsTest {

    private ManageShortfallUseCase useCase;
    private MeterRegistry registry;
    private ShortfallBacklogMetrics metrics;

    @BeforeEach
    void setUp() {
        useCase = mock(ManageShortfallUseCase.class);
        registry = new SimpleMeterRegistry();
        metrics = new ShortfallBacklogMetrics(useCase, registry);
    }

    private static DepositOffsetShortfall shortfall(String requested, String applied) {
        return DepositOffsetShortfall.open(777L, DepositHolderType.CARD_AUTHORIZATION, "CAP-1",
                new BigDecimal(requested), new BigDecimal(applied), null, OffsetDateTime.now());
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("갱신 전에는 0 — 게이지가 등록돼 있어야 스크레이프가 이름을 못 찾는 일이 없다")
    void gaugesRegisteredBeforeFirstRefresh() {
        assertThat(gauge("deposit.shortfall.open.count")).isZero();
        assertThat(gauge("deposit.shortfall.open.amount")).isZero();
    }

    @Test
    @DisplayName("건수와 금액을 둘 다 낸다 — 건수만 보면 대형 1건이 소액 무리에 묻힌다")
    void publishesBothCountAndAmount() {
        when(useCase.findOpenShortfalls()).thenReturn(List.of(
                shortfall("3000", "1000"),      // 부족 2000
                shortfall("5000", "500")));     // 부족 4500

        metrics.refresh();

        assertThat(gauge("deposit.shortfall.open.count")).isEqualTo(2.0);
        assertThat(gauge("deposit.shortfall.open.amount")).isEqualTo(6500.0);
    }

    @Test
    @DisplayName("적체가 해소되면 게이지도 0 으로 내려온다 — 누적이 아니라 스냅샷이다")
    void gaugeFallsBackToZero() {
        when(useCase.findOpenShortfalls())
                .thenReturn(List.of(shortfall("3000", "1000")))
                .thenReturn(List.of());

        metrics.refresh();
        assertThat(gauge("deposit.shortfall.open.count")).isEqualTo(1.0);

        metrics.refresh();
        assertThat(gauge("deposit.shortfall.open.count")).isZero();
        assertThat(gauge("deposit.shortfall.open.amount")).isZero();
    }

    @Test
    @DisplayName("조회가 터져도 서비스를 흔들지 않고, 게이지는 직전 값을 유지한다")
    void keepsLastValueOnFailure() {
        when(useCase.findOpenShortfalls())
                .thenReturn(List.of(shortfall("3000", "1000")))
                .thenThrow(new IllegalStateException("DB 연결 끊김"));

        metrics.refresh();
        assertThatCode(metrics::refresh).doesNotThrowAnyException();

        // 실패를 0 으로 덮어쓰면 "적체 없음"과 구분이 사라진다 — 직전 값이 남아야 한다.
        assertThat(gauge("deposit.shortfall.open.count")).isEqualTo(1.0);
        assertThat(gauge("deposit.shortfall.open.amount")).isEqualTo(2000.0);
    }
}
