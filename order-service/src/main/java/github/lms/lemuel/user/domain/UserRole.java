package github.lms.lemuel.user.domain;

/**
 * 사용자 역할 Enum
 */
public enum UserRole {
    USER,
    ADMIN,
    MANAGER;

    public static UserRole fromString(String role) {
        try {
            return UserRole.valueOf(role.toUpperCase());
        } catch (Exception e) {
            return USER; // 기본값
        }
    }
}
