package github.lms.lemuel.user.domain;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * User Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 * DB 스키마: id, email, password, role, name, phone_number, is_active, created_at, updated_at
 */
@Getter
@Setter
public class User {

    private Long id;
    private String email;
    private String passwordHash;
    private UserRole role;
    private String name;
    private String phoneNumber;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public User() {
        this.role = UserRole.USER;
        this.active = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public User(Long id, String email, String passwordHash, UserRole role,
                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, email, passwordHash, role, null, null, true, createdAt, updatedAt);
    }

    public User(Long id, String email, String passwordHash, UserRole role,
                String name, String phoneNumber, Boolean active,
                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role != null ? role : UserRole.USER;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.active = active == null || active;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static User create(String email, String passwordHash) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.validateEmail();
        user.validatePasswordHash();
        return user;
    }

    public static User createWithRole(String email, String passwordHash, UserRole role) {
        User user = create(email, passwordHash);
        user.setRole(role);
        return user;
    }

    public static User createWithProfile(String email, String passwordHash, UserRole role,
                                         String name, String phoneNumber) {
        User user = createWithRole(email, passwordHash, role);
        user.updateProfile(name, phoneNumber);
        return user;
    }

    // 도메인 규칙: 이메일 유효성 검증
    public void validateEmail() {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email cannot be empty");
        }
        if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new IllegalArgumentException("Invalid email format");
        }
    }

    // 도메인 규칙: 비밀번호 해시 검증
    public void validatePasswordHash() {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("Password hash cannot be empty");
        }
    }

    // 비즈니스 메서드
    public void changeRole(UserRole newRole) {
        this.role = newRole;
        this.updatedAt = LocalDateTime.now();
    }

    public void updatePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateProfile(String name, String phoneNumber) {
        if (name != null) {
            String trimmed = name.trim();
            if (trimmed.length() > 100) {
                throw new IllegalArgumentException("Name must not exceed 100 characters");
            }
            this.name = trimmed.isEmpty() ? null : trimmed;
        }
        if (phoneNumber != null) {
            String trimmed = phoneNumber.trim();
            if (!trimmed.isEmpty() && !trimmed.matches("^[0-9+\\-() ]{8,30}$")) {
                throw new IllegalArgumentException("Invalid phone number format");
            }
            this.phoneNumber = trimmed.isEmpty() ? null : trimmed;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void deactivate() {
        this.active = false;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isAdmin() {
        return this.role == UserRole.ADMIN;
    }

}
