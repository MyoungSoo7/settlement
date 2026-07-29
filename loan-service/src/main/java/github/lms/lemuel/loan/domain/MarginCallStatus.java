package github.lms.lemuel.loan.domain;

/**
 * 마진콜 생명주기.
 *
 * <pre>
 * OPEN → RESOLVED   (담보 보충·일부 상환으로 유지비율 회복)
 *      ↘ ESCALATED  (미해소 — 강제 처분 단계로 이관)
 * </pre>
 *
 * <p>대출 상태머신({@link SecuredLoanStatus})과 분리된 이유: 마진콜은 <b>대출 1건에 여러 번</b>
 * 발생할 수 있고(시가가 오르내리며 반복), 각 발생이 독립적으로 해소·이관된다. 대출 상태에 녹이면
 * 이력이 덮여 어느 시점에 몇 번 부족했는지 재현할 수 없다.
 */
public enum MarginCallStatus {
    /** 발생 — 추가담보 요구 중. */
    OPEN,
    /** 해소 — 유지비율 회복. 종료 상태. */
    RESOLVED,
    /** 이관 — 미해소로 강제 처분 절차로 넘어감. 종료 상태. */
    ESCALATED;

    /** 허용 상태 전이 단일 출처. {@link MarginCall} 의 전이 가드가 이 표에 위임한다. */
    public boolean canTransitionTo(MarginCallStatus target) {
        switch (this) {
            case OPEN:
                return target == RESOLVED || target == ESCALATED;
            case RESOLVED:
            case ESCALATED:
                return false; // 종료 상태
            default:
                return false;
        }
    }
}
