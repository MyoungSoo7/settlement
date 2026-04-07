package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.application.port.out.LoadSocialAccountPort;
import github.lms.lemuel.user.application.port.out.SaveSocialAccountPort;
import github.lms.lemuel.user.domain.SocialAccount;
import github.lms.lemuel.user.domain.SocialProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Social Account Persistence Adapter
 */
@Component
@RequiredArgsConstructor
public class SocialAccountPersistenceAdapter implements LoadSocialAccountPort, SaveSocialAccountPort {

    private final SpringDataSocialAccountRepository repository;
    private final SocialAccountPersistenceMapper mapper;

    @Override
    public Optional<SocialAccount> findByProviderAndProviderId(SocialProvider provider, String providerId) {
        return repository.findByProviderAndProviderId(provider.name(), providerId)
                .map(mapper::toDomain);
    }

    @Override
    public List<SocialAccount> findByUserId(Long userId) {
        return repository.findByUserId(userId)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByProviderAndProviderId(SocialProvider provider, String providerId) {
        return repository.existsByProviderAndProviderId(provider.name(), providerId);
    }

    @Override
    public SocialAccount save(SocialAccount socialAccount) {
        SocialAccountJpaEntity entity = mapper.toEntity(socialAccount);
        SocialAccountJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional
    public void deleteByUserIdAndProvider(Long userId, SocialProvider provider) {
        repository.deleteByUserIdAndProvider(userId, provider.name());
    }
}
