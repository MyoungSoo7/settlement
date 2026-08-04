package github.lms.lemuel.card.application.port.in;

/**
 * 카드 환불(Refund) 유스케이스 포트.
 *
 * <p>매입 후 환불: CAPTURED 또는 PARTIALLY_CAPTURED 홀드를 REFUNDED 로 전환하고 가용한도를 복구한다.
 * 취소(void)와의 차이: 취소는 매입 전에만 가능하고, 환불은 매입 후에만 가능하다.
 */
public interface RefundHoldUseCase {

    void refund(RefundHoldCommand command);

    /**
     * 환불 커맨드.
     *
     * @param authorizationId 환불 대상 승인번호(자연키)
     * @param reason          환불 사유(optional — 감사 기록용)
     */
    record RefundHoldCommand(
            String authorizationId,
            String reason
    ) {
    }
}
