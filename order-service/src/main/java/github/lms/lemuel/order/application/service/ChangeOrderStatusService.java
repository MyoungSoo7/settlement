package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.RefundOrderPaymentPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.RefundPolicy;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import github.lms.lemuel.product.application.port.in.IncreaseProductStockUseCase;
import github.lms.lemuel.product.application.port.in.IncreaseVariantStockUseCase;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 상태 변경 서비스
 */
@Service
@RequiredArgsConstructor
public class ChangeOrderStatusService implements ChangeOrderStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChangeOrderStatusService.class);

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final SaveOrderStatusHistoryPort historyPort;
    private final RefundOrderPaymentPort refundOrderPaymentPort;
    private final IncreaseProductStockUseCase increaseProductStockUseCase;
    private final IncreaseVariantStockUseCase increaseVariantStockUseCase;

    @Override
    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.cancel();

        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, OrderStatus.CREATED.name(), saved.getStatus().name(), "system", "cancelOrder");
        return saved;
    }

    @Override
    @Transactional
    public Order requestCancellation(Long orderId, String reason, String requestedBy) {
        return changeStatus(orderId, OrderStatus.CANCELLATION_REQUESTED, requestedBy, reason);
    }

    @Override
    @Transactional
    public Order approveCancellation(Long orderId, String reason, String operator) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // 취소 승인은 사용자가 취소를 신청한(CANCELLATION_REQUESTED) 주문에서만 가능하다.
        if (order.getStatus() != OrderStatus.CANCELLATION_REQUESTED) {
            throw new IllegalStateException(
                    "취소 승인은 CANCELLATION_REQUESTED 상태에서만 가능합니다. 현재 상태: " + order.getStatus());
        }
        OrderStatus previous = order.getStatus();

        // 1) 승인 단계 전이(감사 흐름 유지): CANCELLATION_REQUESTED → CANCELLATION_APPROVED
        order.transitionTo(OrderStatus.CANCELLATION_APPROVED);
        saveOrderPort.save(order);

        // 2) 결제가 있으면 전액 환불(취소는 배송 전 전액 환불). 환불되면 payment 가 주문을 REFUNDED 로 전이하고
        //    PaymentRefunded 이벤트를 발행한다(→ settlement 역정산). 미결제 주문이면 환불 없이 false.
        //    PG 실패 시 예외 전파 → 트랜잭션 롤백("성공한 경우에만 확정").
        boolean refunded = refundOrderPaymentPort.refundOrderPaymentFullyIfPresent(orderId);

        // 3) 최종 상태 확정 — 환불됐으면 REFUNDED(payment 가 이미 전이, 멱등 no-op), 미결제면 CANCELED.
        Order finalOrder = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        OrderStatus terminal = refunded ? OrderStatus.REFUNDED : OrderStatus.CANCELED;
        if (finalOrder.getStatus() != terminal) {
            finalOrder.transitionTo(terminal);
            finalOrder = saveOrderPort.save(finalOrder);
        }

        // 4) 승인 이력 + 재고 원복(주문 생성 시 차감한 재고는 결제 여부와 무관하게 되돌린다).
        historyPort.save(orderId, previous.name(), finalOrder.getStatus().name(),
                operator == null || operator.isBlank() ? "system" : operator, reason);
        restoreStock(finalOrder);
        return finalOrder;
    }

    @Override
    @Transactional
    public Order requestRefund(Long orderId, String reason, String requestedBy) {
        return changeStatus(orderId, OrderStatus.REFUND_REQUESTED, requestedBy, reason);
    }

    @Override
    @Transactional
    public Order approveRefund(Long orderId, String reason, String operator) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // 환불 승인은 사용자가 환불을 신청한(REFUND_REQUESTED) 주문에서만 가능하다.
        if (order.getStatus() != OrderStatus.REFUND_REQUESTED) {
            throw new IllegalStateException(
                    "환불 승인은 REFUND_REQUESTED 상태에서만 가능합니다. 현재 상태: " + order.getStatus());
        }
        OrderStatus previous = order.getStatus();

        // 1) 배송 상태 기반 환불 금액 계산 — 배송 시작 후면 배송비를 차감한다(배송 전이면 전액).
        RefundPolicy.RefundOutcome outcome =
                RefundPolicy.forOrder(order.getAmount(), order.getShippingFee(), order.isShipped());

        // 2) 실제 PG 환불 실행. 전액이면 payment 가 주문을 REFUNDED 로 자동 전이하고 PaymentRefunded
        //    이벤트를 발행한다(→ settlement 역정산). 배송비 차감(부분)이면 payment 는 CAPTURED 로 남으므로
        //    3)에서 주문을 명시적으로 REFUNDED 확정한다. PG 실패 시 예외 전파 → 트랜잭션 롤백
        //    ("환불에 성공한 경우에만 주문이 확정").
        if (outcome.deductsShippingFee()) {
            refundOrderPaymentPort.refundOrderPayment(
                    orderId, outcome.refundableAmount(), refundApprovalKey(orderId));
        } else {
            refundOrderPaymentPort.refundOrderPaymentFully(orderId);
        }

        // 3) 환불 성공 후 주문을 REFUNDED 로 확정한다(전액 환불로 이미 전이됐으면 멱등 no-op).
        Order refunded = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (refunded.getStatus() != OrderStatus.REFUNDED) {
            refunded.transitionTo(OrderStatus.REFUNDED);
            refunded = saveOrderPort.save(refunded);
        }

        // 4) 관리자 승인 이력 기록 + 환불된 주문 라인만큼 재고 원복(주문 생성 시 차감의 역연산).
        //    (단건 레거시 주문은 items 가 비어 재고를 차감하지 않았으므로 원복 대상도 없다.)
        historyPort.save(orderId, previous.name(), refunded.getStatus().name(),
                operator == null || operator.isBlank() ? "system" : operator, reason);
        restoreStock(refunded);
        if (outcome.deductsShippingFee()) {
            log.info("환불 승인(배송비 차감): orderId={}, 환불액={}, 차감 배송비={}",
                    orderId, outcome.refundableAmount(), outcome.deductedShippingFee());
        }
        return refunded;
    }

    private static String refundApprovalKey(Long orderId) {
        return "order-" + orderId + "-refund-approve";
    }

    /**
     * 주문 라인 아이템 수량만큼 상품/SKU 재고를 원복한다. 환불·취소 승인 후 공통 사용.
     * 개별 라인 원복 실패(단종 등)는 조용히 스킵되며(하위 서비스가 예외 대신 로그), 전체 흐름을 막지 않는다.
     */
    private void restoreStock(Order order) {
        for (OrderItem item : order.getItems()) {
            if (item.getVariantId() != null) {
                increaseVariantStockUseCase.increase(item.getVariantId(), item.getQuantity());
            } else {
                increaseProductStockUseCase.increase(item.getProductId(), item.getQuantity());
            }
        }
        if (!order.getItems().isEmpty()) {
            log.info("주문 재고 원복 완료: orderId={}, lines={}", order.getId(), order.getItems().size());
        }
    }

    @Override
    @Transactional
    public Order changeShippingStatus(Long orderId, String status, String reason, String operator) {
        OrderStatus target = OrderStatus.valueOf(status.toUpperCase());
        if (target != OrderStatus.SHIPPING_PENDING
                && target != OrderStatus.IN_TRANSIT
                && target != OrderStatus.DELIVERED) {
            throw new IllegalArgumentException("배송 상태로 변경할 수 없는 값입니다: " + status);
        }
        return changeStatus(orderId, target, operator, reason);
    }

    @Override
    @Transactional
    public Order updateStatus(Long orderId, String status) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus target = OrderStatus.valueOf(status);
        OrderStatus previous = order.getStatus();
        order.transitionTo(target);

        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, previous.name(), saved.getStatus().name(), "system", "updateStatus");
        return saved;
    }

    private Order changeStatus(Long orderId, OrderStatus target, String changedBy, String reason) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        OrderStatus previous = order.getStatus();
        order.transitionTo(target);
        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, previous.name(), target.name(),
                changedBy == null || changedBy.isBlank() ? "system" : changedBy, reason);
        return saved;
    }
}
