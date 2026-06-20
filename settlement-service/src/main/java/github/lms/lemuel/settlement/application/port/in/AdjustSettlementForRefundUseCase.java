package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.Settlement;

import java.math.BigDecimal;

/**
 * Use Case: 환불 발생 시 정산 조정
 */
public interface AdjustSettlementForRefundUseCase {

    /**
     * 환불 금액을 정산에 반영하여 순 정산 금액 재계산하고
     * settlement_adjustments 에 감사용 음수 레코드를 남긴다.
     *
     * @param paymentId 결제 ID
     * @param refundAmount 환불 금액
     * @param refundId 환불 엔티티 ID (null 허용 — 아직 Refund 생성 전 호출 대비)
     * @return 조정된 정산
     */
    Settlement adjustSettlementForRefund(Long paymentId, BigDecimal refundAmount, Long refundId);

    /**
     * 레거시 호환 오버로드 — 신규 호출부는 refundId 를 함께 넘길 것.
     */
    default Settlement adjustSettlementForRefund(Long paymentId, BigDecimal refundAmount) {
        return adjustSettlementForRefund(paymentId, refundAmount, null);
    }
}
