package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadMerchantPolicyPort;
import github.lms.lemuel.card.domain.MerchantPolicy;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 가맹점 정책 조회 어댑터.
 *
 * <p>카드 단위 정책이 있으면 계정 단위 정책보다 우선 반환한다 — 더 구체적인 정책이 우선이다.
 * 둘 다 없으면 empty → 서비스 계층이 "제한 없음"으로 처리한다.
 */
@Component
public class MerchantPolicyPersistenceAdapter implements LoadMerchantPolicyPort {

    private final SpringDataMerchantPolicyRepository repository;

    public MerchantPolicyPersistenceAdapter(SpringDataMerchantPolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MerchantPolicy> findEffectivePolicy(Long cardId, Long cardAccountId) {
        // 카드 단위 정책 우선
        Optional<MerchantPolicyJpaEntity> cardPolicy = repository.findByCardId(cardId);
        if (cardPolicy.isPresent()) {
            return cardPolicy.map(MerchantPolicyJpaEntity::toDomain);
        }
        // 계정 단위 정책 폴백
        return repository.findByCardAccountIdAndCardIdIsNull(cardAccountId)
                .map(MerchantPolicyJpaEntity::toDomain);
    }
}
