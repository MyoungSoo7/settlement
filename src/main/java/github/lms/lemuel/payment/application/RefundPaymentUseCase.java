package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class RefundPaymentUseCase implements RefundPaymentPort {

    private static final Logger log = LoggerFactory.getLogger(RefundPaymentUseCase.class);
    private static final String FULL_REFUND_KEY_PREFIX = "payment-";
    private static final String FULL_REFUND_KEY_SUFFIX = "-full";

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final LoadRefundPort loadRefundPort;
    private final SaveRefundPort saveRefundPort;
    private final AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase;

    public RefundPaymentUseCase(LoadPaymentPort loadPaymentPort,
                                SavePaymentPort savePaymentPort,
                                PgClientPort pgClientPort,
                                UpdateOrderStatusPort updateOrderStatusPort,
                                PublishEventPort publishEventPort,
                                LoadRefundPort loadRefundPort,
                                SaveRefundPort saveRefundPort,
                                AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.pgClientPort = pgClientPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.loadRefundPort = loadRefundPort;
        this.saveRefundPort = saveRefundPort;
        this.adjustSettlementForRefundUseCase = adjustSettlementForRefundUseCase;
    }

    @Override
    public PaymentDomain refundPayment(Long paymentId) {
        PaymentDomain paymentDomain = loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // 1. 멱등성 보장: 이미 환불된 결제는 추가 PG 호출 없이 현재 상태 반환
        if (paymentDomain.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment already refunded, skipping PG call. paymentId={}", paymentId);
            return paymentDomain;
        }

        // 2. 상태 가드를 PG 호출 전에 먼저 실행
        if (paymentDomain.getStatus() != PaymentStatus.CAPTURED) {
            throw new IllegalStateException(
                "Payment must be in CAPTURED status to refund. Current: " + paymentDomain.getStatus()
            );
        }

        // 3. 환불 멱등성 레코드: 동일 paymentId-idempotencyKey 조합으로 이미 COMPLETED 된 Refund 가 있으면 재호출 건너뜀
        String idempotencyKey = fullRefundKey(paymentId);
        Refund refund = loadRefundPort.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey)
                .orElse(null);
        if (refund != null && refund.isCompleted()) {
            log.info("Refund already completed for paymentId={}, key={}", paymentId, idempotencyKey);
            return paymentDomain;
        }

        // 4. Refund 엔티티 생성 (REQUESTED)
        if (refund == null) {
            refund = Refund.request(paymentId, paymentDomain.getAmount(), idempotencyKey, "FULL_REFUND");
            refund = saveRefundPort.save(refund);
        }

        // 5. PG 환불 호출
        pgClientPort.refund(paymentDomain.getPgTransactionId(), paymentDomain.getAmount());

        // 6. Refund COMPLETED 반영
        refund.markCompleted();
        Refund completedRefund = saveRefundPort.save(refund);

        // 7. Payment 도메인 환불 및 저장
        paymentDomain.refund();
        PaymentDomain savedPaymentDomain = savePaymentPort.save(paymentDomain);

        // 8. 주문 상태 REFUNDED
        updateOrderStatusPort.updateOrderStatus(savedPaymentDomain.getOrderId(), "REFUNDED");

        // 9. 이벤트 발행
        publishEventPort.publishPaymentRefunded(savedPaymentDomain.getId(), savedPaymentDomain.getOrderId());

        // 10. 정산 조정 + refundId 전달
        try {
            adjustSettlementForRefundUseCase.adjustSettlementForRefund(
                savedPaymentDomain.getId(),
                savedPaymentDomain.getAmount(),
                completedRefund.getId()
            );
            log.info("Settlement adjusted for refund. paymentId={}, refundId={}",
                    savedPaymentDomain.getId(), completedRefund.getId());
        } catch (Exception e) {
            log.error("Failed to adjust settlement for refund. paymentId={}", savedPaymentDomain.getId(), e);
            // 정산 조정 실패 시에도 환불은 정상 처리
        }

        return savedPaymentDomain;
    }

    private static String fullRefundKey(Long paymentId) {
        return FULL_REFUND_KEY_PREFIX + paymentId + FULL_REFUND_KEY_SUFFIX;
    }
}
