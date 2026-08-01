package github.lms.lemuel.card.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataCardAccountRepository extends JpaRepository<CardAccountJpaEntity, Long> {

    Optional<CardAccountJpaEntity> findByOrganizationId(Long organizationId);

    /** 발급·한도변경 경로의 비관적 락 — 계정 행을 잠근 뒤 서브한도 합계를 재계산한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CardAccountJpaEntity a where a.id = :id")
    Optional<CardAccountJpaEntity> findByIdForUpdate(@Param("id") Long id);

    List<CardAccountJpaEntity> findAllByStatus(String status);
}
