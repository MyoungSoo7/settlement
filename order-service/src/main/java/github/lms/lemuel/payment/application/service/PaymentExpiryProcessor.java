package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.out.CancelUnpaidOrderPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미입금 만료 1건 처리기 — <b>건별 독립 트랜잭션</b>.
 *
 * <p>배치 루프와 트랜잭션 경계를 분리하는 이유: 한 건의 실패(락 타임아웃·주문 상태 경합)가 이미 처리된
 * 앞 건들을 함께 롤백하면 안 되기 때문이다. 실패 건은 다음 주기가 다시 집는다(멱등).
 *
 * <p>스캔 시점의 스냅샷을 믿지 않고 <b>비관적 락으로 재조회해 권위 재검증</b>한다 — 스캔과 처리 사이에
 * 입금이 도착해 승인(AUTHORIZED)됐을 수 있다. 그 경우 도메인 전이 가드가 만료를 차단한다
 * ({@link PaymentDomain#expire()} → READY 에서만 EXPIRED 도달).
 */
@Component
public class PaymentExpiryProcessor {

    /** 주문 취소 이력에 남길 사유 — 운영자가 "왜 취소됐나"를 이력만 보고 알 수 있어야 한다. */
    static final String REASON = "입금 기한 경과(미입금 자동 만료)";

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final CancelUnpaidOrderPort cancelUnpaidOrderPort;

    public PaymentExpiryProcessor(LoadPaymentPort loadPaymentPort,
                                  SavePaymentPort savePaymentPort,
                                  CancelUnpaidOrderPort cancelUnpaidOrderPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.cancelUnpaidOrderPort = cancelUnpaidOrderPort;
    }

    /**
     * 결제를 만료시키고 그 주문을 취소한다.
     *
     * @return 주문까지 취소했으면 true. 결제만 만료하고 주문은 손대지 않았으면 false
     *         (이미 결제·취소된 주문에 잔류 결제 행만 남은 경우)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireAndCancelOrder(Long paymentId) {
        PaymentDomain payment = loadPaymentPort.loadByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        payment.expire();
        savePaymentPort.save(payment);

        return cancelUnpaidOrderPort.cancelUnpaidOrder(payment.getOrderId(), REASON);
    }
}
