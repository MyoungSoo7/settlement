package github.lms.lemuel.settlement.adapter.in.kafka;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementProjectionMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SettlementProjectionMetrics metrics = new SettlementProjectionMetrics(registry);

    @Test
    @DisplayName("Phase 5.6: 반영 시 type 태그로 applied 카운터와 lag 타이머를 기록한다")
    void recordApply_incrementsCounterAndRecordsLag() {
        long oneSecAgo = System.currentTimeMillis() - 1_000L;

        metrics.recordApply("payment", oneSecAgo);

        double applied = registry.get("settlement.projection.applied").tag("type", "payment").counter().count();
        assertThat(applied).isEqualTo(1.0);

        Timer lag = registry.get("settlement.projection.lag").tag("type", "payment").timer();
        assertThat(lag.count()).isEqualTo(1L);
        assertThat(lag.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Phase 5.6: record timestamp 가 없으면(<=0) lag 타이머는 기록하지 않고 카운터만 올린다")
    void recordApply_skipsLagWhenNoTimestamp() {
        metrics.recordApply("order", -1L);

        double applied = registry.get("settlement.projection.applied").tag("type", "order").counter().count();
        assertThat(applied).isEqualTo(1.0);

        // lag 타이머는 등록되지 않아야 한다
        assertThat(registry.find("settlement.projection.lag").tag("type", "order").timer()).isNull();
    }

    @Test
    @DisplayName("Phase 5.6: 미래 타임스탬프(시계 차이)는 음수 lag 대신 0 으로 보정한다")
    void recordApply_clampsNegativeLagToZero() {
        long future = System.currentTimeMillis() + 60_000L;

        metrics.recordApply("user", future);

        Timer lag = registry.get("settlement.projection.lag").tag("type", "user").timer();
        assertThat(lag.count()).isEqualTo(1L);
        assertThat(lag.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(0.0);
    }
}
