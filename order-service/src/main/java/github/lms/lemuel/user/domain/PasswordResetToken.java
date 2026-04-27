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
        resetToken.setUserId(userId);
        resetToken.setExpiryDate(LocalDateTime.now().plusMinutes(expiryMinutes));
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

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public LocalDateTime getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDateTime expiryDate) {
        this.expiryDate = expiryDate;
    }

    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
