package github.lms.lemuel.user.adapter.out.social;

import github.lms.lemuel.user.application.port.out.SocialAuthPort;
import github.lms.lemuel.user.domain.SocialProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OAuth2 소셜 인증 어댑터
 * 실제 OAuth2 API 호출을 담당합니다.
 * 현재는 클라이언트 ID가 설정되지 않아 스텁 구현입니다.
 */
@Component
@Slf4j
public class OAuth2SocialAuthAdapter implements SocialAuthPort {

    @Override
    public SocialUserInfo authenticate(SocialProvider provider, String code, String redirectUri) {
        return switch (provider) {
            case GOOGLE -> authenticateGoogle(code, redirectUri);
            case KAKAO -> authenticateKakao(code, redirectUri);
            case NAVER -> authenticateNaver(code, redirectUri);
        };
    }

    private SocialUserInfo authenticateGoogle(String code, String redirectUri) {
        // TODO: Implement with actual Google OAuth2 API when client ID is configured
        // Flow:
        // 1. Exchange code for access token: POST https://oauth2.googleapis.com/token
        // 2. Get user info: GET https://www.googleapis.com/oauth2/v2/userinfo
        log.warn("Google OAuth2 not yet configured. Attempted authentication with code: {}", code.substring(0, Math.min(5, code.length())) + "...");
        throw new UnsupportedOperationException("Google OAuth2 not yet configured. Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.");
    }

    private SocialUserInfo authenticateKakao(String code, String redirectUri) {
        // TODO: Implement with actual Kakao OAuth2 API when client ID is configured
        // Flow:
        // 1. Exchange code for access token: POST https://kauth.kakao.com/oauth/token
        // 2. Get user info: GET https://kapi.kakao.com/v2/user/me
        log.warn("Kakao OAuth2 not yet configured. Attempted authentication with code: {}", code.substring(0, Math.min(5, code.length())) + "...");
        throw new UnsupportedOperationException("Kakao OAuth2 not yet configured. Set KAKAO_CLIENT_ID.");
    }

    private SocialUserInfo authenticateNaver(String code, String redirectUri) {
        // TODO: Implement with actual Naver OAuth2 API when client ID is configured
        // Flow:
        // 1. Exchange code for access token: POST https://nid.naver.com/oauth2.0/token
        // 2. Get user info: GET https://openapi.naver.com/v1/nid/me
        log.warn("Naver OAuth2 not yet configured. Attempted authentication with code: {}", code.substring(0, Math.min(5, code.length())) + "...");
        throw new UnsupportedOperationException("Naver OAuth2 not yet configured. Set NAVER_CLIENT_ID and NAVER_CLIENT_SECRET.");
    }
}
