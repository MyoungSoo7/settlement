package github.lms.lemuel.settlement.domain;

/**
 * 정산 상태 Enum
 *
 * 상태 전이:
 *   REQUESTED → PROCESSING → DONE
 *                          ↘ FAILED → REQUESTED (재시도)
 *   REQUESTED → CANCELED
 */
public enum SettlementStatus {
    REQUESTED,    // 정산 요청됨 (초기 상태)
    PROCESSING,   // 정산 처리 중
    DONE,         // 정산 완료
    FAILED,       // 정산 실패
    CANCELED;     // 정산 취소

    public static SettlementStatus fromString(String status) {
        try {
            return SettlementStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return REQUESTED; // 기본값
        }
    }

    public boolean canTransitionTo(SettlementStatus targetStatus) {
        return switch (this) {
            case REQUESTED -> targetStatus == PROCESSING || targetStatus == CANCELED;
            case PROCESSING -> targetStatus == DONE || targetStatus == FAILED;
            case FAILED -> targetStatus == REQUESTED; // 재시도 가능
            case DONE, CANCELED -> false; // 종료 상태
        };
    }
}
