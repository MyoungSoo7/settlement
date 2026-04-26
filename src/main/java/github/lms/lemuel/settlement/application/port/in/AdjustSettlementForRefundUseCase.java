package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.Settlement;

import java.math.BigDecimal;

public interface AdjustSettlementForRefundUseCase {

    /** @deprecated Task 3.3에서 제거. refundId 포함 버전 사용. */
    @Deprecated
    Settlement adjustSettlementForRefund(Long paymentId, BigDecimal refundAmount);

    /** 환불별 1건 SettlementAdjustment를 INSERT하고 Ledger 분개를 기록한다 (Task 3.3에서 구현). */
    void adjustSettlementForRefund(Long refundId, Long paymentId, BigDecimal refundAmount);
}
