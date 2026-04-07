package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.SocialAccount;
import github.lms.lemuel.user.domain.SocialProvider;

import java.util.List;

/**
 * 소셜 로그인 UseCase (Inbound Port)
 */
public interface SocialLoginUseCase {

    LoginResult socialLogin(SocialLoginCommand command);

    SocialAccount linkSocialAccount(Long userId, SocialLoginCommand command);

    void unlinkSocialAccount(Long userId, SocialProvider provider);

    List<SocialAccount> getSocialAccounts(Long userId);

    record SocialLoginCommand(SocialProvider provider, String code, String redirectUri) {
        public SocialLoginCommand {
            if (provider == null) {
                throw new IllegalArgumentException("Provider is required");
            }
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Authorization code is required");
            }
            if (redirectUri == null || redirectUri.isBlank()) {
                throw new IllegalArgumentException("Redirect URI is required");
            }
        }
    }

    record LoginResult(String token, String email, String role, boolean isNewUser) {}
}
