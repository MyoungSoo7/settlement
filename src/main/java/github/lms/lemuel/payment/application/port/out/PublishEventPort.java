package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * Port for publishing domain events (Transactional Outbox 경유).
 */
public interface PublishEventPort {
    void publishPaymentCreated(Long paymentId, Long orderId);
    void publishPaymentAuthorized(Long paymentId);

    /**
     * 결제 매입 완료. 컨슈머가 정산 생성에 amount 를 사용하므로 반드시 전달.
     */
    void publishPaymentCaptured(Long paymentId, Long orderId, BigDecimal amount);

    void publishPaymentRefunded(Long paymentId, Long orderId);
}
