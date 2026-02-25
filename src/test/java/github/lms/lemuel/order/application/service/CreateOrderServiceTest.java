package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateOrderUseCase.CreateOrderCommand;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * CreateOrderService TDD Test
 *
 * 테스트 범위:
 * 1. 정상적인 주문 생성 흐름
 * 2. 사용자 존재 여부 검증
 * 3. 도메인 검증 통합
 * 4. 저장 로직
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateOrderService 애플리케이션 서비스")
class CreateOrderServiceTest {

    @Mock
    private LoadUserForOrderPort loadUserForOrderPort;

    @Mock
    private SaveOrderPort saveOrderPort;

    @InjectMocks
    private CreateOrderService createOrderService;

    @Nested
    @DisplayName("주문 생성 성공 시나리오")
    class SuccessScenario {

        @Test
        @DisplayName("유효한 정보로 주문을 생성한다")
        void createOrder_WithValidData_CreatesOrder() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("50000");
            CreateOrderCommand command = new CreateOrderCommand(userId, amount);

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);
            given(saveOrderPort.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                order.setId(100L);
                return order;
            });

            // when
            Order result = createOrderService.createOrder(command);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(100L);
            assertThat(result.getUserId()).isEqualTo(userId);
            assertThat(result.getAmount()).isEqualByComparingTo(amount);
            assertThat(result.getStatus()).isEqualTo(OrderStatus.CREATED);

            // verify interactions
            then(loadUserForOrderPort).should().existsById(userId);
            then(saveOrderPort).should().save(any(Order.class));
        }

        @Test
        @DisplayName("생성된 주문은 CREATED 상태이다")
        void createOrder_SetsInitialStatusToCreated() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("30000");
            CreateOrderCommand command = new CreateOrderCommand(userId, amount);

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);
            given(saveOrderPort.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                order.setId(1L);
                return order;
            });

            // when
            Order result = createOrderService.createOrder(command);

            // then
            assertThat(result.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(result.isCancelable()).isTrue();
            assertThat(result.isRefundable()).isFalse();
        }

        @Test
        @DisplayName("다양한 금액으로 주문을 생성한다")
        void createOrder_WithVariousAmounts_CreatesOrders() {
            // given
            Long userId = 1L;
            BigDecimal[] amounts = {
                    new BigDecimal("100"),
                    new BigDecimal("1000.50"),
                    new BigDecimal("999999.99")
            };

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);
            given(saveOrderPort.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                order.setId(1L);
                return order;
            });

            // when & then
            for (BigDecimal amount : amounts) {
                CreateOrderCommand command = new CreateOrderCommand(userId, amount);
                Order result = createOrderService.createOrder(command);

                assertThat(result.getAmount()).isEqualByComparingTo(amount);
            }
        }
    }

    @Nested
    @DisplayName("사용자 존재 여부 검증")
    class UserExistenceValidation {

        @Test
        @DisplayName("존재하지 않는 사용자 ID로 주문 생성 시 UserNotExistsException을 발생시킨다")
        void createOrder_WithNonExistentUser_ThrowsException() {
            // given
            Long nonExistentUserId = 999L;
            BigDecimal amount = new BigDecimal("10000");
            CreateOrderCommand command = new CreateOrderCommand(nonExistentUserId, amount);

            given(loadUserForOrderPort.existsById(nonExistentUserId)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> createOrderService.createOrder(command))
                    .isInstanceOf(UserNotExistsException.class);

            // verify
            then(loadUserForOrderPort).should().existsById(nonExistentUserId);
            then(saveOrderPort).should(never()).save(any(Order.class));
        }

        @Test
        @DisplayName("사용자 존재 확인은 주문 생성 전에 수행된다")
        void createOrder_ValidatesUserExistenceFirst() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("10000");
            CreateOrderCommand command = new CreateOrderCommand(userId, amount);

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);
            given(saveOrderPort.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                order.setId(1L);
                return order;
            });

            // when
            createOrderService.createOrder(command);

            // then - 올바른 순서로 호출되었는지 확인
            var inOrder = inOrder(loadUserForOrderPort, saveOrderPort);
            inOrder.verify(loadUserForOrderPort).existsById(userId);
            inOrder.verify(saveOrderPort).save(any(Order.class));
        }
    }

    @Nested
    @DisplayName("도메인 검증 통합")
    class DomainValidationIntegration {

        @Test
        @DisplayName("0 이하의 userId로 주문 생성 시 도메인에서 예외를 발생시킨다")
        void createOrder_WithInvalidUserId_ThrowsException() {
            // given
            Long invalidUserId = -1L;
            BigDecimal amount = new BigDecimal("10000");
            CreateOrderCommand command = new CreateOrderCommand(invalidUserId, amount);

            given(loadUserForOrderPort.existsById(invalidUserId)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> createOrderService.createOrder(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User ID must be a positive number");

            // verify - save should not be called due to validation failure
            then(saveOrderPort).should(never()).save(any(Order.class));
        }

        @Test
        @DisplayName("0 이하의 amount로 주문 생성 시 도메인에서 예외를 발생시킨다")
        void createOrder_WithInvalidAmount_ThrowsException() {
            // given
            Long userId = 1L;
            BigDecimal invalidAmount = BigDecimal.ZERO;
            CreateOrderCommand command = new CreateOrderCommand(userId, invalidAmount);

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> createOrderService.createOrder(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be greater than zero");

            // verify
            then(saveOrderPort).should(never()).save(any(Order.class));
        }

        @Test
        @DisplayName("null userId로 주문 생성 시 도메인에서 예외를 발생시킨다")
        void createOrder_WithNullUserId_ThrowsException() {
            // given
            BigDecimal amount = new BigDecimal("10000");
            CreateOrderCommand command = new CreateOrderCommand(null, amount);

            // when & then
            assertThatThrownBy(() -> createOrderService.createOrder(command))
                    .isInstanceOf(IllegalArgumentException.class);

            // verify
            then(saveOrderPort).should(never()).save(any(Order.class));
        }

        @Test
        @DisplayName("null amount로 주문 생성 시 도메인에서 예외를 발생시킨다")
        void createOrder_WithNullAmount_ThrowsException() {
            // given
            Long userId = 1L;
            CreateOrderCommand command = new CreateOrderCommand(userId, null);

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> createOrderService.createOrder(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be greater than zero");

            // verify
            then(saveOrderPort).should(never()).save(any(Order.class));
        }
    }

    @Nested
    @DisplayName("저장 로직")
    class SaveLogic {

        @Test
        @DisplayName("생성된 주문을 포트를 통해 저장한다")
        void createOrder_SavesOrderThroughPort() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("20000");
            CreateOrderCommand command = new CreateOrderCommand(userId, amount);

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);
            given(saveOrderPort.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                order.setId(42L);
                return order;
            });

            // when
            Order result = createOrderService.createOrder(command);

            // then
            assertThat(result.getId()).isEqualTo(42L);
            then(saveOrderPort).should().save(argThat(order ->
                    order.getUserId().equals(userId) &&
                            order.getAmount().compareTo(amount) == 0 &&
                            order.getStatus() == OrderStatus.CREATED
            ));
        }

        @Test
        @DisplayName("저장 후 ID가 할당된 주문을 반환한다")
        void createOrder_ReturnsOrderWithAssignedId() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("15000");
            CreateOrderCommand command = new CreateOrderCommand(userId, amount);

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);
            given(saveOrderPort.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                order.setId(999L);
                return order;
            });

            // when
            Order result = createOrderService.createOrder(command);

            // then
            assertThat(result.getId()).isNotNull();
            assertThat(result.getId()).isEqualTo(999L);
        }

        @Test
        @DisplayName("저장된 주문은 생성 시간과 수정 시간이 설정된다")
        void createOrder_SetsTimestamps() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("25000");
            CreateOrderCommand command = new CreateOrderCommand(userId, amount);

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);
            given(saveOrderPort.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                order.setId(1L);
                return order;
            });

            // when
            Order result = createOrderService.createOrder(command);

            // then
            assertThat(result.getCreatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Command 검증")
    class CommandValidation {

        @Test
        @DisplayName("유효한 Command로 주문을 생성한다")
        void createCommand_WithValidData_CreatesCommand() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("10000");

            // when & then
            assertThatCode(() -> new CreateOrderCommand(userId, amount))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Command는 userId와 amount를 올바르게 저장한다")
        void createCommand_StoresDataCorrectly() {
            // given
            Long userId = 123L;
            BigDecimal amount = new BigDecimal("45000.50");

            // when
            CreateOrderCommand command = new CreateOrderCommand(userId, amount);

            // then
            assertThat(command.userId()).isEqualTo(userId);
            assertThat(command.amount()).isEqualByComparingTo(amount);
        }
    }

    @Nested
    @DisplayName("전체 통합 시나리오")
    class FullIntegrationScenario {

        @Test
        @DisplayName("주문 생성 전체 플로우: 사용자확인 → 도메인생성 → 저장")
        void createOrder_FullFlow_Success() {
            // given
            Long userId = 1L;
            BigDecimal amount = new BigDecimal("75000.50");
            CreateOrderCommand command = new CreateOrderCommand(userId, amount);

            given(loadUserForOrderPort.existsById(userId)).willReturn(true);
            given(saveOrderPort.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                order.setId(777L);
                return order;
            });

            // when
            Order result = createOrderService.createOrder(command);

            // then - 모든 단계 검증
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(777L);
            assertThat(result.getUserId()).isEqualTo(userId);
            assertThat(result.getAmount()).isEqualByComparingTo(amount);
            assertThat(result.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(result.getCreatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isNotNull();

            // verify - 올바른 순서로 호출되었는지 확인
            var inOrder = inOrder(loadUserForOrderPort, saveOrderPort);
            inOrder.verify(loadUserForOrderPort).existsById(userId);
            inOrder.verify(saveOrderPort).save(any(Order.class));
        }

        @Test
        @DisplayName("여러 사용자가 동시에 주문을 생성할 수 있다")
        void createOrder_MultipleUsers_Success() {
            // given
            Long[] userIds = {1L, 2L, 3L};
            BigDecimal amount = new BigDecimal("10000");

            given(loadUserForOrderPort.existsById(anyLong())).willReturn(true);
            given(saveOrderPort.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                order.setId(order.getUserId() * 100);
                return order;
            });

            // when & then
            for (Long userId : userIds) {
                CreateOrderCommand command = new CreateOrderCommand(userId, amount);
                Order result = createOrderService.createOrder(command);

                assertThat(result.getUserId()).isEqualTo(userId);
                assertThat(result.getAmount()).isEqualByComparingTo(amount);
                assertThat(result.getStatus()).isEqualTo(OrderStatus.CREATED);
            }

            // verify
            then(loadUserForOrderPort).should(times(3)).existsById(anyLong());
            then(saveOrderPort).should(times(3)).save(any(Order.class));
        }
    }
}
