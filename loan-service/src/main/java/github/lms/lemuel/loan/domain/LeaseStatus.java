package github.lms.lemuel.loan.domain;

/**
 * 리스·할부 계약 생명주기.
 *
 * <pre>
 * APPLIED → APPROVED → ACTIVE → MATURED
 *         ↘ REJECTED  ↘ CANCELLED
 *                       ACTIVE ⇄ OVERDUE → DEFAULTED
 *                       ACTIVE·OVERDUE·DEFAULTED → EARLY_TERMINATED
 * </pre>
 *
 * <p><b>실행 시점이 대출과 다르다</b> — 대출은 돈이 나가면 실행이지만 리스는 <b>물건이 인도되어야</b>
 * 계약이 개시된다(ACTIVE). 승인 후 인도 전에 무산되는 일이 실제로 있어 {@link #CANCELLED} 를 둔다.
 *
 * <p><b>연체는 되돌아올 수 있다</b>({@code OVERDUE → ACTIVE}). 회차 상품이라 한 회차 미납 후 납입으로
 * 정상화되는 것이 예외가 아니라 정상 흐름이다. 이 점이 일회성 상환 대출과 갈린다.
 *
 * <p><b>중도해지는 종료 상태</b>다. 물건 회수·규정손해금 정산까지 마친 결과이므로 되돌리지 않는다
 * (되돌려야 하면 신규 계약이다).
 */
public enum LeaseStatus {

    /** 신청 접수. */
    APPLIED,
    /** 심사 승인 — 아직 물건이 인도되지 않았다. */
    APPROVED,
    /** 계약 개시 — 물건 인도 완료, 리스료 청구가 시작된다. */
    ACTIVE,
    /** 회차 미납 — 아직 기한의 이익은 유지된다. */
    OVERDUE,
    /** 기한이익상실 — 잔여 리스료 전액을 즉시 청구하고 물건 회수 절차로 넘어간다. */
    DEFAULTED,
    /** 만기 종료 — 인수(금융리스·할부) 또는 반환(운용리스)으로 정상 종결. */
    MATURED,
    /** 중도해지 — 규정손해금 정산으로 종결. */
    EARLY_TERMINATED,
    /** 심사 거절. */
    REJECTED,
    /** 승인 후 인도 전 취소. */
    CANCELLED;

    /**
     * 허용 상태 전이 단일 출처. 애그리거트 {@link LeaseContract} 의 전이 가드가 이 표에 위임하므로,
     * 표에 없는 전이는 애그리거트에서도 금지된다.
     */
    public boolean canTransitionTo(LeaseStatus target) {
        return switch (this) {
            case APPLIED -> target == APPROVED || target == REJECTED;
            case APPROVED -> target == ACTIVE || target == CANCELLED;
            // 기한이익상실은 연체를 거쳐야만 도달한다 — 잔여 전액 청구는 연체 사실이 선행 기록되어야 한다.
            case ACTIVE -> target == MATURED || target == OVERDUE || target == EARLY_TERMINATED;
            case OVERDUE -> target == ACTIVE || target == DEFAULTED || target == EARLY_TERMINATED
                    || target == MATURED;
            case DEFAULTED -> target == EARLY_TERMINATED;
            case MATURED, EARLY_TERMINATED, REJECTED, CANCELLED -> false;   // 종료 상태
        };
    }

    /** 리스료 청구가 살아 있는 상태인가. */
    public boolean isBillable() {
        return this == ACTIVE || this == OVERDUE;
    }

    /** 더 이상 전이가 없는 종료 상태인가. */
    public boolean isTerminal() {
        return this == MATURED || this == EARLY_TERMINATED || this == REJECTED || this == CANCELLED;
    }
}
