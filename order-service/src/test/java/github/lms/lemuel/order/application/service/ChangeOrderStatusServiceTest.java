package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.RefundOrderPaymentPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import github.lms.lemuel.product.application.port.in.IncreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.in.IncreaseVariantStockUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChangeOrderStatusServiceTest {

    @Mock LoadOrderPort loadOrderPort;
    @Mock SaveOrderPort saveOrderPort;
    @Mock SaveOrderStatusHistoryPort historyPort;
    @Mock RefundOrderPaymentPort refundOrderPaymentPort;
    @Mock IncreaseProductStockUseCase increaseProductStockUseCase;
    @Mock IncreaseVariantStockUseCase increaseVariantStockUseCase;
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
                .isInstanceOf(InvalidOrderStateException.class);
        verify(saveOrderPort, never()).save(any());
    }

    @Test @DisplayName("환불 승인: PG 환불 실행 후 주문이 REFUNDED 로 확정되고 승인 이력 기록")
    void approveRefund_executesRefund_andConfirmsRefunded() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.REFUND_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        // payment 가 환불 성공 시 주문을 REFUNDED 로 전이하는 부수효과를 시뮬레이션
        doAnswer(inv -> { order.transitionTo(OrderStatus.REFUNDED); return null; })
                .when(refundOrderPaymentPort).refundOrderPaymentFully(1L);

        Order result = service.approveRefund(1L, "고객 변심", "admin");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(refundOrderPaymentPort).refundOrderPaymentFully(1L);
        verify(historyPort).save(eq(1L), eq(OrderStatus.REFUND_REQUESTED.name()),
                eq(OrderStatus.REFUNDED.name()), eq("admin"), eq("고객 변심"));
    }

    @Test @DisplayName("환불 승인: 다건 주문은 라인별로 재고를 원복한다(SKU=variant, 일반=product)")
    void approveRefund_restoresStockPerLine() {
        OrderItem skuLine = OrderItem.newItem(100L, 500L, "SKU-1", "상품A", new BigDecimal("10000"), 2);
        OrderItem plainLine = OrderItem.newItem(200L, null, null, "상품B", new BigDecimal("5000"), 3);
        Order order = Order.createMultiItem(1L, List.of(skuLine, plainLine));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.REFUND_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        doAnswer(inv -> { order.transitionTo(OrderStatus.REFUNDED); return null; })
                .when(refundOrderPaymentPort).refundOrderPaymentFully(1L);

        service.approveRefund(1L, "환불", "admin");

        verify(increaseVariantStockUseCase).increase(500L, 2);
        verify(increaseProductStockUseCase).increase(200L, 3);
    }

    @Test @DisplayName("환불 승인(배송 시작 후): 배송비를 차감한 부분 환불 후 REFUNDED 확정")
    void approveRefund_afterShipping_deductsShippingFee() {
        OrderItem line = OrderItem.newItem(100L, null, null, "상품A", new BigDecimal("30000"), 1);
        Order order = Order.createMultiItem(1L, List.of(line)); // amount = 30000
        order.assignShippingFee(new BigDecimal("3000"));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.SHIPPING_PENDING);
        order.transitionTo(OrderStatus.IN_TRANSIT);          // shipped = true
        order.transitionTo(OrderStatus.REFUND_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 부분 환불이므로 payment 는 주문을 자동 전이하지 않는다(mock no-op).

        Order result = service.approveRefund(1L, "단순 변심", "admin");

        // 배송비 3000 차감 → 27000 만 부분 환불 (전액 환불 경로 미사용)
        verify(refundOrderPaymentPort).refundOrderPayment(
                eq(1L), eq(new BigDecimal("27000")), eq("order-1-refund-approve"));
        verify(refundOrderPaymentPort, never()).refundOrderPaymentFully(anyLong());
        // payment 가 전이하지 못한 주문을 승인 서비스가 REFUNDED 로 확정
        assertThat(result.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test @DisplayName("환불 승인: REFUND_REQUESTED 가 아니면 PG 환불 호출 없이 차단")
    void approveRefund_invalidState_blocked() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID); // REFUND_REQUESTED 아님
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.approveRefund(1L, "사유", "admin"))
                .isInstanceOf(IllegalStateException.class);
        verify(refundOrderPaymentPort, never()).refundOrderPaymentFully(anyLong());
    }

    @Test @DisplayName("취소 승인(결제됨): 전액 환불 실행 후 REFUNDED 확정")
    void approveCancellation_paid_refundsAndBecomesRefunded() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.CANCELLATION_REQUESTED);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundOrderPaymentPort.refundOrderPaymentFullyIfPresent(1L))
                .thenAnswer(inv -> { order.transitionTo(OrderStatus.REFUNDED); return true; });

        Order result = service.approveCancellation(1L, "취소", "admin");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(refundOrderPaymentPort).refundOrderPaymentFullyIfPresent(1L);
    }

    @Test @DisplayName("취소 승인(미결제): 환불 없이 CANCELED 확정")
    void approveCancellation_unpaid_becomesCanceled() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.CANCELLATION_REQUESTED); // CREATED → CANCELLATION_REQUESTED
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundOrderPaymentPort.refundOrderPaymentFullyIfPresent(1L)).thenReturn(false);

        Order result = service.approveCancellation(1L, "취소", "admin");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test @DisplayName("취소 승인: CANCELLATION_REQUESTED 가 아니면 차단")
    void approveCancellation_invalidState_blocked() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.approveCancellation(1L, "취소", "admin"))
                .isInstanceOf(IllegalStateException.class);
        verify(refundOrderPaymentPort, never()).refundOrderPaymentFullyIfPresent(anyLong());
    }

    @Test @DisplayName("changeShippingStatus: 단계 건너뛰기(PAID→DELIVERED)는 차단")
    void changeShippingStatus_skipStage_blocked() {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.transitionTo(OrderStatus.PAID);
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.changeShippingStatus(1L, "DELIVERED", "배송완료", "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(saveOrderPort, never()).save(any());
    }
}
