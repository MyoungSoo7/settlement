package github.lms.lemuel.returns.domain;

/**
 * 반품/교환 사유
 */
public enum ReturnReason {
    DEFECTIVE,       // 불량
    WRONG_ITEM,      // 오배송
    CHANGED_MIND,    // 단순 변심
    SIZE_ISSUE,      // 사이즈 문제
    QUALITY_ISSUE,   // 품질 문제
    LATE_DELIVERY,   // 배송 지연
    OTHER;           // 기타

    public static ReturnReason fromString(String reason) {
        try {
            return ReturnReason.valueOf(reason.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid return reason: " + reason);
        }
    }
}
