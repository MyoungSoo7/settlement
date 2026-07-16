package github.lms.lemuel.investment.adapter.out.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MicrometerInvestmentMetricsAdapter} 가 각 액션을 올바른 Micrometer 카운터(및 reason 태그)에
 * 적재하는지 검증한다.
 */
class MicrometerInvestmentMetricsAdapterTest {

    private SimpleMeterRegistry registry;
    private MicrometerInvestmentMetricsAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        adapter = new MicrometerInvestmentMetricsAdapter(registry);
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    private double countReason(String name, String reason) {
        return registry.get(name).tag("reason", reason).counter().count();
    }

    @Test
    void 신청과_집행은_각각_카운트되고_집행금액이_적재된다() {
        adapter.orderPlaced();
        adapter.orderExecuted(new BigDecimal("1000000"));

        assertThat(count("investment.order.placed")).isEqualTo(1.0);
        assertThat(count("investment.order.executed")).isEqualTo(1.0);
        assertThat(count("investment.order.executed.amount")).isEqualTo(1000000.0);
    }

    @Test
    void 신청거절은_사유_태그별로_분리_적재된다() {
        adapter.orderPlacementRejected("NOT_INVESTABLE");
        adapter.orderPlacementRejected("INSUFFICIENT_FUNDING");
        adapter.orderPlacementRejected("UNKNOWN"); // 미정의 사유는 무시

        assertThat(countReason("investment.order.rejected", "not_investable")).isEqualTo(1.0);
        assertThat(countReason("investment.order.rejected", "insufficient_funding")).isEqualTo(1.0);
    }

    @Test
    void 집행시점_재원부족_거절은_전용_카운터에_적재된다() {
        adapter.orderExecutionRejectedInsufficientFunding();

        assertThat(count("investment.order.execution.insufficient_funding")).isEqualTo(1.0);
    }

    @Test
    void 집행금액이_0이하면_금액은_올리지_않고_건수만_올린다() {
        adapter.orderExecuted(BigDecimal.ZERO);
        adapter.orderExecuted(null);

        assertThat(count("investment.order.executed")).isEqualTo(2.0);
        assertThat(count("investment.order.executed.amount")).isEqualTo(0.0);
    }
}
