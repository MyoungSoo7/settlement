package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * Port for publishing domain events (Transactional Outbox 경유).
 */
public interface PublishEventPort {
    void publishPaymentCreated(Long paymentId, Long orderId);
    void publishPaymentAuthorized(Long paymentId);

    /**
     * 결제 매입 완료. 컨슈머가 정산 생성에 amount 를 사용한다.
     * sellerMeta(sellerId·tier·cycle)를 동봉하면 settlement 가 정산 생성 시 order DB 조인을
     * 하지 않아도 된다(ADR 0020 Phase 1, Event-Carried State Transfer). 미해석 시 null 허용.
     */
    void publishPaymentCaptured(Long paymentId, Long orderId, BigDecimal amount,
                                java.time.LocalDateTime capturedAt,
                                String paymentMethod, String pgTransactionId,
                                SellerSettlementMeta sellerMeta);

    /** 환불. refundedAmount 를 동봉해 settlement 결제 프로젝션의 환불액·상태를 갱신한다(ADR 0020 Phase 3b-4). */
    void publishPaymentRefunded(Long paymentId, Long orderId, BigDecimal refundedAmount);
}
