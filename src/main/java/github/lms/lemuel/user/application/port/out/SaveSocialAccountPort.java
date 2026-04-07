package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.domain.SocialAccount;
import github.lms.lemuel.user.domain.SocialProvider;

/**
 * 소셜 계정 저장 Outbound Port
 */
public interface SaveSocialAccountPort {

    SocialAccount save(SocialAccount socialAccount);

    void deleteByUserIdAndProvider(Long userId, SocialProvider provider);
}
