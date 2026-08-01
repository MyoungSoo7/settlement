package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class CardAccountPersistenceAdapter implements LoadCardAccountPort, SaveCardAccountPort {

    private final SpringDataCardAccountRepository repository;

    public CardAccountPersistenceAdapter(SpringDataCardAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<CardAccount> findByOrganizationId(Long organizationId) {
        return repository.findByOrganizationId(organizationId).map(CardAccountJpaEntity::toDomain);
    }

    @Override
    public Optional<CardAccount> findById(Long id) {
        return repository.findById(id).map(CardAccountJpaEntity::toDomain);
    }

    @Override
    public Optional<CardAccount> findByIdForUpdate(Long id) {
        return repository.findByIdForUpdate(id).map(CardAccountJpaEntity::toDomain);
    }

    @Override
    public List<CardAccount> findAllActive() {
        return repository.findByStatus(CardAccountStatus.ACTIVE).stream()
                .map(CardAccountJpaEntity::toDomain)
                .toList();
    }

    @Override
    public CardAccount save(CardAccount account) {
        // 도메인 스냅샷 → detached 엔티티 재구성 후 merge. @Version 이 그대로 실려 낙관적 락이 동작한다.
        return repository.saveAndFlush(CardAccountJpaEntity.fromDomain(account)).toDomain();
    }
}
