package github.lms.lemuel.insurance.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 수수료 회차(CommissionSchedule) 상태머신.
 *
 * <p>허용 전이는 아래 4개뿐이다:
 * <ol>
 *   <li>SCHEDULED → PAID : 지급 배치가 due_date 도래 회차를 지급</li>
 *   <li>SCHEDULED → CANCELLED : 계약 종료(해지·철회·소멸)로 미지급 회차 소멸</li>
 *   <li>PAID → CLAWBACK_PENDING : 환수 트리거 (D6 — SURRENDERED·CANCELLED·LAPSED→EXPIRED)</li>
 *   <li>CLAWBACK_PENDING → CLAWED_BACK : 환수금 수납 확인</li>
 * </ol>
 *
 * <p>Terminal 상태(CLAWED_BACK·CANCELLED)에서 나가는 전이는 없다.
 * 위 4개에 없는 모든 전이는
 * {@link github.lms.lemuel.insurance.domain.exception.InvalidCommissionTransitionException} 을 던진다.
 *
 * <p>V1 마이그레이션의 {@code chk_commission_status} CHECK 제약과 값이 1:1 로 일치한다.
 */
public enum CommissionStatus {

    /** 지급 예정. 지급 배치가 due_date 도래분을 PAID 로 전이시킨다. */
    SCHEDULED,

    /** 지급 완료. paid_at·paid_amount 가 함께 기록된다. */
    PAID,

    /** 환수 대기. 계약 종료로 환수가 트리거된 기지급 회차. */
    CLAWBACK_PENDING,

    /** 환수 완료 (terminal). 환수금 수납 확인됨. */
    CLAWED_BACK,

    /** 취소 (terminal). 계약 종료로 미지급 회차가 소멸됨. */
    CANCELLED;

    private static final Map<CommissionStatus, Set<CommissionStatus>> ALLOWED;

    static {
        ALLOWED = new EnumMap<>(CommissionStatus.class);
        ALLOWED.put(SCHEDULED, EnumSet.of(PAID, CANCELLED));
        ALLOWED.put(PAID, EnumSet.of(CLAWBACK_PENDING));
        ALLOWED.put(CLAWBACK_PENDING, EnumSet.of(CLAWED_BACK));
        ALLOWED.put(CLAWED_BACK, EnumSet.noneOf(CommissionStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(CommissionStatus.class));
    }

    /**
     * 이 상태에서 {@code target} 으로의 전이가 허용되는가.
     *
     * <p>허용 여부만 반환한다 — 비즈니스 전제조건(지급일 존재 등)은
     * 상위 {@link CommissionSchedule} 메서드가 별도로 검증한다.
     */
    public boolean canTransitionTo(CommissionStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Terminal 상태 여부. Terminal 에서 나가는 전이는 없다. */
    public boolean isTerminal() {
        return this == CLAWED_BACK || this == CANCELLED;
    }
}
