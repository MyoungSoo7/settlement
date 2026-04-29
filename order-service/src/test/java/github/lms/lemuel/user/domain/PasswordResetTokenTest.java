package github.lms.lemuel.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class PasswordResetTokenTest {

    @Test @DisplayName("기본 생성자: UUID 토큰과 기본값 설정")
    void defaultConstructor() {
        var token = new PasswordResetToken();
        assertThat(token.getToken()).isNotNull().isNotBlank();
        assertThat(token.isUsed()).isFalse();
        assertThat(token.getCreatedAt()).isNotNull();
    }

    @Test @DisplayName("create: userId와 만료 시간 설정")
    void create() {
        var token = PasswordResetToken.create(42L, 30);
        assertThat(token.getUserId()).isEqualTo(42L);
        assertThat(token.getToken()).isNotBlank();
        assertThat(token.isUsed()).isFalse();
        assertThat(token.getExpiryDate()).isAfter(LocalDateTime.now().plusMinutes(29));
    }

    @Test @DisplayName("isValid: 미사용 + 미만료이면 true")
    void isValid_true() {
        var token = PasswordResetToken.create(1L, 30);
        assertThat(token.isValid()).isTrue();
    }

    @Test @DisplayName("isValid: 사용됨이면 false")
    void isValid_used() {
        var token = PasswordResetToken.create(1L, 30);
        token.markAsUsed();
        assertThat(token.isValid()).isFalse();
        assertThat(token.isUsed()).isTrue();
    }

    @Test @DisplayName("isExpired: 만료된 토큰")
    void isExpired() {
        var token = new PasswordResetToken(1L, 1L, "tok", LocalDateTime.now().minusMinutes(1), false, LocalDateTime.now());
        assertThat(token.isExpired()).isTrue();
        assertThat(token.isValid()).isFalse();
    }

    @Test @DisplayName("전체 생성자: 모든 필드 설정")
    void fullConstructor() {
        var now = LocalDateTime.of(2025, 1, 1, 0, 0);
        var expiry = LocalDateTime.of(2025, 1, 1, 1, 0);
        var token = new PasswordResetToken(1L, 2L, "abc", expiry, true, now);
        assertThat(token.getId()).isEqualTo(1L);
        assertThat(token.getUserId()).isEqualTo(2L);
        assertThat(token.getToken()).isEqualTo("abc");
        assertThat(token.getExpiryDate()).isEqualTo(expiry);
        assertThat(token.isUsed()).isTrue();
        assertThat(token.getCreatedAt()).isEqualTo(now);
    }

    @Test @DisplayName("setter: id 설정")
    void setter() {
        var token = new PasswordResetToken();
        token.setId(99L);
        assertThat(token.getId()).isEqualTo(99L);
    }
}
