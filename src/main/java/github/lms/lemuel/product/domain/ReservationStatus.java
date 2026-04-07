package github.lms.lemuel.product.domain;

/**
 * 재고 예약 상태 Enum
 */
public enum ReservationStatus {
    RESERVED,   // 예약됨
    CONFIRMED,  // 확정됨
    RELEASED,   // 해제됨
    EXPIRED;    // 만료됨

    public static ReservationStatus fromString(String status) {
        try {
            return ReservationStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return RESERVED; // 기본값
        }
    }
}
