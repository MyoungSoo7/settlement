package github.lms.lemuel.loan.domain;

/**
 * 담보/개인신용 대출 생명주기.
 *
 * <pre>
 * REQUESTED → APPROVED → DISBURSED → REPAID
 *           ↘ REJECTED ↙            ↘ OVERDUE → REPAID
 *                                             ↘ DEFAULTED → REPAID
 * </pre>
 *
 * <p>기존 두 대출({@link LoanStatus}·{@link CorporateLoanStatus})과 달리 <b>연체(OVERDUE)와
 * 기한이익상실(DEFAULTED)이 처음부터 들어가 있다</b> — 장기 분할상환 상품이라 회차 미납이 예외가 아니라
 * 정상 흐름의 일부이기 때문이다. 나중에 상태를 끼워 넣는 리팩터링은 이미 실행된 대출의 이력 해석을
 * 바꾸므로, 상품 도입 시점에 확정한다.
 *
 * <p><b>기한이익상실은 연체를 거쳐야만 도달한다</b>(DISBURSED → DEFAULTED 직행 금지) — 잔액 전액을
 * 즉시 청구하는 중대한 조치라 연체 사실이 선행 기록되어야 근거가 남는다. 상각(WRITTEN_OFF)은 담보 실행
 * 결과에 종속되므로 Phase 2 이월이다.
 */
public enum SecuredLoanStatus {
    REQUESTED,
    APPROVED,
    DISBURSED,
    /** 회차 미납 — 아직 기한의 이익은 유지된다. */
    OVERDUE,
    /** 기한이익상실 — 잔여 원금 전액을 즉시 청구한다. */
    DEFAULTED,
    REPAID,
    REJECTED,
    /** 상각 — 담보 실행 후 회수 부족분을 손실로 확정. 종료 상태. */
    WRITTEN_OFF;

    /**
     * 허용 상태 전이 단일 출처({@code CorporateLoanStatus#canTransitionTo} 동형). 애그리거트
     * {@link SecuredLoan} 의 전이 가드가 이 표에 위임하므로, 표에 없는 전이는 애그리거트에서도 금지된다.
     */
    public boolean canTransitionTo(SecuredLoanStatus target) {
        switch (this) {
            case REQUESTED:
                return target == APPROVED || target == REJECTED;
            case APPROVED:
                return target == DISBURSED || target == REJECTED;
            case DISBURSED:
                return target == REPAID || target == OVERDUE;
            case OVERDUE:
                return target == REPAID || target == DEFAULTED;
            case DEFAULTED:
                // 담보 실행 결과: 전액 회수되면 완제, 부족분이 남으면 상각으로 종결.
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
