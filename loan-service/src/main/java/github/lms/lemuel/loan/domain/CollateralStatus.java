package github.lms.lemuel.loan.domain;

/**
 * 담보 생명주기.
 *
 * <pre>
 * PLEDGED(설정) → ACTIVE(유효) → RELEASED(말소)
 *               ↘ RELEASED (심사 거절 시 설정 해제)
 * </pre>
 *
 * <p>대출 상태머신({@link SecuredLoanStatus})과 <b>독립적으로</b> 관리된다 — 담보는 대출보다 먼저
 * 설정되고 완제 후에 말소되므로 두 생명주기가 1:1로 겹치지 않는다.
 */
public enum CollateralStatus {
    /** 담보 설정 진행 중 — 아직 담보력이 확정되지 않았다. */
    PLEDGED,
    /** 담보 설정 완료 — 대출 실행의 전제. */
    ACTIVE,
    /** 말소 — 종료 상태. 완제 후 해지 또는 심사 거절로 설정 해제. */
    RELEASED;

    /**
     * 허용 상태 전이 단일 출처({@code CorporateLoanStatus#canTransitionTo} 동형).
     * {@link Collateral} 의 전이 가드가 이 표에 위임하므로, 표에 없는 전이는 애그리거트에서도 금지된다.
     */
    public boolean canTransitionTo(CollateralStatus target) {
        switch (this) {
            case PLEDGED:
                return target == ACTIVE || target == RELEASED;
            case ACTIVE:
                return target == RELEASED;
            case RELEASED:
                return false; // 종료 상태
            default:
                return false;
        }
    }
}
