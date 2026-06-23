package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChangeOrderStatusServiceTest {

    @Mock LoadOrderPort loadOrderPort;
    @Mock SaveOrderPort saveOrderPort;
    @Mock SaveOrderStatusHistoryPort historyPort;
    @InjectMocks ChangeOrderStatusService service;

    @Test @DisplayName("주문 취소 성공")
    void cancelOrder_success() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.cancelOrder(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(saveOrderPort).save(any());
        verify(historyPort).save(eq(1L), eq(OrderStatus.CREATED.name()),
                eq(OrderStatus.CANCELED.name()), eq("system"), eq("cancelOrder"));
    }

    @Test @DisplayName("주문 미존재 시 예외")
    void cancelOrder_notFound() {
        when(loadOrderPort.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelOrder(999L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test @DisplayName("updateStatus: 정상 전이(CREATED→PAID)는 허용")
    void updateStatus_validTransition() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000")); // CREATED
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.updateStatus(1L, "PAID");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test @DisplayName("updateStatus: 비정상 전이(CREATED→DELIVERED)는 상태머신 가드로 차단")
    void updateStatus_invalidTransition_blocked() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000")); // CREATED
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.updateStatus(1L, "DELIVERED"))
                .isInstanceOf(IllegalStateException.class);
        verify(saveOrderPort, never()).save(any());
    }

    @Test @DisplayName("changeShippingStatus: 단계 건너뛰기(PAID→DELIVERED)는 차단")
    void changeShippingStatus_skipStage_blocked() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.changeShippingStatus(1L, "DELIVERED", "배송완료", "admin"))
                .isInstanceOf(IllegalStateException.class);
        verify(saveOrderPort, never()).save(any());
    }
}
