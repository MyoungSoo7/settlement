package github.lms.lemuel.user.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 비밀번호 재설정 토큰 도메인
 */
public class PasswordResetToken {

    private Long id;
    private Long userId;
    private String token;
    private LocalDateTime expiryDate;
    private boolean used;
    private LocalDateTime createdAt;

    public PasswordResetToken() {
        this.token = UUID.randomUUID().toString();
        this.used = false;
        this.createdAt = LocalDateTime.now();
    }

    public PasswordResetToken(Long id, Long userId, String token, LocalDateTime expiryDate,
                              boolean used, LocalDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.token = token;
        this.expiryDate = expiryDate;
        this.used = used;
        this.createdAt = createdAt;
    }

    public static PasswordResetToken create(Long userId, int expiryMinutes) {
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.userId = userId;
        resetToken.expiryDate = LocalDateTime.now().plusMinutes(expiryMinutes);
        return resetToken;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiryDate);
    }

    public boolean isValid() {
        return !used && !isExpired();
    }

    public void markAsUsed() {
        this.used = true;
    }

    /** DB 부여 PK 주입(setter 대체). 전체 필드 복원은 전체 생성자 사용. */
    public void assignId(Long id) {
        this.id = id;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public LocalDateTime getExpiryDate() {
        return expiryDate;
    }

    public boolean isUsed() {
        return used;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
