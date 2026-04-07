package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.domain.SocialAccount;
import github.lms.lemuel.user.domain.SocialProvider;

import java.util.List;
import java.util.Optional;

/**
 * 소셜 계정 조회 Outbound Port
 */
public interface LoadSocialAccountPort {

    Optional<SocialAccount> findByProviderAndProviderId(SocialProvider provider, String providerId);

    List<SocialAccount> findByUserId(Long userId);

    boolean existsByProviderAndProviderId(SocialProvider provider, String providerId);
}
