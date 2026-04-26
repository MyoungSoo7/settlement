package github.lms.lemuel.settlement.application.port.in;

import java.math.BigDecimal;

public interface AdjustSettlementForRefundUseCase {

    /**
     * 환불 1건당 SettlementAdjustment INSERT + Ledger recordRefundProcessed 분개 기록.
     * 원 Settlement는 변경하지 않는다 (audit immutability).
     *
     * @param refundId      환불 ID (Adjustment의 refund_id 외래키 + Ledger idempotency)
     * @param paymentId     결제 ID (Settlement 조회용)
     * @param refundAmount  환불 금액 (양수)
     */
    void adjustSettlementForRefund(Long refundId, Long paymentId, BigDecimal refundAmount);
}
