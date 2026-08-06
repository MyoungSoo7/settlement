package github.lms.lemuel.insurance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 계약자 PII 영속 저장소.
 */
public interface ContractorPiiRepository extends JpaRepository<ContractorPiiJpaEntity, Long> {
    Optional<ContractorPiiJpaEntity> findByApplicationId(UUID applicationId);
}
