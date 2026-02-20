package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.in.CreateOrderUseCase.CreateOrderCommand;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CreateOrderService 단위 테스트 (TDD)
 */
@ExtendWith(MockitoExtension.class)
class CreateOrderServiceTest {

    @Mock
    private LoadUserForOrderPort loadUserForOrderPort;

    @Mock
    private SaveOrderPort saveOrderPort;

    private CreateOrderService createOrderService;

    @BeforeEach
    void setUp() {
        createOrderService = new CreateOrderService(
                loadUserForOrderPort,
                saveOrderPort
        );
    }

    @Test
    @DisplayName("성공: 새로운 주문 생성")
    void testCreateOrder_Success() {
        // Given
        Long userId = 1L;
        BigDecimal amount = new BigDecimal("10000");
        CreateOrderCommand command = new CreateOrderCommand(userId, amount);

        // 사용자 존재 확인
        when(loadUserForOrderPort.existsById(userId)).thenReturn(true);

        // 주문 저장
        Order savedOrder = Order.create(userId, amount);
        savedOrder.setId(100L);
        when(saveOrderPort.save(any(Order.class))).thenReturn(savedOrder);

        // When
        Order result = createOrderService.createOrder(command);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getAmount()).isEqualByComparingTo(amount);
        assertThat(result.getStatus()).isEqualTo(OrderStatus.CREATED);

        // Verify - 협력 객체 상호작용 검증
        verify(loadUserForOrderPort, times(1)).existsById(userId);
        verify(saveOrderPort, times(1)).save(any(Order.class));
    }

    @Test
    @DisplayName("실패: 존재하지 않는 사용자로 주문 생성 시도")
    void testCreateOrder_UserNotExists() {
        // Given
        Long invalidUserId = 999L;
        BigDecimal amount = new BigDecimal("5000");
        CreateOrderCommand command = new CreateOrderCommand(invalidUserId, amount);

        // 사용자 존재하지 않음
        when(loadUserForOrderPort.existsById(invalidUserId)).thenReturn(false);

        // When & Then
        assertThatThrownBy(() -> createOrderService.createOrder(command))
                .isInstanceOf(UserNotExistsException.class)
                .hasMessageContaining("존재하지 않는 사용자입니다")
                .hasMessageContaining(String.valueOf(invalidUserId));

        // Verify - 사용자 확인 후 저장하지 않음
        verify(loadUserForOrderPort, times(1)).existsById(invalidUserId);
        verify(saveOrderPort, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("검증: SaveOrderPort가 Order 도메인을 저장")
    void testCreateOrder_SavesOrderDomain() {
        // Given
        Long userId = 2L;
        BigDecimal amount = new BigDecimal("25000");
        CreateOrderCommand command = new CreateOrderCommand(userId, amount);

        when(loadUserForOrderPort.existsById(userId)).thenReturn(true);

        Order savedOrder = Order.create(userId, amount);
        savedOrder.setId(200L);
        when(saveOrderPort.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            // 저장되는 Order가 도메인 검증을 통과했는지 확인
            assertThat(order.getUserId()).isEqualTo(userId);
            assertThat(order.getAmount()).isEqualByComparingTo(amount);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            return savedOrder;
        });

        // When
        Order result = createOrderService.createOrder(command);

        // Then
        assertThat(result.getId()).isEqualTo(200L);
        verify(saveOrderPort).save(argThat(order ->
                order.getUserId().equals(userId) &&
                order.getAmount().compareTo(amount) == 0 &&
                order.getStatus() == OrderStatus.CREATED
        ));
    }

    @Test
    @DisplayName("검증: 금액이 0 이하일 경우 예외 발생")
    void testCreateOrder_InvalidAmount() {
        // Given
        Long userId = 3L;
        BigDecimal invalidAmount = BigDecimal.ZERO;
        CreateOrderCommand command = new CreateOrderCommand(userId, invalidAmount);

        when(loadUserForOrderPort.existsById(userId)).thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> createOrderService.createOrder(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amount must be greater than zero");

        verify(saveOrderPort, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("검증: userId가 null일 경우 예외 발생")
    void testCreateOrder_NullUserId() {
        // Given
        CreateOrderCommand command = new CreateOrderCommand(null, new BigDecimal("1000"));

        // When & Then
        assertThatThrownBy(() -> createOrderService.createOrder(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID cannot be null");

        verify(loadUserForOrderPort, never()).existsById(any());
        verify(saveOrderPort, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("협력 객체 상호작용: LoadUserForOrderPort -> SaveOrderPort 순서 확인")
    void testCreateOrder_CollaborationOrder() {
        // Given
        Long userId = 4L;
        BigDecimal amount = new BigDecimal("15000");
        CreateOrderCommand command = new CreateOrderCommand(userId, amount);

        when(loadUserForOrderPort.existsById(userId)).thenReturn(true);

        Order savedOrder = Order.create(userId, amount);
        savedOrder.setId(400L);
        when(saveOrderPort.save(any(Order.class))).thenReturn(savedOrder);

        // When
        createOrderService.createOrder(command);

        // Then - InOrder로 호출 순서 검증
        var inOrder = inOrder(loadUserForOrderPort, saveOrderPort);
        inOrder.verify(loadUserForOrderPort).existsById(userId);
        inOrder.verify(saveOrderPort).save(any(Order.class));
    }
}
