package github.lms.lemuel.loan.domain;

/**
 * 선지급(LoanAdvance) 생명주기.
 *
 * <pre>
 * REQUESTED → APPROVED → DISBURSED → (부분상환) → REPAID
 *                      ↘ REJECTED               ↘ OVERDUE → WRITTEN_OFF
 * </pre>
 */
public enum LoanStatus {
    REQUESTED,
    APPROVED,
    DISBURSED,
    REPAID,
    REJECTED,
    OVERDUE,
    WRITTEN_OFF
}
