package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 카드계정 영속 어댑터 — 도메인 스냅샷 ↔ 엔티티 재구성(merge) 왕복.
 */
@Component
public class CardAccountPersistenceAdapter implements LoadCardAccountPort, SaveCardAccountPort {

    private final SpringDataCardAccountRepository repository;

    public CardAccountPersistenceAdapter(SpringDataCardAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public CardAccount save(CardAccount account) {
        return repository.save(CardAccountJpaEntity.from(account)).toDomain();
    }

    @Override
    public Optional<CardAccount> findById(Long id) {
        return repository.findById(id).map(CardAccountJpaEntity::toDomain);
    }

    @Override
    public Optional<CardAccount> findByOrganizationId(Long organizationId) {
        return repository.findByOrganizationId(organizationId).map(CardAccountJpaEntity::toDomain);
    }

    @Override
    public Optional<CardAccount> findByIdForUpdate(Long id) {
        return repository.findByIdForUpdate(id).map(CardAccountJpaEntity::toDomain);
    }

    @Override
    public List<CardAccount> findAllActive() {
        return repository.findAllByStatus(CardAccountStatus.ACTIVE.name()).stream()
                .map(CardAccountJpaEntity::toDomain)
                .toList();
    }
}
