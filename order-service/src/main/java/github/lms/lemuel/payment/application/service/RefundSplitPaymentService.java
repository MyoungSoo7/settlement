package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentDomain.TenderRefundPlan;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 분할결제 환불 — 역순 처리 (sequence 큰 tender 부터).
 *
 * <p>예) 50,000원 = POINT(seq=1, 5,000) + GIFT_CARD(seq=2, 10,000) + CARD(seq=3, 35,000)
 * 에서 30,000원 환불 요청 시:
 * <ol>
 *   <li>CARD 부터 환불 (sequence=3): 30,000 차감 → CARD 잔여 5,000</li>
 *   <li>GIFT_CARD / POINT 는 그대로</li>
 * </ol>
 *
 * <p>왜 역순인가? 외부 PG (CARD) 가 먼저 취소되어야 실 거래가 사라지고, 내부 잔액
 * (POINT/GIFT_CARD) 은 실패해도 운영자가 수동 복원 가능. 반대 순서는 PG 환불 실패 시
 * 내부 잔액만 복원되고 카드는 살아있는 *부분 환불 정합성 깨짐* 발생.
 */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class RefundSplitPaymentService {

    private static final Logger log = LoggerFactory.getLogger(RefundSplitPaymentService.class);

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;

    public RefundSplitPaymentService(LoadPaymentPort loadPaymentPort,
                                      SavePaymentPort savePaymentPort,
                                      PgClientPort pgClientPort,
                                      UpdateOrderStatusPort updateOrderStatusPort,
                                      PublishEventPort publishEventPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.pgClientPort = pgClientPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
    }

    public PaymentDomain refundSplit(Long paymentId, BigDecimal totalRefundAmount) {
        PaymentDomain payment = loadPaymentPort.loadById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (!payment.isSplit()) {
            throw new IllegalStateException("단일결제는 RefundPaymentUseCase 사용");
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new IllegalStateException("CAPTURED 결제만 환불 가능: " + payment.getStatus());
        }

        // 1) 도메인이 역순 환불 계획 수립
        List<TenderRefundPlan> plans = payment.planRefundFromTenders(totalRefundAmount);

        // 2) 각 plan 을 실제 PG/내부 잔액에 적용
        for (TenderRefundPlan plan : plans) {
            var tender = plan.tender();
            BigDecimal portion = plan.amount();

            if (tender.getType().usesExternalPg()) {
                pgClientPort.refund(tender.getPgTransactionId(), portion);
                log.info("외부 PG 환불: tenderId={}, type={}, amount={}",
                        tender.getId(), tender.getType(), portion);
            } else {
                // 실 운영: PointService.restore / GiftCardService.refund
                log.info("내부 잔액 복원: tenderId={}, type={}, amount={}",
                        tender.getId(), tender.getType(), portion);
            }
            tender.addRefund(portion);
        }

        // 3) Payment 도메인의 누적 refundedAmount 갱신
        payment.addRefundedAmount(totalRefundAmount);
        if (payment.isFullyRefunded()) {
            payment.refund();
            updateOrderStatusPort.updateOrderStatus(payment.getOrderId(), "REFUNDED");
        }

        PaymentDomain saved = savePaymentPort.save(payment);
        publishEventPort.publishPaymentRefunded(saved.getId(), saved.getOrderId());

        log.info("분할결제 환불 완료: paymentId={}, refundAmount={}, plansApplied={}",
                paymentId, totalRefundAmount, plans.size());
        return saved;
    }
}
