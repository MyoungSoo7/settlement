package github.lms.lemuel.seller.domain;

/**
 * 판매자 상태 Enum
 *
 * 상태 전이:
 * PENDING → APPROVED → SUSPENDED
 * PENDING → REJECTED
 * SUSPENDED → APPROVED (재활성화)
 */
public enum SellerStatus {
    PENDING,     // 승인 대기
    APPROVED,    // 승인됨
    SUSPENDED,   // 정지됨
    REJECTED;    // 거부됨

    public static SellerStatus fromString(String status) {
        try {
            return SellerStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return PENDING;
        }
    }
}
