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
    WRITTEN_OFF;

    /**
     * 허용 상태 전이 단일 출처(SettlementStatus#canTransitionTo 동형). 애그리거트(LoanAdvance)의
     * 전이 가드가 이 표에 위임한다 — 표에 없는 전이는 금지된다.
     *
     * <pre>
     * REQUESTED → APPROVED → DISBURSED → REPAID
     * REQUESTED/APPROVED ↘ REJECTED
     * </pre>
     *
     * <p>OVERDUE·WRITTEN_OFF 는 아직 도메인 전이 메서드가 없어 어떤 전이의 원천/대상도 아니다
     * (현행 애그리거트가 수행하는 전이만 표로 옮긴다 — 미구현 전이는 추가하지 않는다).
     */
    public boolean canTransitionTo(LoanStatus target) {
        switch (this) {
            case REQUESTED:
                return target == APPROVED || target == REJECTED;
            case APPROVED:
                return target == DISBURSED || target == REJECTED;
            case DISBURSED:
                return target == REPAID;
            case REPAID:
            case REJECTED:
            case OVERDUE:
            case WRITTEN_OFF:
                return false; // 종료 상태(또는 미구현 전이)
            default:
                return false;
        }
    }
}
