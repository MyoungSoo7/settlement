package github.lms.lemuel.user.domain;

/**
 * 소셜 로그인 제공자 Enum
 */
public enum SocialProvider {
    GOOGLE,
    KAKAO,
    NAVER;

    public static SocialProvider fromString(String provider) {
        try {
            return SocialProvider.valueOf(provider.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported social provider: " + provider);
        }
    }
}
