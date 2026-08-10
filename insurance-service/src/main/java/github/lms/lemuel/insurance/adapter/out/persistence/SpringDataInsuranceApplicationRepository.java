package github.lms.lemuel.insurance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataInsuranceApplicationRepository
        extends JpaRepository<InsuranceApplicationJpaEntity, Long> {

    Optional<InsuranceApplicationJpaEntity> findByApplicationId(UUID applicationId);
}
