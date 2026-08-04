package github.lms.lemuel.card.application.port.in;

/**
 * 카드 승인 취소(Void) 유스케이스 포트.
 *
 * <p>매입 전 취소: ACTIVE 또는 PARTIALLY_CAPTURED 홀드를 VOIDED 로 전환하고 가용한도를 복구한다.
 * 취소된 홀드는 한도 합계에서 제외된다.
 */
public interface VoidHoldUseCase {

    void voidHold(VoidHoldCommand command);

    /**
     * 취소 커맨드.
     *
     * @param authorizationId 취소 대상 승인번호(자연키)
     * @param reason          취소 사유(optional — 감사 기록용)
     */
    record VoidHoldCommand(
            String authorizationId,
            String reason
    ) {
    }
}
