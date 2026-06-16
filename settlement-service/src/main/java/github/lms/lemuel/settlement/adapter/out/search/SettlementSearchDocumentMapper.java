package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewRepository;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Settlement 도메인을 SettlementSearchDocument 로 변환.
 *
 * <p>ADR 0020 Phase 3b — order 테이블을 @Immutable 매핑하던 read-model 대신 settlement 소유
 * 로컬 프로젝션(settlement_payment/order_view, 이벤트로 적재)에서 통합 색인 문서를 만든다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementSearchDocumentMapper {

    private final SettlementOrderViewRepository orderViewRepository;
    private final SettlementPaymentViewRepository paymentViewRepository;

    public SettlementSearchDocument toDocument(Settlement settlement) {
        SettlementSearchDocument document = new SettlementSearchDocument();

        document.setSettlementId(settlement.getId());
        document.setSettlementStatus(settlement.getStatus().name());
        document.setSettlementAmount(settlement.getNetAmount());
        document.setSettlementDate(settlement.getSettlementDate());
        document.setSettlementConfirmedAt(settlement.getConfirmedAt());

        paymentViewRepository.findById(settlement.getPaymentId()).ifPresentOrElse(
                payment -> mapPaymentInfo(document, payment),
                () -> log.warn("Payment projection not found for settlement: {}", settlement.getId())
        );

        orderViewRepository.findById(settlement.getOrderId()).ifPresentOrElse(
                order -> mapOrderInfo(document, order),
                () -> log.warn("Order projection not found for settlement: {}", settlement.getId())
        );

        document.setIndexedAt(LocalDateTime.now());

        return document;
    }

    private void mapPaymentInfo(SettlementSearchDocument document, SettlementPaymentViewJpaEntity payment) {
        document.setPaymentId(payment.getPaymentId());
        document.setPaymentStatus(payment.getStatus());
        document.setPaymentAmount(payment.getAmount());
        document.setRefundedAmount(payment.getRefundedAmount());
        document.setPaymentMethod(payment.getPaymentMethod());
        document.setPgTransactionId(payment.getPgTransactionId());
        document.setPaymentCapturedAt(payment.getCapturedAt());
    }

    private void mapOrderInfo(SettlementSearchDocument document, SettlementOrderViewJpaEntity order) {
        document.setOrderId(order.getOrderId());
        document.setUserId(order.getUserId());
        document.setOrderStatus(order.getStatus());
        document.setOrderAmount(order.getAmount());
        document.setOrderCreatedAt(order.getCreatedAt());
    }
}
