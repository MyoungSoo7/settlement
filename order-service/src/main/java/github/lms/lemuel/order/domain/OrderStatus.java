package github.lms.lemuel.order.domain;

/**
 * 주문 상태 Enum
 */
public enum OrderStatus {
    CREATED,    // 주문 생성됨(결제 전)
    PAID,       // 결제 완료로 주문 확정
    CANCELED,   // 결제 전 취소
    REFUNDED;   // 결제 후 환불 완료

    public static OrderStatus fromString(String status) {
        try {
            return OrderStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return CREATED; // 기본값
        }
    }
}
