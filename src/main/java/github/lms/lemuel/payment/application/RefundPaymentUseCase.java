package github.lms.lemuel.payment.application;

import github.lms.lemuel.common.exception.MissingIdempotencyKeyException;
import github.lms.lemuel.common.exception.RefundExceedsPaymentException;
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

import java.math.BigDecimal;

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
    public PaymentDomain refundPayment(Long paymentId, BigDecimal amount, String idempotencyKey) {
        PaymentDomain paymentDomain = loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // 1. 이미 전액 환불된 결제는 추가 PG 호출 없이 현재 상태 반환
        if (paymentDomain.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment already fully refunded, skipping. paymentId={}", paymentId);
            return paymentDomain;
        }

        // 2. CAPTURED 만 환불 가능
        if (paymentDomain.getStatus() != PaymentStatus.CAPTURED) {
            throw new IllegalStateException(
                "Payment must be in CAPTURED status to refund. Current: " + paymentDomain.getStatus()
            );
        }

        // 3. 환불 금액 결정 및 검증
        boolean isFullRefund = (amount == null);
        BigDecimal refundAmount = isFullRefund ? paymentDomain.getRefundableAmount() : amount;

        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }
        if (refundAmount.compareTo(paymentDomain.getRefundableAmount()) > 0) {
            throw new RefundExceedsPaymentException(
                "Refund amount " + refundAmount + " exceeds refundable " + paymentDomain.getRefundableAmount()
            );
        }

        // 4. 멱등 키 결정: 전액 환불은 기본 키 자동 생성, 부분 환불은 호출자가 반드시 지정
        String effectiveKey;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            effectiveKey = idempotencyKey;
        } else if (isFullRefund) {
            effectiveKey = fullRefundKey(paymentId);
        } else {
            throw new MissingIdempotencyKeyException(
                "Partial refund requires an explicit idempotency key"
            );
        }

        // 5. 멱등성: 동일 키로 이미 COMPLETED 된 Refund 가 있으면 PG 재호출 건너뜀
        Refund refund = loadRefundPort.findByPaymentIdAndIdempotencyKey(paymentId, effectiveKey)
                .orElse(null);
        if (refund != null && refund.isCompleted()) {
            log.info("Refund already completed. paymentId={}, key={}", paymentId, effectiveKey);
            return paymentDomain;
        }

        // 6. Refund 엔티티 생성 (REQUESTED)
        if (refund == null) {
            refund = Refund.request(paymentId, refundAmount, effectiveKey,
                    isFullRefund ? "FULL_REFUND" : "PARTIAL_REFUND");
            refund = saveRefundPort.save(refund);
        }

        // 7. PG 환불 호출 (amount 만큼)
        pgClientPort.refund(paymentDomain.getPgTransactionId(), refundAmount);

        // 8. Refund COMPLETED
        refund.markCompleted();
        Refund completedRefund = saveRefundPort.save(refund);

        // 9. Payment 도메인: 전액 환불이면 REFUNDED 전이, 부분 환불이면 refundedAmount 누적 + 전액 도달 시 REFUNDED
        paymentDomain.addRefundedAmount(refundAmount);
        if (paymentDomain.isFullyRefunded()) {
            paymentDomain.refund();
        }
        PaymentDomain savedPaymentDomain = savePaymentPort.save(paymentDomain);

        // 10. 전액 환불 도달 시점에만 주문 상태 REFUNDED 로 전이 — 부분 환불은 주문 상태 변경 없음
        if (savedPaymentDomain.getStatus() == PaymentStatus.REFUNDED) {
            updateOrderStatusPort.updateOrderStatus(savedPaymentDomain.getOrderId(), "REFUNDED");
        }

        // 11. 이벤트 발행
        publishEventPort.publishPaymentRefunded(savedPaymentDomain.getId(), savedPaymentDomain.getOrderId());

        // 12. 정산 조정 + refundId 전달
        try {
            adjustSettlementForRefundUseCase.adjustSettlementForRefund(
                savedPaymentDomain.getId(),
                refundAmount,
                completedRefund.getId()
            );
            log.info("Settlement adjusted for refund. paymentId={}, refundId={}, refundAmount={}",
                    savedPaymentDomain.getId(), completedRefund.getId(), refundAmount);
        } catch (Exception e) {
            log.error("Failed to adjust settlement for refund. paymentId={}", savedPaymentDomain.getId(), e);
        }

        return savedPaymentDomain;
    }

    private static String fullRefundKey(Long paymentId) {
        return FULL_REFUND_KEY_PREFIX + paymentId + FULL_REFUND_KEY_SUFFIX;
    }
}
