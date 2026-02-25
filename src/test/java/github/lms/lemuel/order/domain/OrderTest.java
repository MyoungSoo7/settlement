package github.lms.lemuel.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * Order Domain Entity TDD Test
 *
 * 테스트 범위:
 * 1. 생성자 및 팩토리 메서드
 * 2. userId 검증
 * 3. amount 검증
 * 4. 비즈니스 메서드 (취소, 완료, 환불)
 * 5. 상태 전이 규칙
 */
@DisplayName("Order 도메인 엔티티")
class OrderTest {

    @Nested
    @DisplayName("생성자 테스트")
    class ConstructorTest {

        @Test
        @DisplayName("기본 생성자로 Order 생성 시 기본값이 설정된다")
        void defaultConstructor() {
            // when
            Order order = new Order();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.getCreatedAt()).isNotNull();
            assertThat(order.getUpdatedAt()).isNotNull();
            assertThat(order.getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
        }

        @Test
        @DisplayName("전체 생성자로 Order 생성 시 모든 필드가 설정된다")
        void allArgsConstructor() {
            // given
            Long id = 1L;
            Long userId = 100L;
            BigDecimal amount = new BigDecimal("10000");
            OrderStatus status = OrderStatus.PAID;
            LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
            LocalDateTime updatedAt = LocalDateTime.now();

            // when
            Order order = new Order(id, userId, amount, status, createdAt, updatedAt);

            // then
            assertThat(order.getId()).isEqualTo(id);
            assertThat(order.getUserId()).isEqualTo(userId);
            assertThat(order.getAmount()).isEqualTo(amount);
            assertThat(order.getStatus()).isEqualTo(status);
            assertThat(order.getCreatedAt()).isEqualTo(createdAt);
            assertThat(order.getUpdatedAt()).isEqualTo(updatedAt);
        }

        @Test
        @DisplayName("전체 생성자에서 status가 null이면 CREATED로 기본 설정된다")
        void allArgsConstructor_WithNullStatus_SetsDefaultStatus() {
            // when
            Order order = new Order(1L, 100L, new BigDecimal("10000"), null, null, null);

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        }

        @Test
        @DisplayName("전체 생성자에서 timestamp가 null이면 현재 시간으로 설정된다")
        void allArgsConstructor_WithNullTimestamps_SetsCurrentTime() {
            // when
            Order order = new Order(1L, 100L, new BigDecimal("10000"), OrderStatus.CREATED, null, null);

            // then
            assertThat(order.getCreatedAt()).isNotNull();
            assertThat(order.getUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("정적 팩토리 메서드 테스트")
    class FactoryMethodTest {

        @Test
        @DisplayName("create() 메서드로 유효한 Order를 생성한다")
        void create_WithValidData_CreatesOrder() {
            // given
            Long userId = 100L;
            BigDecimal amount = new BigDecimal("50000");

            // when
            Order order = Order.create(userId, amount);

            // then
            assertThat(order.getUserId()).isEqualTo(userId);
            assertThat(order.getAmount()).isEqualTo(amount);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.getCreatedAt()).isNotNull();
            assertThat(order.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("create() 메서드는 userId 검증을 수행한다")
        void create_ValidatesUserId() {
            // given
            Long invalidUserId = -1L;
            BigDecimal amount = new BigDecimal("10000");

            // when & then
            assertThatThrownBy(() -> Order.create(invalidUserId, amount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User ID must be a positive number");
        }

        @Test
        @DisplayName("create() 메서드는 amount 검증을 수행한다")
        void create_ValidatesAmount() {
            // given
            Long userId = 100L;
            BigDecimal invalidAmount = BigDecimal.ZERO;

            // when & then
            assertThatThrownBy(() -> Order.create(userId, invalidAmount))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be greater than zero");
        }
    }

    @Nested
    @DisplayName("userId 검증 테스트")
    class UserIdValidationTest {

        @Test
        @DisplayName("유효한 userId는 검증을 통과한다")
        void validateUserId_WithValidId_Passes() {
            // given
            Order order = new Order();
            order.setUserId(1L);

            // when & then
            assertThatCode(() -> order.validateUserId())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null userId는 예외를 발생시킨다")
        void validateUserId_WithNull_ThrowsException() {
            // given
            Order order = new Order();
            order.setUserId(null);

            // when & then
            assertThatThrownBy(() -> order.validateUserId())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User ID must be a positive number");
        }

        @Test
        @DisplayName("0 이하의 userId는 예외를 발생시킨다")
        void validateUserId_WithZeroOrNegative_ThrowsException() {
            // given
            Order order = new Order();

            // when & then
            order.setUserId(0L);
            assertThatThrownBy(() -> order.validateUserId())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User ID must be a positive number");

            order.setUserId(-1L);
            assertThatThrownBy(() -> order.validateUserId())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User ID must be a positive number");
        }
    }

    @Nested
    @DisplayName("amount 검증 테스트")
    class AmountValidationTest {

        @Test
        @DisplayName("유효한 amount는 검증을 통과한다")
        void validateAmount_WithValidAmount_Passes() {
            // given
            Order order = new Order();
            order.setAmount(new BigDecimal("10000"));

            // when & then
            assertThatCode(() -> order.validateAmount())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null amount는 예외를 발생시킨다")
        void validateAmount_WithNull_ThrowsException() {
            // given
            Order order = new Order();
            order.setAmount(null);

            // when & then
            assertThatThrownBy(() -> order.validateAmount())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be greater than zero");
        }

        @Test
        @DisplayName("0 이하의 amount는 예외를 발생시킨다")
        void validateAmount_WithZeroOrNegative_ThrowsException() {
            // given
            Order order = new Order();

            // when & then
            order.setAmount(BigDecimal.ZERO);
            assertThatThrownBy(() -> order.validateAmount())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be greater than zero");

            order.setAmount(new BigDecimal("-100"));
            assertThatThrownBy(() -> order.validateAmount())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be greater than zero");
        }

        @Test
        @DisplayName("다양한 유효한 금액을 허용한다")
        void validateAmount_AcceptsVariousValidAmounts() {
            // given
            Order order = new Order();
            BigDecimal[] validAmounts = {
                    new BigDecimal("0.01"),
                    new BigDecimal("100"),
                    new BigDecimal("10000.50"),
                    new BigDecimal("999999.99")
            };

            // when & then
            for (BigDecimal amount : validAmounts) {
                order.setAmount(amount);
                assertThatCode(() -> order.validateAmount())
                        .as("Amount: " + amount)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("비즈니스 메서드 - 주문 취소")
    class CancelOrderTest {

        @Test
        @DisplayName("CREATED 상태의 주문은 취소할 수 있다")
        void cancel_WithCreatedStatus_SuccessfullyCancels() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            LocalDateTime beforeCancel = order.getUpdatedAt();

            // when
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.cancel();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
            assertThat(order.getUpdatedAt()).isAfter(beforeCancel);
        }

        @Test
        @DisplayName("PAID 상태의 주문은 취소할 수 없다")
        void cancel_WithPaidStatus_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.complete(); // PAID 상태로 변경

            // when & then
            assertThatThrownBy(() -> order.cancel())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be canceled");
        }

        @Test
        @DisplayName("CANCELED 상태의 주문은 다시 취소할 수 없다")
        void cancel_WithCanceledStatus_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.cancel();

            // when & then
            assertThatThrownBy(() -> order.cancel())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be canceled");
        }

        @Test
        @DisplayName("REFUNDED 상태의 주문은 취소할 수 없다")
        void cancel_WithRefundedStatus_ThrowsException() {
            // given
            Order order = new Order();
            order.setUserId(1L);
            order.setAmount(new BigDecimal("10000"));
            order.setStatus(OrderStatus.REFUNDED);

            // when & then
            assertThatThrownBy(() -> order.cancel())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be canceled");
        }
    }

    @Nested
    @DisplayName("비즈니스 메서드 - 주문 완료")
    class CompleteOrderTest {

        @Test
        @DisplayName("CREATED 상태의 주문은 완료할 수 있다")
        void complete_WithCreatedStatus_SuccessfullyCompletes() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            LocalDateTime beforeComplete = order.getUpdatedAt();

            // when
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.complete();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.getUpdatedAt()).isAfter(beforeComplete);
        }

        @Test
        @DisplayName("PAID 상태의 주문은 다시 완료할 수 없다")
        void complete_WithPaidStatus_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.complete();

            // when & then
            assertThatThrownBy(() -> order.complete())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be completed");
        }

        @Test
        @DisplayName("CANCELED 상태의 주문은 완료할 수 없다")
        void complete_WithCanceledStatus_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.cancel();

            // when & then
            assertThatThrownBy(() -> order.complete())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be completed");
        }

        @Test
        @DisplayName("REFUNDED 상태의 주문은 완료할 수 없다")
        void complete_WithRefundedStatus_ThrowsException() {
            // given
            Order order = new Order();
            order.setUserId(1L);
            order.setAmount(new BigDecimal("10000"));
            order.setStatus(OrderStatus.REFUNDED);

            // when & then
            assertThatThrownBy(() -> order.complete())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only CREATED orders can be completed");
        }
    }

    @Nested
    @DisplayName("비즈니스 메서드 - 환불")
    class RefundOrderTest {

        @Test
        @DisplayName("PAID 상태의 주문은 환불할 수 있다")
        void refund_WithPaidStatus_SuccessfullyRefunds() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.complete(); // PAID 상태로 변경
            LocalDateTime beforeRefund = order.getUpdatedAt();

            // when
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.refund();

            // then
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(order.getUpdatedAt()).isAfter(beforeRefund);
        }

        @Test
        @DisplayName("CREATED 상태의 주문은 환불할 수 없다")
        void refund_WithCreatedStatus_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));

            // when & then
            assertThatThrownBy(() -> order.refund())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PAID orders can be refunded");
        }

        @Test
        @DisplayName("CANCELED 상태의 주문은 환불할 수 없다")
        void refund_WithCanceledStatus_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.cancel();

            // when & then
            assertThatThrownBy(() -> order.refund())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PAID orders can be refunded");
        }

        @Test
        @DisplayName("REFUNDED 상태의 주문은 다시 환불할 수 없다")
        void refund_WithRefundedStatus_ThrowsException() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.complete();
            order.refund();

            // when & then
            assertThatThrownBy(() -> order.refund())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PAID orders can be refunded");
        }
    }

    @Nested
    @DisplayName("상태 확인 메서드")
    class StatusCheckMethodTest {

        @Test
        @DisplayName("isCancelable()은 CREATED 상태일 때 true를 반환한다")
        void isCancelable_WithCreatedStatus_ReturnsTrue() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));

            // when & then
            assertThat(order.isCancelable()).isTrue();
        }

        @Test
        @DisplayName("isCancelable()은 PAID 상태일 때 false를 반환한다")
        void isCancelable_WithPaidStatus_ReturnsFalse() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.complete();

            // when & then
            assertThat(order.isCancelable()).isFalse();
        }

        @Test
        @DisplayName("isCancelable()은 CANCELED 상태일 때 false를 반환한다")
        void isCancelable_WithCanceledStatus_ReturnsFalse() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.cancel();

            // when & then
            assertThat(order.isCancelable()).isFalse();
        }

        @Test
        @DisplayName("isRefundable()은 PAID 상태일 때 true를 반환한다")
        void isRefundable_WithPaidStatus_ReturnsTrue() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.complete();

            // when & then
            assertThat(order.isRefundable()).isTrue();
        }

        @Test
        @DisplayName("isRefundable()은 CREATED 상태일 때 false를 반환한다")
        void isRefundable_WithCreatedStatus_ReturnsFalse() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));

            // when & then
            assertThat(order.isRefundable()).isFalse();
        }

        @Test
        @DisplayName("isRefundable()은 CANCELED 상태일 때 false를 반환한다")
        void isRefundable_WithCanceledStatus_ReturnsFalse() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.cancel();

            // when & then
            assertThat(order.isRefundable()).isFalse();
        }

        @Test
        @DisplayName("isRefundable()은 REFUNDED 상태일 때 false를 반환한다")
        void isRefundable_WithRefundedStatus_ReturnsFalse() {
            // given
            Order order = Order.create(1L, new BigDecimal("10000"));
            order.complete();
            order.refund();

            // when & then
            assertThat(order.isRefundable()).isFalse();
        }
    }

    @Nested
    @DisplayName("통합 시나리오 테스트")
    class IntegrationScenarioTest {

        @Test
        @DisplayName("주문 생성 후 결제 완료 시나리오")
        void orderCreationAndPaymentScenario() {
            // given: 주문 생성
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("50000");
            Order order = Order.create(userId, amount);

            // when: 결제 완료
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.isCancelable()).isTrue();
            assertThat(order.isRefundable()).isFalse();

            order.complete();

            // then: 결제 완료 상태 확인
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.isCancelable()).isFalse();
            assertThat(order.isRefundable()).isTrue();
        }

        @Test
        @DisplayName("주문 생성 후 취소 시나리오")
        void orderCreationAndCancellationScenario() {
            // given: 주문 생성
            Order order = Order.create(1L, new BigDecimal("30000"));

            // when: 주문 취소
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            order.cancel();

            // then: 취소 상태 확인
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
            assertThat(order.isCancelable()).isFalse();
            assertThat(order.isRefundable()).isFalse();
        }

        @Test
        @DisplayName("주문 생성 후 결제 완료 후 환불 시나리오")
        void orderCreationPaymentAndRefundScenario() {
            // given: 주문 생성 및 결제 완료
            Order order = Order.create(1L, new BigDecimal("100000"));
            order.complete();

            // when: 환불 처리
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.isRefundable()).isTrue();

            order.refund();

            // then: 환불 상태 확인
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(order.isCancelable()).isFalse();
            assertThat(order.isRefundable()).isFalse();
        }

        @Test
        @DisplayName("주문 상태 전이 규칙 검증: CREATED -> PAID -> REFUNDED")
        void orderStateTransitionValidation() {
            // given
            Order order = Order.create(1L, new BigDecimal("20000"));

            // CREATED 상태 검증
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.isCancelable()).isTrue();
            assertThat(order.isRefundable()).isFalse();

            // PAID 상태로 전이
            order.complete();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.isCancelable()).isFalse();
            assertThat(order.isRefundable()).isTrue();

            // REFUNDED 상태로 전이
            order.refund();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(order.isCancelable()).isFalse();
            assertThat(order.isRefundable()).isFalse();
        }

        @Test
        @DisplayName("주문 금액과 사용자 정보 보존 검증")
        void orderDataPersistenceValidation() {
            // given
            Long userId = 999L;
            BigDecimal amount = new BigDecimal("75000.50");

            // when
            Order order = Order.create(userId, amount);
            order.complete();
            order.refund();

            // then: 상태가 변경되어도 원본 데이터는 보존됨
            assertThat(order.getUserId()).isEqualTo(userId);
            assertThat(order.getAmount()).isEqualByComparingTo(amount);
        }
    }
}
