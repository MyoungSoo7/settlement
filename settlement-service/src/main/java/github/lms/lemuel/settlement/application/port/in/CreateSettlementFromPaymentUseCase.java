package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.Settlement;

/**
 * Use Case: 결제로부터 정산 생성
 */
public interface CreateSettlementFromPaymentUseCase {

    /**
     * 결제 완료 시 정산 자동 생성
     * Idempotent: 동일한 paymentId로 여러 번 호출해도 한 번만 생성됨
     *
     * @param paymentId 결제 ID
     * @param orderId 주문 ID
     * @param amount 결제 금액
     * @return 생성된 정산 (이미 존재하면 기존 정산 반환)
     */
    Settlement createSettlementFromPayment(Long paymentId, Long orderId, java.math.BigDecimal amount);

    /**
     * Event-Carried State Transfer 경로 (ADR 0020 Phase 1).
     * 이벤트에 동봉된 셀러 메타(sellerId·tier·cycle)를 사용해 order DB 조인 없이 정산을 생성한다.
     * 각 값이 null 이면 기존 조인 포트로 fallback 한다(dual-run / 하위호환).
     *
     * @param sellerTier      enum 이름 문자열(예: "VIP"), null 이면 fallback
     * @param settlementCycle enum 이름 문자열, null 이면 fallback
     * @param sellerId        null 이면 fallback
     */
    Settlement createSettlementFromPayment(Long paymentId, Long orderId, java.math.BigDecimal amount,
                                           Long sellerId, String sellerTier, String settlementCycle);

    /**
     * 결제 시각(paymentCapturedAt)을 함께 받아 정산일을 <b>결제일 기준</b>으로 계산하는 정본 경로.
     *
     * <p>{@link github.lms.lemuel.settlement.domain.SettlementCycle} 계약은 정산일을
     * "결제 발생일(paymentDate)" 기준 T+N 으로 정의한다. 소비 시각({@code now()})을 넘기면 컨슈머
     * 지연·백필·재처리 시 같은 결제가 매번 다른 정산일을 얻어 회계가 흔들린다. 따라서 결제 이벤트가
     * 실어 온 결제 시각의 날짜(KST)를 기준일로 넘긴다.
     *
     * @param paymentCapturedAt 결제 확정 시각(payment.captured 의 capturedAt). null 이면 KST 현재일로 폴백.
     */
    Settlement createSettlementFromPayment(Long paymentId, Long orderId, java.math.BigDecimal amount,
                                           Long sellerId, String sellerTier, String settlementCycle,
                                           java.time.LocalDateTime paymentCapturedAt);
}
