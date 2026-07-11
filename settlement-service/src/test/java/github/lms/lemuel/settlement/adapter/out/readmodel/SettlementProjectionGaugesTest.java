package github.lms.lemuel.settlement.adapter.out.readmodel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementProjectionGaugesTest {

    @Mock SettlementPaymentViewRepository paymentViewRepository;
    @Mock SettlementOrderViewRepository orderViewRepository;
    @Mock SettlementUserViewRepository userViewRepository;
    @Mock SettlementProductViewRepository productViewRepository;

    @Test
    @DisplayName("생성 시 4개 뷰의 행 수 게이지와 payment/order 금액 게이지를 등록한다")
    void constructor_registersRowAndAmountGauges() {
        when(paymentViewRepository.count()).thenReturn(10L);
        when(orderViewRepository.count()).thenReturn(20L);
        when(userViewRepository.count()).thenReturn(30L);
        when(productViewRepository.count()).thenReturn(40L);
        when(paymentViewRepository.sumCapturedAmount()).thenReturn(new BigDecimal("150000"));
        when(orderViewRepository.sumAmount()).thenReturn(new BigDecimal("250000"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new SettlementProjectionGauges(registry, paymentViewRepository, orderViewRepository,
                userViewRepository, productViewRepository);

        assertThat(registry.get("settlement.projection.rows").tag("view", "payment_view").gauge().value())
                .isEqualTo(10.0);
        assertThat(registry.get("settlement.projection.rows").tag("view", "order_view").gauge().value())
                .isEqualTo(20.0);
        assertThat(registry.get("settlement.projection.rows").tag("view", "user_view").gauge().value())
                .isEqualTo(30.0);
        assertThat(registry.get("settlement.projection.rows").tag("view", "product_view").gauge().value())
                .isEqualTo(40.0);

        assertThat(registry.get("settlement.projection.amount").tag("view", "payment_view").gauge().value())
                .isEqualTo(150000.0);
        assertThat(registry.get("settlement.projection.amount").tag("view", "order_view").gauge().value())
                .isEqualTo(250000.0);
    }

    @Test
    @DisplayName("금액 합계가 null 이면 게이지 값은 0.0 으로 안전 변환된다")
    void amountGauge_nullSum_convertsToZero() {
        when(paymentViewRepository.count()).thenReturn(0L);
        when(orderViewRepository.count()).thenReturn(0L);
        when(userViewRepository.count()).thenReturn(0L);
        when(productViewRepository.count()).thenReturn(0L);
        when(paymentViewRepository.sumCapturedAmount()).thenReturn(null);
        when(orderViewRepository.sumAmount()).thenReturn(null);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new SettlementProjectionGauges(registry, paymentViewRepository, orderViewRepository,
                userViewRepository, productViewRepository);

        assertThat(registry.get("settlement.projection.rows").tag("view", "payment_view").gauge().value())
                .isEqualTo(0.0);
        assertThat(registry.get("settlement.projection.rows").tag("view", "order_view").gauge().value())
                .isEqualTo(0.0);
        assertThat(registry.get("settlement.projection.rows").tag("view", "user_view").gauge().value())
                .isEqualTo(0.0);
        assertThat(registry.get("settlement.projection.rows").tag("view", "product_view").gauge().value())
                .isEqualTo(0.0);
        assertThat(registry.get("settlement.projection.amount").tag("view", "payment_view").gauge().value())
                .isEqualTo(0.0);
        assertThat(registry.get("settlement.projection.amount").tag("view", "order_view").gauge().value())
                .isEqualTo(0.0);
    }
}
