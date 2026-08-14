package github.lms.lemuel.insurance.domain;

/**
 * 일반지급(GeneralPayout) 상태머신 (D-G4).
 *
 * <p>허용 전이는 아래 1개뿐이다:
 * <ol>
 *   <li>REQUESTED → PAID : 일반지급 실행 배치가 지급</li>
 * </ol>
 *
 * <p>거절·취소 상태가 없는 이유 — payout 은 이미 확정된 terminal 전이(해지·만기·철회)에서만
 * 태어나므로 지급 의무가 소급 취소될 경로가 없다. 위 1개에 없는 모든 전이는
 * {@link github.lms.lemuel.insurance.domain.exception.InvalidGeneralPayoutTransitionException}
 * 을 던진다.
 *
 * <p>V10 마이그레이션의 {@code chk_general_payout_status} CHECK 제약과 값이 1:1 로 일치한다.
 */
public enum GeneralPayoutStatus {

    /** 지급 요청됨. 일반지급 배치가 PAID 로 전이시킨다. */
    REQUESTED,

    /** 지급 완료 (terminal). paid_on 이 함께 기록된다. */
    PAID;

    /**
     * 이 상태에서 {@code target} 으로의 전이가 허용되는가.
     *
     * <p>허용 여부만 반환한다 — 비즈니스 전제조건(지급일 존재 등)은
     * 상위 {@link GeneralPayout} 메서드가 별도로 검증한다.
     */
    public boolean canTransitionTo(GeneralPayoutStatus target) {
        return this == REQUESTED && target == PAID;
    }

    /** Terminal 상태 여부. Terminal 에서 나가는 전이는 없다. */
    public boolean isTerminal() {
        return this == PAID;
    }
}
