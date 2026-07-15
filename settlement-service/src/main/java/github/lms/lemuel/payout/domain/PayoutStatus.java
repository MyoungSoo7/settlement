package github.lms.lemuel.payout.domain;

/**
 * 출금 상태머신.
 *
 * <pre>
 *   REQUESTED → SENDING → COMPLETED
 *                       → FAILED → (운영자 retry) → REQUESTED
 *                       → CANCELED (운영자 취소 — 종결)
 * </pre>
 */
public enum PayoutStatus {
    /** 송금 요청됨 — 펌뱅킹 호출 대기 */
    REQUESTED,
    /** 펌뱅킹 호출 진행 중 — 응답 대기 */
    SENDING,
    /** 송금 완료 — 셀러 통장 입금 확인 */
    COMPLETED,
    /** 송금 실패 — 운영자 검토 후 retry / cancel */
    FAILED,
    /** 운영자가 영구 취소 — 종결 상태 */
    CANCELED;

    /**
     * 허용 전이 선언표 — {@link SettlementStatus#canTransitionTo} 동형.
     *
     * <p>{@code Payout} 의 각 전이 메서드(startSending/markCompleted/markFailed/retry/cancel)가
     * 인라인으로 흩어져 있던 {@code status != X} 가드를 이 단일 출처에 위임한다. 허용 집합:
     * <pre>
     *   REQUESTED → SENDING   (startSending)
     *   REQUESTED → CANCELED  (cancel — 요청 철회)
     *   SENDING   → COMPLETED (markCompleted)
     *   SENDING   → FAILED    (markFailed)
     *   FAILED    → REQUESTED (retry)
     *   FAILED    → CANCELED  (cancel)
     *   COMPLETED / CANCELED  → (종결, 전이 없음)
     * </pre>
     * SENDING → CANCELED 는 불허(송금 진행 중 취소 차단).
     */
    public boolean canTransitionTo(PayoutStatus target) {
        switch (this) {
            case REQUESTED:
                return target == SENDING || target == CANCELED;
            case SENDING:
                return target == COMPLETED || target == FAILED;
            case FAILED:
                return target == REQUESTED || target == CANCELED;
            case COMPLETED:
            case CANCELED:
                return false; // 종결 상태
            default:
                return false;
        }
    }
}
