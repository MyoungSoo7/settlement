package github.lms.lemuel.insurance.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 보험 계약(Policy) 생애주기 상태머신 — D7 확정 결정.
 *
 * <p>허용 전이는 아래 7개뿐이다:
 * <ol>
 *   <li>ACTIVE → LAPSED : 보험료 납입 2회 연속 실패</li>
 *   <li>LAPSED → ACTIVE : 부활 (실효일로부터 부활 창구 이내 + 연체 전액 납입)</li>
 *   <li>LAPSED → EXPIRED : 실효일로부터 부활 창구 경과 소멸</li>
 *   <li>ACTIVE → SURRENDERED : 계약자 임의 해지</li>
 *   <li>ACTIVE → EXPIRED : 만기일 도래</li>
 *   <li>ACTIVE → CANCELLED : 청약철회 (효력일로부터 15일 이내)</li>
 *   <li>LAPSED → CANCELLED : 청약철회 (효력일로부터 15일 이내)</li>
 * </ol>
 *
 * <p>Terminal 상태(SURRENDERED·EXPIRED·CANCELLED)에서 나가는 전이는 없다.
 * 위 7개에 없는 모든 전이는 {@link github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException} 을 던진다.
 */
public enum PolicyStatus {

    /** 유효 계약. 납입·보장 정상. */
    ACTIVE,

    /** 실효. 납입 2회 연속 실패로 전이. 부활 가능 기간({@code Policy.REINSTATEMENT_WINDOW_MONTHS}) 동안 ACTIVE 로 복귀 가능. */
    LAPSED,

    /** 해지 (terminal). 계약자 임의 해지. */
    SURRENDERED,

    /** 소멸 (terminal). 만기 도래 또는 실효 후 부활 기간 도과. */
    EXPIRED,

    /** 철회 (terminal). 청약철회 — 효력일로부터 15일 이내에만 가능. */
    CANCELLED;

    private static final Map<PolicyStatus, Set<PolicyStatus>> ALLOWED;

    static {
        ALLOWED = new EnumMap<>(PolicyStatus.class);
        ALLOWED.put(ACTIVE, EnumSet.of(LAPSED, SURRENDERED, EXPIRED, CANCELLED));
        ALLOWED.put(LAPSED, EnumSet.of(ACTIVE, EXPIRED, CANCELLED));
        ALLOWED.put(SURRENDERED, EnumSet.noneOf(PolicyStatus.class));
        ALLOWED.put(EXPIRED, EnumSet.noneOf(PolicyStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(PolicyStatus.class));
    }

    /**
     * 이 상태에서 {@code target} 으로의 전이가 허용되는가.
     *
     * <p>허용 여부만 반환한다 — 비즈니스 전제조건(납입 실패 횟수, 기간 경과 등)은
     * 상위 {@link Policy} 메서드가 별도로 검증한다.
     */
    public boolean canTransitionTo(PolicyStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Terminal 상태 여부. Terminal 에서 나가는 전이는 없다. */
    public boolean isTerminal() {
        return this == SURRENDERED || this == EXPIRED || this == CANCELLED;
    }
}
