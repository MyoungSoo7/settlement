package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
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

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase;

    public RefundPaymentUseCase(LoadPaymentPort loadPaymentPort,
                                SavePaymentPort savePaymentPort,
                                PgClientPort pgClientPort,
                                UpdateOrderStatusPort updateOrderStatusPort,
                                PublishEventPort publishEventPort,
                                AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.pgClientPort = pgClientPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.adjustSettlementForRefundUseCase = adjustSettlementForRefundUseCase;
    }

    @Override
    public PaymentDomain refundPayment(Long paymentId) {
        PaymentDomain paymentDomain = loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // 멱등성 보장: 이미 환불된 결제는 추가 PG 호출 없이 현재 상태 반환
        if (paymentDomain.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment already refunded, skipping PG call. paymentId={}", paymentId);
            return paymentDomain;
        }

        // 상태 가드를 PG 호출 전에 먼저 실행 (잘못된 상태에서 PG가 호출되는 것을 방지)
        if (paymentDomain.getStatus() != PaymentStatus.CAPTURED) {
            throw new IllegalStateException(
                "Payment must be in CAPTURED status to refund. Current: " + paymentDomain.getStatus()
            );
        }

        // Call external PG to refund (상태 가드 통과 후에만 실행)
        pgClientPort.refund(paymentDomain.getPgTransactionId(), paymentDomain.getAmount());

        // Domain logic
        paymentDomain.refund();

        // Save payment
        PaymentDomain savedPaymentDomain = savePaymentPort.save(paymentDomain);

        // Update order status to REFUNDED
        updateOrderStatusPort.updateOrderStatus(savedPaymentDomain.getOrderId(), "REFUNDED");

        // Publish event
        publishEventPort.publishPaymentRefunded(savedPaymentDomain.getId(), savedPaymentDomain.getOrderId());

        // ========== 정산 조정 (핵심!) ==========
        try {
            adjustSettlementForRefundUseCase.adjustSettlementForRefund(
                savedPaymentDomain.getId(),
                savedPaymentDomain.getAmount()  // 전액 환불 기준
            );
            log.info("Settlement adjusted for refund. paymentId={}", savedPaymentDomain.getId());
        } catch (Exception e) {
            log.error("Failed to adjust settlement for refund. paymentId={}", savedPaymentDomain.getId(), e);
            // 정산 조정 실패 시에도 환불은 정상 처리
        }

        return savedPaymentDomain;
    }
}
