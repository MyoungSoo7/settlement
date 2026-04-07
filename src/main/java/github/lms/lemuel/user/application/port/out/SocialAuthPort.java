package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.domain.SocialProvider;

/**
 * 소셜 인증 Outbound Port (OAuth2 제공자와 통신)
 */
public interface SocialAuthPort {

    SocialUserInfo authenticate(SocialProvider provider, String code, String redirectUri);

    record SocialUserInfo(String providerId, String email, String name, String profileImage) {}
}
