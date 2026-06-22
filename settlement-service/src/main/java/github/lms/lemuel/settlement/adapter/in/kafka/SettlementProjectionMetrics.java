package github.lms.lemuel.settlement.adapter.in.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * settlement 로컬 프로젝션(CQRS read model) 적재 관측 지표 (ADR 0020 Phase 5.6).
 *
 * <p>order 도메인 이벤트가 settlement_db 프로젝션에 반영되기까지의 지연·처리량을 노출해
 * DB 물리 분리(Phase 4) 이후의 eventual consistency 를 운영에서 감시한다.
 *
 * <p>노출 지표 (Prometheus 변환명):
 * <ul>
 *   <li>{@code settlement_projection_applied_total{type}} — 프로젝션에 반영된 이벤트 누적</li>
 *   <li>{@code settlement_projection_lag_seconds{type}} — 발행→반영 end-to-end 지연 타이머
 *       (Kafka record timestamp 기준). p95/p99 로 복제 지연 추적</li>
 * </ul>
 * type ∈ {payment, order, user, product}.
 */
@Component
public class SettlementProjectionMetrics {

    private final MeterRegistry registry;

    public SettlementProjectionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 프로젝션 1건 반영을 기록한다.
     *
     * @param type                 프로젝션 종류 (payment/order/user/product)
     * @param eventTimestampMillis 원본 이벤트의 Kafka record timestamp(ms). 0 이하면 lag 미기록.
     */
    public void recordApply(String type, long eventTimestampMillis) {
        registry.counter("settlement.projection.applied", "type", type).increment();

        if (eventTimestampMillis > 0L) {
            long lagMs = System.currentTimeMillis() - eventTimestampMillis;
            if (lagMs < 0L) {
                lagMs = 0L; // 시계 차이로 인한 음수 방어
            }
            registry.timer("settlement.projection.lag", "type", type)
                    .record(Duration.ofMillis(lagMs));
        }
    }
}
