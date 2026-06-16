package github.lms.lemuel.settlement.adapter.out.readmodel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;

/**
 * settlement 로컬 프로젝션 행 수 게이지 (ADR 0020 Phase 5.6).
 *
 * <p>각 {@code settlement_*_view} 의 현재 행 수를 노출한다. opslab(order 원천) 카운트와 대조하면
 * 프로젝션 누락·드리프트를 감지할 수 있어 cross-DB 대사(Phase 5.2)의 1차 신호가 된다.
 *
 * <p>게이지는 스크레이프 시점마다 {@code count()} 를 호출한다(~15s 주기, 뷰당 가벼운 COUNT 쿼리).
 * Kafka 소비 경로와 무관하므로 컨슈머에 영향을 주지 않는다.
 *
 * <p>노출: {@code settlement_projection_rows{view=payment_view|order_view|user_view|product_view}}
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementProjectionGauges {

    public SettlementProjectionGauges(MeterRegistry registry,
                                      SettlementPaymentViewRepository paymentViewRepository,
                                      SettlementOrderViewRepository orderViewRepository,
                                      SettlementUserViewRepository userViewRepository,
                                      SettlementProductViewRepository productViewRepository) {
        register(registry, "payment_view", paymentViewRepository);
        register(registry, "order_view", orderViewRepository);
        register(registry, "user_view", userViewRepository);
        register(registry, "product_view", productViewRepository);
    }

    private void register(MeterRegistry registry, String view, CrudRepository<?, ?> repository) {
        Gauge.builder("settlement.projection.rows", repository, r -> (double) r.count())
                .tag("view", view)
                .description("settlement_" + view + " 프로젝션 행 수 (opslab 원천 대조용)")
                .register(registry);
    }
}
