package github.lms.lemuel.projectionbackfill;

import github.lms.lemuel.order.adapter.out.persistence.SpringDataOrderJpaRepository;
import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaRepository;
import github.lms.lemuel.product.adapter.out.persistence.SpringDataProductJpaRepository;
import github.lms.lemuel.user.adapter.out.persistence.SpringDataUserJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementReconciliationGaugesTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SpringDataUserJpaRepository userRepo = mock(SpringDataUserJpaRepository.class);
    private final SpringDataProductJpaRepository productRepo = mock(SpringDataProductJpaRepository.class);
    private final SpringDataOrderJpaRepository orderRepo = mock(SpringDataOrderJpaRepository.class);
    private final PaymentJpaRepository paymentRepo = mock(PaymentJpaRepository.class);

    @Test
    @DisplayName("Phase 5.2: view 라벨별로 opslab 원천 행 수를 게이지로 노출한다")
    void exposesSourceRowGaugesPerView() {
        when(userRepo.count()).thenReturn(12L);
        when(productRepo.count()).thenReturn(34L);
        when(orderRepo.count()).thenReturn(56L);
        when(paymentRepo.countByStatus("CAPTURED")).thenReturn(78L);

        new SettlementReconciliationGauges(registry, userRepo, productRepo, orderRepo, paymentRepo);

        assertThat(gauge("user_view")).isEqualTo(12.0);
        assertThat(gauge("product_view")).isEqualTo(34.0);
        assertThat(gauge("order_view")).isEqualTo(56.0);
        assertThat(gauge("payment_view")).isEqualTo(78.0);
    }

    private double gauge(String view) {
        return registry.get("settlement.recon.source.rows").tag("view", view).gauge().value();
    }
}
