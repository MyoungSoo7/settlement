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
    CANCELED
}
