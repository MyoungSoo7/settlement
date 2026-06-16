package github.lms.lemuel.projectionbackfill;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.PublishProductEventPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PublishUserEventPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementProjectionBackfillServiceTest {

    @Mock LoadUserPort loadUserPort;
    @Mock LoadProductPort loadProductPort;
    @Mock LoadOrderPort loadOrderPort;
    @Mock LoadPaymentPort loadPaymentPort;
    @Mock PublishUserEventPort publishUserEventPort;
    @Mock PublishProductEventPort publishProductEventPort;
    @Mock PublishOrderEventPort publishOrderEventPort;
    @Mock PublishEventPort publishEventPort;
    @Mock LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;
    @InjectMocks SettlementProjectionBackfillService service;

    @Test
    @DisplayName("Phase 4 Chunk 3: 기존 user/product/order/payment 를 이벤트로 재발행하고 건수를 반환한다")
    void backfillAll_republishesEachEntityAsEvent() {
        when(loadUserPort.findAll()).thenReturn(List.of(
                User.createWithProfile("a@b.com", "h", UserRole.USER, "n", "010-0000-0000")));
        when(loadProductPort.findAll()).thenReturn(List.of(
                Product.create("원목마루", "desc", new BigDecimal("1000"), 10)));
        when(loadOrderPort.findAll()).thenReturn(List.of(
                Order.create(1L, 2L, new BigDecimal("5000"))));
        when(loadPaymentPort.findAllCaptured()).thenReturn(List.of(
                new PaymentDomain(7L, 8L, new BigDecimal("5000"), BigDecimal.ZERO,
                        PaymentStatus.CAPTURED, "CARD", "pg-x",
                        LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now())));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());

        var result = service.backfillAll();

        assertThat(result.users()).isEqualTo(1);
        assertThat(result.products()).isEqualTo(1);
        assertThat(result.orders()).isEqualTo(1);
        assertThat(result.payments()).isEqualTo(1);

        verify(publishUserEventPort, times(1)).publishUserRegistered(any(), any());
        verify(publishProductEventPort, times(1)).publishProductChanged(any(), any());
        verify(publishOrderEventPort, times(1)).publishOrderCreated(any(), any(), any(), any(), any(), any());
        verify(publishEventPort, times(1)).publishPaymentCaptured(any(), any(), any(), any(), any(), any(), any());
    }
}
