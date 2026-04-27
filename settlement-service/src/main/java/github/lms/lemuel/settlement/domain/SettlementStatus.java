package github.lms.lemuel.settlement.domain;

/**
 * 정산 상태 Enum
 *
 * 상태 전이:
 * REQUESTED → PROCESSING → DONE
 *                        ↘ FAILED → REQUESTED (재시도)
 *
 * REQUESTED → CANCELED (환불로 net=0 이 됐을 때)
 */
public enum SettlementStatus {
    REQUESTED,          // 정산 요청됨 (초기 상태)
    PROCESSING,         // 정산 처리 중
    DONE,               // 정산 완료
    FAILED,             // 정산 실패
    CANCELED;           // 정산 취소 (환불 등으로 소멸)

    /**
     * 문자열로부터 상태를 복원. 알 수 없는 값은 REQUESTED 로 fallback.
     * V26 에서 레거시 값(PENDING, CONFIRMED 등)은 DB에서 제거됐지만 롤백 대비 방어 코드.
     */
    public static SettlementStatus fromString(String status) {
        if (status == null) return REQUESTED;
        try {
            return SettlementStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return REQUESTED;
        }
    }

    public boolean canTransitionTo(SettlementStatus targetStatus) {
        switch (this) {
            case REQUESTED:
                return targetStatus == PROCESSING || targetStatus == CANCELED;
            case PROCESSING:
                return targetStatus == DONE || targetStatus == FAILED;
            case FAILED:
                return targetStatus == REQUESTED; // 재시도 가능
            case DONE:
            case CANCELED:
                return false; // 종료 상태
            default:
                return false;
        }
    }
}
