package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

class OrderStatusTest {

    @Test @DisplayName("모든 주문 상태값 존재") void allStatuses() {
        assertThat(OrderStatus.values()).contains(
                OrderStatus.CREATED, OrderStatus.PAID,
                OrderStatus.CANCELED, OrderStatus.REFUNDED
        );
    }
}
