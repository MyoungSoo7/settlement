package github.lms.lemuel.insurance.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 청약(InsuranceApplication) 언더라이팅 상태머신.
 *
 * <p>허용 전이는 아래 3개뿐이다:
 * <ol>
 *   <li>SUBMITTED → UNDER_REVIEW : 심사 착수</li>
 *   <li>UNDER_REVIEW → APPROVED : 승인 — 계약 발행 + 수수료 스케줄 확정으로 이어진다</li>
 *   <li>UNDER_REVIEW → REJECTED : 반려 (사유 필수)</li>
 * </ol>
 *
 * <p>Terminal 상태(APPROVED·REJECTED)에서 나가는 전이는 없다.
 * V1 마이그레이션의 {@code chk_application_status} CHECK 제약과 값이 1:1 로 일치한다.
 */
public enum ApplicationStatus {

    /** 접수됨. 심사 대기. */
    SUBMITTED,

    /** 심사 중. 승인 또는 반려로만 나간다. */
    UNDER_REVIEW,

    /** 승인 (terminal). 계약(Policy) 발행 완료. */
    APPROVED,

    /** 반려 (terminal). reject_reason 이 함께 기록된다. */
    REJECTED;

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED;

    static {
        ALLOWED = new EnumMap<>(ApplicationStatus.class);
        ALLOWED.put(SUBMITTED, EnumSet.of(UNDER_REVIEW));
        ALLOWED.put(UNDER_REVIEW, EnumSet.of(APPROVED, REJECTED));
        ALLOWED.put(APPROVED, EnumSet.noneOf(ApplicationStatus.class));
        ALLOWED.put(REJECTED, EnumSet.noneOf(ApplicationStatus.class));
    }

    /** 이 상태에서 {@code target} 으로의 전이가 허용되는가. */
    public boolean canTransitionTo(ApplicationStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Terminal 상태 여부. Terminal 에서 나가는 전이는 없다. */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED;
    }
}
