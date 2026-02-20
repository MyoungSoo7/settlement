package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.order.adapter.out.persistence.OrderJpaEntity;
import github.lms.lemuel.order.adapter.out.persistence.SpringDataOrderJpaRepository;
import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaEntity;
import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaRepository;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Settlement 도메인을 SettlementSearchDocument로 변환
 * Refund/Order/Payment 관련 정보도 함께 조회하여 통합 문서 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementSearchDocumentMapper {

    private final SpringDataOrderJpaRepository orderRepository;
    private final PaymentJpaRepository paymentRepository;
    // TODO: RefundRepository가 settlement 모듈로 이동되면 주입
    // private final RefundRepository refundRepository;

    /**
     * Settlement 도메인을 기반으로 통합 검색 Document 생성
     */
    public SettlementSearchDocument toDocument(Settlement settlement) {
        SettlementSearchDocument document = new SettlementSearchDocument();

        // Settlement 정보 매핑
        document.setSettlementId(settlement.getId());
        document.setSettlementStatus(settlement.getStatus().name());
        document.setSettlementAmount(settlement.getNetAmount()); // 실 지급액
        document.setSettlementDate(settlement.getSettlementDate());
        document.setSettlementConfirmedAt(settlement.getConfirmedAt());

        // Payment 정보 조회 및 매핑
        paymentRepository.findById(settlement.getPaymentId()).ifPresentOrElse(
                payment -> mapPaymentInfo(document, payment),
                () -> log.warn("Payment not found for settlement: {}", settlement.getId())
        );

        // Order 정보 조회 및 매핑
        orderRepository.findById(settlement.getOrderId()).ifPresentOrElse(
                order -> mapOrderInfo(document, order),
                () -> log.warn("Order not found for settlement: {}", settlement.getId())
        );

        // TODO: Refund 정보 조회 및 매핑 (refundRepository 이동 후)
        // mapRefundInfo(document, settlement.getPaymentId());

        document.setIndexedAt(LocalDateTime.now());

        return document;
    }

    /**
     * Payment 정보 매핑
     */
    private void mapPaymentInfo(SettlementSearchDocument document, PaymentJpaEntity payment) {
        document.setPaymentId(payment.getId());
        document.setPaymentStatus(payment.getStatus());
        document.setPaymentAmount(payment.getAmount());
        document.setRefundedAmount(payment.getRefundedAmount());
        document.setPaymentMethod(payment.getPaymentMethod());
        document.setPgTransactionId(payment.getPgTransactionId());
        document.setPaymentCapturedAt(payment.getCapturedAt());
    }

    /**
     * Order 정보 매핑
     */
    private void mapOrderInfo(SettlementSearchDocument document, OrderJpaEntity order) {
        document.setOrderId(order.getId());
        document.setUserId(order.getUserId());
        document.setOrderStatus(order.getStatus());
        document.setOrderAmount(order.getAmount());
        document.setOrderCreatedAt(order.getCreatedAt());
    }
}
