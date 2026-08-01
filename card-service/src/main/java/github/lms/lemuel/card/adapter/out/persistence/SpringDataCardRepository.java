package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface SpringDataCardRepository extends JpaRepository<CardJpaEntity, Long> {

    List<CardJpaEntity> findByCardAccountId(Long cardAccountId);

    /** 활성(CANCELED 아님) 카드 — partial unique {@code uq_card_active_holder} 와 같은 기준. */
    @Query("""
            select c from CardJpaEntity c
             where c.cardAccountId = :cardAccountId
               and c.holderUserId = :holderUserId
               and c.status <> 'CANCELED'
            """)
    Optional<CardJpaEntity> findActiveByHolder(@Param("cardAccountId") Long cardAccountId,
                                               @Param("holderUserId") Long holderUserId);

    /** SUSPENDED 포함(재개되면 그 한도를 다시 쓴다) — CANCELED 만 제외. */
    @Query("""
            select coalesce(sum(c.subLimit), 0)
              from CardJpaEntity c
             where c.cardAccountId = :cardAccountId
               and c.status <> 'CANCELED'
            """)
    BigDecimal sumActiveSubLimits(@Param("cardAccountId") Long cardAccountId);
}
