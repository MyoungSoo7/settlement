package github.lms.lemuel.settlement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SpringDataCommissionRatePolicyRepository
        extends JpaRepository<CommissionRatePolicyJpaEntity, Long> {

    /**
     * 주어진 시점에 유효한 후보 — 우선순위 판정은 도메인이 하므로 여기서는 좁히기만 한다.
     * 유효기간은 [from, to) 반열림이고, closed_at 이 찍힌 행은 조기 종료돼 후보가 아니다.
     */
    @Query("""
            SELECT p FROM CommissionRatePolicyJpaEntity p
            WHERE p.closedAt IS NULL
              AND p.effectiveFrom <= :at
              AND (p.effectiveTo IS NULL OR p.effectiveTo > :at)
              AND ((p.scope = 'SELLER' AND p.scopeKey = :sellerKey)
                OR (p.scope = 'TIER'   AND p.scopeKey = :tierKey))
            """)
    List<CommissionRatePolicyJpaEntity> findCandidates(@Param("at") LocalDate at,
                                                       @Param("sellerKey") String sellerKey,
                                                       @Param("tierKey") String tierKey);

    /** 같은 scope 안에서 기간이 겹치는 살아있는 정책 — 등록 전 사전 확인용(최종 차단은 DB EXCLUDE). */
    @Query("""
            SELECT p FROM CommissionRatePolicyJpaEntity p
            WHERE p.closedAt IS NULL
              AND p.scope = :scope AND p.scopeKey = :scopeKey
              AND (:to IS NULL OR p.effectiveFrom < :to)
              AND (p.effectiveTo IS NULL OR p.effectiveTo > :from)
            """)
    List<CommissionRatePolicyJpaEntity> findOverlapping(@Param("scope") String scope,
                                                         @Param("scopeKey") String scopeKey,
                                                         @Param("from") LocalDate from,
                                                         @Param("to") LocalDate to);
}
