package github.lms.lemuel.insurance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 피보험자 PII 영속 저장소.
 */
public interface InsuredPersonPiiRepository extends JpaRepository<InsuredPersonPiiJpaEntity, Long> {
    Optional<InsuredPersonPiiJpaEntity> findByApplicationId(UUID applicationId);
}
