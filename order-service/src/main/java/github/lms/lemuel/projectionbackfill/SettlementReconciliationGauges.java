package github.lms.lemuel.projectionbackfill;

import github.lms.lemuel.order.adapter.out.persistence.SpringDataOrderJpaRepository;
import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaRepository;
import github.lms.lemuel.product.adapter.out.persistence.SpringDataProductJpaRepository;
import github.lms.lemuel.user.adapter.out.persistence.SpringDataUserJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.ToDoubleFunction;

/**
 * settlement 프로젝션 cross-DB 대사를 위한 order(opslab) 원천 카운트 게이지 (ADR 0020 Phase 5.2).
 *
 * <p>코드 의존성 0 불변식 유지: order 는 settlement_db 를, settlement 는 opslab 을 직접 읽지 않는다.
 * 대신 양쪽이 각자 행 수를 게이지로 노출하고 <b>Prometheus 관측 계층</b>에서 대조한다.
 *
 * <ul>
 *   <li>order 노출: {@code settlement_recon_source_rows{view}} (이 클래스)</li>
 *   <li>settlement 노출: {@code settlement_projection_rows{view}}
 *       ({@code SettlementProjectionGauges})</li>
 * </ul>
 *
 * <p>드리프트 = {@code max by(view)(settlement_recon_source_rows) -
 * max by(view)(settlement_projection_rows)}. 짧은 복제 lag 은 정상이며,
 * 큰 값이 지속되면 프로젝션 누락 신호 → 자가치유는 {@code POST /admin/settlement-projection/backfill}.
 *
 * <p>view 라벨은 settlement 게이지와 동일 값을 사용해 PromQL {@code on(view)} 대조가 성립한다:
 * user_view = users, product_view = products, order_view = orders,
 * payment_view = CAPTURED 결제 건수.
 *
 * <p>게이지 supplier 는 스크레이프 시점마다 가벼운 {@code count()} 1회를 수행한다(전체 로드 X).
 */
@Component
public class SettlementReconciliationGauges {

    public SettlementReconciliationGauges(MeterRegistry registry,
                                          SpringDataUserJpaRepository userRepository,
                                          SpringDataProductJpaRepository productRepository,
                                          SpringDataOrderJpaRepository orderRepository,
                                          PaymentJpaRepository paymentRepository) {
        register(registry, "user_view", userRepository, r -> (double) r.count());
        register(registry, "product_view", productRepository, r -> (double) r.count());
        register(registry, "order_view", orderRepository, r -> (double) r.count());
        register(registry, "payment_view", paymentRepository, r -> (double) r.countByStatus("CAPTURED"));
    }

    private <T> void register(MeterRegistry registry, String view, T source, ToDoubleFunction<T> count) {
        Gauge.builder("settlement.recon.source.rows", source, count)
                .tag("view", view)
                .description("opslab 원천 행 수 (settlement_" + view + " 프로젝션 대조용)")
                .register(registry);
    }
}
