package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.CardAccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataCardAccountRepository extends JpaRepository<CardAccountJpaEntity, Long> {

    /** 조직당 카드계정 1개(uq_card_account_org). */
    Optional<CardAccountJpaEntity> findByOrganizationId(Long organizationId);

    /**
     * 비관적 락(PESSIMISTIC_WRITE) — 발급·한도변경 유스케이스가 이 행을 잠근 채로 서브한도
     * 합계를 재계산해야 동시 요청 경쟁에서 master_limit >= Σ subLimit 불변식이 깨지지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CardAccountJpaEntity a where a.id = :id")
    Optional<CardAccountJpaEntity> findByIdForUpdate(@Param("id") Long id);

    /** 재산정 스케줄러(Task 13) 전용 — status=ACTIVE 만. */
    List<CardAccountJpaEntity> findByStatus(CardAccountStatus status);
}
