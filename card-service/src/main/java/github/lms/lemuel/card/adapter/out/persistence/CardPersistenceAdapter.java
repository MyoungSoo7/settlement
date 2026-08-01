package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.domain.Card;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 임직원 카드 영속 어댑터.
 */
@Component
public class CardPersistenceAdapter implements LoadCardPort, SaveCardPort {

    private final SpringDataCardRepository repository;

    public CardPersistenceAdapter(SpringDataCardRepository repository) {
        this.repository = repository;
    }

    @Override
    public Card save(Card card) {
        return repository.save(CardJpaEntity.from(card)).toDomain();
    }

    @Override
    public Optional<Card> findById(Long id) {
        return repository.findById(id).map(CardJpaEntity::toDomain);
    }

    @Override
    public List<Card> findByCardAccountId(Long cardAccountId) {
        return repository.findByCardAccountId(cardAccountId).stream()
                .map(CardJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<Card> findActiveByHolder(Long cardAccountId, Long holderUserId) {
        return repository.findActiveByHolder(cardAccountId, holderUserId).map(CardJpaEntity::toDomain);
    }

    @Override
    public BigDecimal sumActiveSubLimits(Long cardAccountId) {
        return repository.sumActiveSubLimits(cardAccountId);
    }
}
