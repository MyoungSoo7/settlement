package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.ConfirmDepositUseCase;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PointTenderPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderType;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입금 확인 — 가상계좌·무통장 결제에 돈이 들어왔을 때 결제를 확정한다.
 *
 * <p>결제 생성 시점에는 승인만 해 두고 아무것도 확정하지 않았다. 여기서 처음으로 PG 매입,
 * 포인트 선점 확정(로트 소비·USE 엔트리), 주문 PAID 전이, {@code payment.captured} 발행이 일어난다.
 *
 * <p><b>비관적 락으로 재조회</b>한다. 입금 통보와 미입금 만료 배치는 독립적으로 도착해 경합하며,
 * 락 없이 스냅샷을 믿으면 둘 다 성공해 "취소된 주문이 결제 완료로 되살아난다".
 *
 * <p><b>멱등</b>이 기능의 일부다 — 웹훅은 같은 통보를 여러 번 보내는 것이 정상이다. 이미 CAPTURED
 * 면 아무것도 다시 하지 않고 그대로 돌려준다. 다시 하면 PG 이중 매입·포인트 이중 차감이 된다.
 *
 * <p>순서는 <b>PG 매입 → 선점 확정 → 상태 전이 → 이벤트</b>다. 외부 왕복(PG)이 실패하면 아무것도
 * 확정되지 않아야 하므로 가장 먼저 시도하고, 이벤트는 모든 것이 확정된 뒤 마지막에 나간다.
 */
@Service
@Transactional
public class ConfirmDepositService implements ConfirmDepositUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmDepositService.class);

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final PgClientPort pgClientPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;
    private final PointTenderPort pointTenderPort;

    public ConfirmDepositService(LoadPaymentPort loadPaymentPort,
                                 SavePaymentPort savePaymentPort,
                                 PgClientPort pgClientPort,
                                 UpdateOrderStatusPort updateOrderStatusPort,
                                 PublishEventPort publishEventPort,
                                 LoadSellerSettlementMetaPort loadSellerSettlementMetaPort,
                                 PointTenderPort pointTenderPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.pgClientPort = pgClientPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.loadSellerSettlementMetaPort = loadSellerSettlementMetaPort;
        this.pointTenderPort = pointTenderPort;
    }

    @Override
    public PaymentDomain confirmDeposit(Long paymentId, Long actorUserId) {
        PaymentDomain payment = loadPaymentPort.loadByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            log.info("입금 확인 멱등 단축 반환: paymentId={}", paymentId);
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.READY) {
            // 만료·취소가 먼저 이겼다. 여기서 확정하면 취소된 주문이 결제 완료로 되살아난다.
            throw new InvalidPaymentStateException(
                    "입금 대기 상태의 결제만 확정할 수 있습니다: " + payment.getStatus());
        }
        if (!payment.awaitsDeposit()) {
            throw new PaymentInvariantViolationException(
                    "입금을 기다리는 결제가 아닙니다: paymentId=" + paymentId
                            + ", method=" + payment.getPaymentMethod());
        }

        // 1) 외부 PG 매입 — 실패하면 아무것도 확정되지 않아야 하므로 가장 먼저 시도한다.
        for (PaymentTender tender : payment.getTenders()) {
            if (tender.getType().usesExternalPg()) {
                pgClientPort.capture(tender.getPgTransactionId(), tender.getAmount());
                tender.capture();
            }
        }

        // 2) 내부 잔액 선점 확정 — 여기서 비로소 로트가 소비되고 USE 엔트리가 남는다.
        for (PaymentTender tender : payment.getTenders()) {
            if (tender.getType() == TenderType.POINT) {
                if (actorUserId == null) {
                    throw new PaymentInvariantViolationException(
                            "선점 확정에는 인증 주체가 필요합니다: paymentId=" + paymentId);
                }
                pointTenderPort.captureHold(tender.getId(), actorUserId);
                tender.capture();
            }
        }

        // 3) 부모 결제 확정.
        payment.authorize("DEPOSIT-" + payment.getOrderId());
        payment.capture();
        PaymentDomain saved = savePaymentPort.save(payment);

        // 4) 주문 전이와 이벤트는 모든 것이 확정된 뒤 마지막에.
        updateOrderStatusPort.updateOrderStatus(saved.getOrderId(), "PAID");
        publishEventPort.publishPaymentCaptured(saved.getId(), saved.getOrderId(), saved.getAmount(),
                saved.getCapturedAt(), saved.getPaymentMethod(), saved.getPgTransactionId(),
                loadSellerSettlementMetaPort.findByPaymentId(saved.getId()).orElse(null));

        log.info("입금 확인 완료: paymentId={}, orderId={}, amount={}",
                saved.getId(), saved.getOrderId(), saved.getAmount());
        return saved;
    }
}
