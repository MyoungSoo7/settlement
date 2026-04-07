package github.lms.lemuel.returns.domain;

/**
 * 반품/교환 유형
 */
public enum ReturnType {
    RETURN,     // 반품
    EXCHANGE;   // 교환

    public static ReturnType fromString(String type) {
        try {
            return ReturnType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid return type: " + type);
        }
    }
}
