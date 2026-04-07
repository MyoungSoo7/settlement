package github.lms.lemuel.user.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Social Account Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 */
@Getter
@Setter
public class SocialAccount {

    private Long id;
    private Long userId;
    private SocialProvider provider;
    private String providerId;
    private String email;
    private String name;
    private String profileImage;
    private String accessToken;
    private String refreshToken;
    private LocalDateTime tokenExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public SocialAccount() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public SocialAccount(Long id, Long userId, SocialProvider provider, String providerId,
                         String email, String name, String profileImage,
                         String accessToken, String refreshToken, LocalDateTime tokenExpiresAt,
                         LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.name = name;
        this.profileImage = profileImage;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = tokenExpiresAt;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static SocialAccount create(Long userId, SocialProvider provider, String providerId,
                                       String email, String name, String profileImage) {
        SocialAccount account = new SocialAccount();
        account.setUserId(userId);
        account.setProvider(provider);
        account.setProviderId(providerId);
        account.setEmail(email);
        account.setName(name);
        account.setProfileImage(profileImage);
        return account;
    }

    // 비즈니스 메서드: 토큰 갱신
    public void updateTokens(String accessToken, String refreshToken, LocalDateTime expiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = expiresAt;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 프로필 갱신
    public void updateProfile(String name, String profileImage) {
        this.name = name;
        this.profileImage = profileImage;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 토큰 만료 확인
    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(tokenExpiresAt);
    }
}
