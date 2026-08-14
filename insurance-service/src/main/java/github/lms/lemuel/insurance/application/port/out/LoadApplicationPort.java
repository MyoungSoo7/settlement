package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.InsuranceApplication;

import java.util.Optional;

/**
 * 청약 조회 포트.
 */
public interface LoadApplicationPort {

    Optional<InsuranceApplication> findByApplicationId(String applicationId);
}
