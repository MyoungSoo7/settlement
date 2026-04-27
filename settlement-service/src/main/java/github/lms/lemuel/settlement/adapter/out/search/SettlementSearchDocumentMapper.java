package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderReadModel;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderReadModelRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentReadModel;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentReadModelRepository;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Settlement 도메인을 SettlementSearchDocument 로 변환.
 *
 * <p>Refund/Order/Payment 정보를 함께 조회해 ES 색인용 통합 문서를 만든다.
 * 모든 cross-domain 데이터는 settlement-service 의 read-only projection 을 통해 읽는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementSearchDocumentMapper {

    private final SettlementOrderReadModelRepository orderReadRepository;
    private final SettlementPaymentReadModelRepository paymentReadRepository;

    public SettlementSearchDocument toDocument(Settlement settlement) {
        SettlementSearchDocument document = new SettlementSearchDocument();

        document.setSettlementId(settlement.getId());
        document.setSettlementStatus(settlement.getStatus().name());
        document.setSettlementAmount(settlement.getNetAmount());
        document.setSettlementDate(settlement.getSettlementDate());
        document.setSettlementConfirmedAt(settlement.getConfirmedAt());

        paymentReadRepository.findById(settlement.getPaymentId()).ifPresentOrElse(
                payment -> mapPaymentInfo(document, payment),
                () -> log.warn("Payment not found for settlement: {}", settlement.getId())
        );

        orderReadRepository.findById(settlement.getOrderId()).ifPresentOrElse(
                order -> mapOrderInfo(document, order),
                () -> log.warn("Order not found for settlement: {}", settlement.getId())
        );

        document.setIndexedAt(LocalDateTime.now());

        return document;
    }

    private void mapPaymentInfo(SettlementSearchDocument document, SettlementPaymentReadModel payment) {
        document.setPaymentId(payment.getId());
        document.setPaymentStatus(payment.getStatus());
        document.setPaymentAmount(payment.getAmount());
        document.setRefundedAmount(payment.getRefundedAmount());
        document.setPaymentMethod(payment.getPaymentMethod());
        document.setPgTransactionId(payment.getPgTransactionId());
        document.setPaymentCapturedAt(payment.getCapturedAt());
    }

    private void mapOrderInfo(SettlementSearchDocument document, SettlementOrderReadModel order) {
        document.setOrderId(order.getId());
        document.setUserId(order.getUserId());
        document.setOrderStatus(order.getStatus());
        document.setOrderAmount(order.getAmount());
        document.setOrderCreatedAt(order.getCreatedAt());
    }
}
