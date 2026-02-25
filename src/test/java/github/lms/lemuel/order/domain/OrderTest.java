package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * Order Domain TDD Tests
 * 순수 도메인 로직 테스트 (Spring Context 불필요)
 */
@DisplayName("Order 도메인 테스트")
class OrderTest {

    @Nested
    @DisplayName("주문 생성 테스트")
    class CreateOrderTest {

        @Test
        @DisplayName("정상적인 주문을 생성할 수 있다")
        void createOrder_Success() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("50000");

            // when
            Order order = Order.create(userId, amount);

            // then
            assertThat(order).isNotNull();
            assertThat(order.getUserId()).isEqualTo(userId);
            assertThat(order.getAmount()).isEqualByComparingTo(amount);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.getCreatedAt()).isNotNull();
            assertThat(order.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("userId가 null이면 예외가 발생한다")
        void createOrder_NullUserId_ThrowsException() {
            // given
            Long userId = null;
            BigDecimal amount = new BigDecimal("50000");

            // when & then
            assertThatThrownBy(() -> Order.create(userId, amount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User ID must be a positive number");
        }

        @Test
        @DisplayName("userId가 0 이하면 예외가 발생한다")
        void createOrder_InvalidUserId_ThrowsException() {
            // given
            Long userId = 0L;
            BigDecimal amount = new BigDecimal("50000");

            // when & then
            assertThatThrownBy(() -> Order.create(userId, amount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User ID must be a positive number");

            assertThatThrownBy(() -> Order.create(-1L, amount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User ID must be a positive number");
        }

        @Test
        @DisplayName("amount가 null이면 예외가 발생한다")
        void createOrder_NullAmount_ThrowsException() {
            // given
            Long userId = 1L;
            BigDecimal amount = null;

            // when & then
            assertThatThrownBy(() -> Order.create(userId, amount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be greater than zero");
        }

        @Test
        @DisplayName("amount가 0 이하면 예외가 발생한다")
        void createOrder_ZeroOrNegativeAmount_ThrowsException() {
            // given
            Long userId = 1L;

            // when & then
            assertThatThrownBy(() -> Order.create(userId, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be greater than zero");

            assertThatThrownBy(() -> Order.create(userId, new BigDecimal("-1000")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be greater than zero");
        }

        @Test
        @DisplayName("초기 상태는 CREATED이다")
        void createOrder_InitialStatus_IsCreated() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("50000");

            // when
            Order order = Order.create(userId, amount);

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.isCancelable()).isTrue();
        }
    }

    @Nested
    @DisplayName("주문 취소 테스트")
    class CancelOrderTest {

        @Test
        @DisplayName("CREATED 상태의 주문을 취소할 수 있다")
        void cancel_CreatedOrder_Success() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));

            // when
            order.cancel();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
            assertThat(order.isCancelable()).isFalse();
        }

        @Test
        @DisplayName("PAID 상태의 주문은 취소할 수 없다")
        void cancel_PaidOrder_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.complete();

            // when & then
            assertThatThrownBy(() -> order.cancel())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be canceled");
        }

        @Test
        @DisplayName("이미 취소된 주문은 다시 취소할 수 없다")
        void cancel_AlreadyCanceled_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.cancel();

            // when & then
            assertThatThrownBy(() -> order.cancel())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be canceled");
        }

        @Test
        @DisplayName("환불된 주문은 취소할 수 없다")
        void cancel_RefundedOrder_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.complete();
            order.refund();

            // when & then
            assertThatThrownBy(() -> order.cancel())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be canceled");
        }
    }

    @Nested
    @DisplayName("주문 완료 테스트")
    class CompleteOrderTest {

        @Test
        @DisplayName("CREATED 상태의 주문을 완료할 수 있다")
        void complete_CreatedOrder_Success() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));

            // when
            order.complete();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.isRefundable()).isTrue();
            assertThat(order.isCancelable()).isFalse();
        }

        @Test
        @DisplayName("이미 완료된 주문은 다시 완료할 수 없다")
        void complete_AlreadyPaid_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.complete();

            // when & then
            assertThatThrownBy(() -> order.complete())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be completed");
        }

        @Test
        @DisplayName("취소된 주문은 완료할 수 없다")
        void complete_CanceledOrder_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.cancel();

            // when & then
            assertThatThrownBy(() -> order.complete())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be completed");
        }
    }

    @Nested
    @DisplayName("주문 환불 테스트")
    class RefundOrderTest {

        @Test
        @DisplayName("PAID 상태의 주문을 환불할 수 있다")
        void refund_PaidOrder_Success() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.complete();

            // when
            order.refund();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(order.isRefundable()).isFalse();
        }

        @Test
        @DisplayName("CREATED 상태의 주문은 환불할 수 없다")
        void refund_CreatedOrder_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));

            // when & then
            assertThatThrownBy(() -> order.refund())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PAID orders can be refunded");
        }

        @Test
        @DisplayName("취소된 주문은 환불할 수 없다")
        void refund_CanceledOrder_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.cancel();

            // when & then
            assertThatThrownBy(() -> order.refund())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PAID orders can be refunded");
        }

        @Test
        @DisplayName("이미 환불된 주문은 다시 환불할 수 없다")
        void refund_AlreadyRefunded_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.complete();
            order.refund();

            // when & then
            assertThatThrownBy(() -> order.refund())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PAID orders can be refunded");
        }
    }

    @Nested
    @DisplayName("주문 상태 전이 테스트")
    class OrderStatusTransitionTest {

        @Test
        @DisplayName("정상 플로우: CREATED -> PAID -> REFUNDED")
        void statusTransition_NormalFlow_Success() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);

            // when: 결제 완료
            order.complete();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

            // when: 환불
            order.refund();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        }

        @Test
        @DisplayName("취소 플로우: CREATED -> CANCELED")
        void statusTransition_CancelFlow_Success() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);

            // when
            order.cancel();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        }

        @Test
        @DisplayName("잘못된 상태 전이는 불가능하다")
        void statusTransition_InvalidFlow_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));

            // when: CREATED 상태에서 환불 시도 (불가능)
            // then
            assertThatThrownBy(() -> order.refund())
                    .isInstanceOf(IllegalStateException.class);

            // when: 완료 후 취소 시도 (불가능)
            order.complete();
            // then
            assertThatThrownBy(() -> order.cancel())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("주문 가능 여부 확인 테스트")
    class OrderAvailabilityTest {

        @Test
        @DisplayName("CREATED 상태에서는 취소 가능하다")
        void isCancelable_CreatedOrder_ReturnsTrue() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));

            // when & then
            assertThat(order.isCancelable()).isTrue();
            assertThat(order.isRefundable()).isFalse();
        }

        @Test
        @DisplayName("PAID 상태에서는 환불 가능하다")
        void isRefundable_PaidOrder_ReturnsTrue() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.complete();

            // when & then
            assertThat(order.isRefundable()).isTrue();
            assertThat(order.isCancelable()).isFalse();
        }

        @Test
        @DisplayName("CANCELED 상태에서는 취소/환불 불가능하다")
        void isCancelableOrRefundable_CanceledOrder_ReturnsFalse() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.cancel();

            // when & then
            assertThat(order.isCancelable()).isFalse();
            assertThat(order.isRefundable()).isFalse();
        }

        @Test
        @DisplayName("REFUNDED 상태에서는 취소/환불 불가능하다")
        void isCancelableOrRefundable_RefundedOrder_ReturnsFalse() {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            order.complete();
            order.refund();

            // when & then
            assertThat(order.isCancelable()).isFalse();
            assertThat(order.isRefundable()).isFalse();
        }
    }

    @Nested
    @DisplayName("주문 금액 테스트")
    class OrderAmountTest {

        @Test
        @DisplayName("주문 금액이 정확하게 저장된다")
        void amount_IsStoredCorrectly() {
            // given
            BigDecimal amount = new BigDecimal("12345.67");

            // when
            Order order = Order.create(1L, amount);

            // then
            assertThat(order.getAmount()).isEqualByComparingTo(amount);
        }

        @Test
        @DisplayName("주문 금액은 소수점 이하도 지원한다")
        void amount_SupportsDecimal() {
            // given
            BigDecimal amount = new BigDecimal("99.99");

            // when
            Order order = Order.create(1L, amount);

            // then
            assertThat(order.getAmount()).isEqualByComparingTo(amount);
        }

        @Test
        @DisplayName("큰 금액도 정확하게 저장된다")
        void amount_SupportsLargeAmount() {
            // given
            BigDecimal amount = new BigDecimal("9999999999.99");

            // when
            Order order = Order.create(1L, amount);

            // then
            assertThat(order.getAmount()).isEqualByComparingTo(amount);
        }
    }

    @Nested
    @DisplayName("주문 시간 정보 테스트")
    class OrderTimestampTest {

        @Test
        @DisplayName("주문 생성 시 생성일시가 자동 설정된다")
        void createdAt_IsSetAutomatically() {
            // when
            Order order = Order.create(1L, new BigDecimal("50000"));

            // then
            assertThat(order.getCreatedAt()).isNotNull();
            assertThat(order.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("주문 상태 변경 시 수정일시가 갱신된다")
        void updatedAt_IsUpdatedOnStatusChange() throws InterruptedException {
            // given
            Order order = Order.create(1L, new BigDecimal("50000"));
            var initialUpdatedAt = order.getUpdatedAt();

            // when
            Thread.sleep(10); // 시간 차이를 만들기 위해
            order.complete();

            // then
            assertThat(order.getUpdatedAt()).isAfter(initialUpdatedAt);
        }
    }
}
