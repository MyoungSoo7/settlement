package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataMerchantPolicyRepository
        extends JpaRepository<MerchantPolicyJpaEntity, Long> {

    /** 카드 단위 정책 조회(우선). */
    Optional<MerchantPolicyJpaEntity> findByCardId(Long cardId);

    /** 계정 단위 정책 조회(card_id IS NULL). */
    Optional<MerchantPolicyJpaEntity> findByCardAccountIdAndCardIdIsNull(Long cardAccountId);
}
