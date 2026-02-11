package github.lms.lemuel.search;

import github.lms.lemuel.domain.Order;
import github.lms.lemuel.domain.Payment;
import github.lms.lemuel.domain.Refund;
import github.lms.lemuel.domain.Settlement;
import github.lms.lemuel.repository.OrderRepository;
import github.lms.lemuel.repository.PaymentRepository;
import github.lms.lemuel.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Settlement 엔티티와 관련 데이터를 SettlementSearchDocument로 변환
 */
@Component
public class SettlementSearchDocumentMapper {

    private static final Logger logger = LoggerFactory.getLogger(SettlementSearchDocumentMapper.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public SettlementSearchDocumentMapper(OrderRepository orderRepository,
                                          PaymentRepository paymentRepository,
                                          RefundRepository refundRepository) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    /**
     * Settlement 엔티티를 기반으로 통합 검색 Document 생성
     */
    public SettlementSearchDocument toDocument(Settlement settlement) {
        SettlementSearchDocument document = new SettlementSearchDocument();

        // Settlement 정보 매핑
        document.setSettlementId(settlement.getId());
        document.setSettlementStatus(settlement.getStatus().name());
        document.setSettlementAmount(settlement.getAmount());
        document.setSettlementDate(settlement.getSettlementDate());
        document.setSettlementConfirmedAt(settlement.getConfirmedAt());
        document.setApprovedBy(settlement.getApprovedBy());
        document.setApprovedAt(settlement.getApprovedAt());
        document.setRejectedBy(settlement.getRejectedBy());
        document.setRejectedAt(settlement.getRejectedAt());
        document.setRejectionReason(settlement.getRejectionReason());

        // Payment 정보 조회 및 매핑
        paymentRepository.findById(settlement.getPaymentId()).ifPresentOrElse(
            payment -> mapPaymentInfo(document, payment),
            () -> logger.warn("Payment not found for settlement: {}", settlement.getId())
        );

        // Order 정보 조회 및 매핑
        orderRepository.findById(settlement.getOrderId()).ifPresentOrElse(
            order -> mapOrderInfo(document, order),
            () -> logger.warn("Order not found for settlement: {}", settlement.getId())
        );

        // Refund 정보 조회 및 매핑
        if (settlement.getPaymentId() != null) {
            List<Refund> refunds = refundRepository.findByPaymentId(settlement.getPaymentId());
            mapRefundInfo(document, refunds);
        }

        document.setIndexedAt(LocalDateTime.now());

        return document;
    }

    /**
     * Payment 정보 매핑
     */
    private void mapPaymentInfo(SettlementSearchDocument document, Payment payment) {
        document.setPaymentId(payment.getId());
        document.setPaymentStatus(payment.getStatus().name());
        document.setPaymentAmount(payment.getAmount());
        document.setRefundedAmount(payment.getRefundedAmount());
        document.setPaymentMethod(payment.getPaymentMethod());
        document.setPgTransactionId(payment.getPgTransactionId());
        document.setPaymentCapturedAt(payment.getCapturedAt());
    }

    /**
     * Order 정보 매핑
     */
    private void mapOrderInfo(SettlementSearchDocument document, Order order) {
        document.setOrderId(order.getId());
        document.setUserId(order.getUserId());
        document.setOrderStatus(order.getStatus().name());
        document.setOrderAmount(order.getAmount());
        document.setOrderCreatedAt(order.getCreatedAt());
    }

    /**
     * Refund 정보 매핑 (복수 건의 환불 정보를 취합)
     */
    private void mapRefundInfo(SettlementSearchDocument document, List<Refund> refunds) {
        if (refunds == null || refunds.isEmpty()) {
            document.setHasRefund(false);
            document.setRefundCount(0);
            return;
        }

        document.setHasRefund(true);
        document.setRefundCount(refunds.size());

        // 가장 최근 환불 정보 추출
        Refund latestRefund = refunds.stream()
            .max(Comparator.comparing(Refund::getRequestedAt))
            .orElse(null);

        if (latestRefund != null) {
            document.setLatestRefundStatus(latestRefund.getStatus().name());
            document.setRefundReason(latestRefund.getReason());
            document.setLatestRefundRequestedAt(latestRefund.getRequestedAt());
            document.setLatestRefundCompletedAt(latestRefund.getCompletedAt());
        }
    }
}
