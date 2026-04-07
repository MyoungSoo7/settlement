package github.lms.lemuel.user.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataSocialAccountRepository extends JpaRepository<SocialAccountJpaEntity, Long> {

    Optional<SocialAccountJpaEntity> findByProviderAndProviderId(String provider, String providerId);

    List<SocialAccountJpaEntity> findByUserId(Long userId);

    boolean existsByProviderAndProviderId(String provider, String providerId);

    void deleteByUserIdAndProvider(Long userId, String provider);
}
