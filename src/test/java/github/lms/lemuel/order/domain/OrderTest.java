package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * Order 도메인 단위 테스트
 * 비즈니스 로직(상태 전이)을 검증
 */
class OrderTest {

    @Test
    @DisplayName("주문 생성 시 기본 상태는 CREATED")
    void testCreateOrder_DefaultStatus() {
        // When
        Order order = Order.create(1L, new BigDecimal("10000"));

        // Then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getUserId()).isEqualTo(1L);
        assertThat(order.getAmount()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("CREATED 상태에서 complete() 호출 시 PAID로 전이")
    void testComplete_FromCreated() {
        // Given
        Order order = Order.create(1L, new BigDecimal("5000"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);

        // When
        order.complete();

        // Then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("CREATED 상태에서 cancel() 호출 시 CANCELED로 전이")
    void testCancel_FromCreated() {
        // Given
        Order order = Order.create(1L, new BigDecimal("3000"));

        // When
        order.cancel();

        // Then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    @DisplayName("PAID 상태에서 refund() 호출 시 REFUNDED로 전이")
    void testRefund_FromPaid() {
        // Given
        Order order = Order.create(1L, new BigDecimal("7000"));
        order.complete(); // CREATED -> PAID

        // When
        order.refund();

        // Then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("PAID 상태에서 cancel() 시도 시 예외 발생")
    void testCancel_FromPaid_ThrowsException() {
        // Given
        Order order = Order.create(1L, new BigDecimal("8000"));
        order.complete(); // PAID 상태

        // When & Then
        assertThatThrownBy(() -> order.cancel())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only CREATED orders can be canceled");
    }

    @Test
    @DisplayName("CREATED 상태에서 refund() 시도 시 예외 발생")
    void testRefund_FromCreated_ThrowsException() {
        // Given
        Order order = Order.create(1L, new BigDecimal("6000"));

        // When & Then
        assertThatThrownBy(() -> order.refund())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only PAID orders can be refunded");
    }

    @Test
    @DisplayName("금액이 0 이하일 경우 예외 발생")
    void testCreateOrder_InvalidAmount() {
        // When & Then
        assertThatThrownBy(() -> Order.create(1L, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amount must be greater than zero");

        assertThatThrownBy(() -> Order.create(1L, new BigDecimal("-100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amount must be greater than zero");
    }

    @Test
    @DisplayName("userId가 null이거나 0 이하일 경우 예외 발생")
    void testCreateOrder_InvalidUserId() {
        // When & Then
        assertThatThrownBy(() -> Order.create(null, new BigDecimal("1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID must be a positive number");

        assertThatThrownBy(() -> Order.create(0L, new BigDecimal("1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID must be a positive number");
    }

    @Test
    @DisplayName("isCancelable() - CREATED 상태일 때만 true")
    void testIsCancelable() {
        Order createdOrder = Order.create(1L, new BigDecimal("1000"));
        assertThat(createdOrder.isCancelable()).isTrue();

        createdOrder.complete();
        assertThat(createdOrder.isCancelable()).isFalse();
    }

    @Test
    @DisplayName("isRefundable() - PAID 상태일 때만 true")
    void testIsRefundable() {
        Order order = Order.create(1L, new BigDecimal("1000"));
        assertThat(order.isRefundable()).isFalse();

        order.complete();
        assertThat(order.isRefundable()).isTrue();

        order.refund();
        assertThat(order.isRefundable()).isFalse();
    }
}
