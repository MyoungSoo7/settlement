package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
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
    @InjectMocks ChangeOrderStatusService service;

    @Test @DisplayName("주문 취소 성공")
    void cancelOrder_success() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.cancelOrder(1L);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(saveOrderPort).save(any());
    }

    @Test @DisplayName("주문 미존재 시 예외")
    void cancelOrder_notFound() {
        when(loadOrderPort.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelOrder(999L))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
