package github.lms.lemuel.recovery.domain;

/**
 * 지급후 회수 채권 상태.
 *
 * <pre>
 * OPEN ──────────────→ CLOSED          (전액 상계 도달)
 *   └──→ MANUAL_REQUIRED ──→ CLOSED    (수기 회수 완료 처리)
 * </pre>
 *
 * CLOSED 는 종결 — 어떤 전이도 불가. MANUAL_REQUIRED 에서 OPEN 복귀는 없다
 * (수기 이관은 단방향 — 자동 상계로 되돌리려면 채권을 새로 발생시킨다).
 */
public enum RecoveryStatus {
    OPEN,
    MANUAL_REQUIRED,
    CLOSED;

    public boolean canTransitionTo(RecoveryStatus target) {
        return switch (this) {
            case OPEN -> target == MANUAL_REQUIRED || target == CLOSED;
            case MANUAL_REQUIRED -> target == CLOSED;
            case CLOSED -> false;
        };
    }
}
