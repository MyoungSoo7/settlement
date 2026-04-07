package github.lms.lemuel.returns.domain;

/**
 * 반품/교환 상태
 */
public enum ReturnStatus {
    REQUESTED,   // 요청됨
    APPROVED,    // 승인됨
    REJECTED,    // 거절됨
    SHIPPED,     // 반송 발송됨
    RECEIVED,    // 반송 수령됨
    COMPLETED,   // 완료
    CANCELED;    // 취소됨

    public static ReturnStatus fromString(String status) {
        try {
            return ReturnStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return REQUESTED;
        }
    }
}
