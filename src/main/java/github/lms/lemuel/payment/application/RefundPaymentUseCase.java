package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.RefundCommand;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.application.port.out.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RefundPaymentUseCase implements RefundPaymentPort {

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final LoadRefundPort loadRefundPort;
    private final SaveRefundPort saveRefundPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase;

    @Override
    public Refund refund(RefundCommand command) {
        // 1. 멱등성 체크
        var existing = loadRefundPort.findByPaymentIdAndIdempotencyKey(
                command.paymentId(), command.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent replay: returning existing refund. paymentId={}, key={}",
                    command.paymentId(), command.idempotencyKey());
            return existing.get();
        }

        // 2. 결제 로드 + 도메인 invariant 검증
        PaymentDomain payment = loadPaymentPort.loadById(command.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(command.paymentId()));

        payment.requestRefund(command.refundAmount()); // 누적 검증 + 상태 전이

        // 3. PG 호출 (실패 시 트랜잭션 롤백)
        pgClientPort.refund(payment.getPgTransactionId(), command.refundAmount());

        // 4. Payment 업데이트
        PaymentDomain savedPayment = savePaymentPort.save(payment);

        // 5. Refund INSERT
        Refund refund = Refund.request(
                command.paymentId(), command.refundAmount(),
                command.idempotencyKey(), command.reason());
        refund.markCompleted();
        Refund savedRefund = saveRefundPort.save(refund);

        // 6. 전액 환불 시 주문 상태 동기화
        if (savedPayment.isFullyRefunded()) {
            updateOrderStatusPort.updateOrderStatus(savedPayment.getOrderId(), "REFUNDED");
        }

        // 7. 이벤트
        publishEventPort.publishPaymentRefunded(savedPayment.getId(), savedPayment.getOrderId());

        // 8. 정산 조정 (Adjustment + Ledger) — Task 3.3에서 실제 구현 완성
        adjustSettlementForRefundUseCase.adjustSettlementForRefund(
                savedRefund.getId(), savedPayment.getId(), command.refundAmount());

        log.info("Refund completed. refundId={}, paymentId={}, amount={}",
                savedRefund.getId(), savedPayment.getId(), command.refundAmount());

        return savedRefund;
    }
}
