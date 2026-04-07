package github.lms.lemuel.point.domain;

public enum PointTransactionType {
    EARN,
    USE,
    CANCEL_EARN,
    CANCEL_USE,
    EXPIRE,
    ADMIN_ADJUST;

    public static PointTransactionType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("포인트 거래 유형은 필수입니다.");
        }
        try {
            return valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 포인트 거래 유형: " + value);
        }
    }
}
