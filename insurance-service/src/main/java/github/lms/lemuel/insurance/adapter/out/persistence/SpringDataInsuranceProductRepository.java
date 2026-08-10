package github.lms.lemuel.insurance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataInsuranceProductRepository
        extends JpaRepository<InsuranceProductJpaEntity, Long> {

    Optional<InsuranceProductJpaEntity> findByProductCode(String productCode);
}
