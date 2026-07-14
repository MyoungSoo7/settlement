package github.lms.lemuel.investment.domain;

/**
 * 투자 주문 생명주기.
 *
 * <pre>
 * REQUESTED → APPROVED → EXECUTED
 *           ↘ REJECTED
 * REQUESTED/APPROVED → CANCELED
 * </pre>
 */
public enum InvestmentOrderStatus {
    REQUESTED,
    APPROVED,
    EXECUTED,
    REJECTED,
    CANCELED;

    /**
     * 허용 상태 전이 단일 출처(SettlementStatus#canTransitionTo 동형). 애그리거트(InvestmentOrder)의
     * 전이 가드가 이 표에 위임한다 — 표에 없는 전이는 금지된다.
     *
     * <pre>
     * REQUESTED → APPROVED → EXECUTED
     *           ↘ REJECTED
     * REQUESTED/APPROVED → CANCELED
     * </pre>
     */
    public boolean canTransitionTo(InvestmentOrderStatus target) {
        switch (this) {
            case REQUESTED:
                return target == APPROVED || target == REJECTED || target == CANCELED;
            case APPROVED:
                return target == EXECUTED || target == CANCELED;
            case EXECUTED:
            case REJECTED:
            case CANCELED:
                return false; // 종료 상태
            default:
                return false;
        }
    }
}
