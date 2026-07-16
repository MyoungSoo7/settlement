package github.lms.lemuel.loan.domain;

/**
 * 선지급(LoanAdvance) 생명주기.
 *
 * <pre>
 * REQUESTED → APPROVED → DISBURSED → (부분상환) → REPAID
 *                      ↘ REJECTED   ↘ OVERDUE → REPAID
 *                                              ↘ WRITTEN_OFF
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
     * DISBURSED → OVERDUE (연체 진입) → REPAID(연체 후 상환) | WRITTEN_OFF(상각)
     * </pre>
     *
     * <p>OVERDUE·WRITTEN_OFF 는 각각 {@code LoanAdvance#markOverdue()}·{@code LoanAdvance#writeOff()}
     * 도메인 메서드의 대상/원천이다. 연체된 대출도 상환되면 REPAID 로 회수될 수 있고, 회수 불능이면
     * WRITTEN_OFF(상각, 종료)로 확정된다. WRITTEN_OFF·REPAID·REJECTED 는 종료 상태다.
     */
    public boolean canTransitionTo(LoanStatus target) {
        switch (this) {
            case REQUESTED:
                return target == APPROVED || target == REJECTED;
            case APPROVED:
                return target == DISBURSED || target == REJECTED;
            case DISBURSED:
                return target == REPAID || target == OVERDUE;
            case OVERDUE:
                return target == REPAID || target == WRITTEN_OFF;
            case REPAID:
            case REJECTED:
            case WRITTEN_OFF:
                return false; // 종료 상태
            default:
                return false;
        }
    }
}
