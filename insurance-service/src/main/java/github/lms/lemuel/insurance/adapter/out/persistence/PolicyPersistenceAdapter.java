package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.SavePolicyPort;
import github.lms.lemuel.insurance.domain.CommissionStatus;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class PolicyPersistenceAdapter implements LoadPolicyPort, SavePolicyPort {

    private final SpringDataPolicyRepository repository;

    public PolicyPersistenceAdapter(SpringDataPolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Policy> findActiveMaturedOnOrBefore(LocalDate date) {
        return repository.findByStatusAndMaturityDateLessThanEqual(PolicyStatus.ACTIVE, date)
                .stream().map(PolicyJpaEntity::toDomain).toList();
    }

    @Override
    public List<Policy> findLapsedOnOrBefore(LocalDate lapsedCutoff) {
        return repository.findByStatusAndLapsedAtLessThanEqual(PolicyStatus.LAPSED, lapsedCutoff)
                .stream().map(PolicyJpaEntity::toDomain).toList();
    }

    @Override
    public List<Policy> findTerminalWithCommissionsInStatus(CommissionStatus commissionStatus) {
        return repository.findTerminalWithCommissionsInStatus(
                        EnumSet.of(PolicyStatus.SURRENDERED, PolicyStatus.EXPIRED, PolicyStatus.CANCELLED),
                        commissionStatus)
                .stream().map(PolicyJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<Policy> findByPolicyId(String policyId) {
        return repository.findByPolicyId(UUID.fromString(policyId)).map(PolicyJpaEntity::toDomain);
    }

    @Override
    public Optional<Policy> findByPolicyNumber(String policyNumber) {
        return repository.findByPolicyNumber(policyNumber).map(PolicyJpaEntity::toDomain);
    }

    @Override
    public Optional<Integer> findPaymentCycleMonths(String policyId) {
        return repository.findPaymentCycleMonths(UUID.fromString(policyId));
    }

    @Override
    public Policy insertIssued(Policy policy, PolicyIssuanceAttributes attributes) {
        return repository.saveAndFlush(PolicyJpaEntity.fromIssued(
                        policy,
                        UUID.fromString(attributes.applicationId()),
                        attributes.productCode(),
                        attributes.coverageAmount(),
                        attributes.paymentCycleMonths()))
                .toDomain();
    }

    @Override
    public Policy save(Policy policy) {
        // 배치 전이는 항상 기존 행 대상 — 신규 발행 경로가 생기면 별도 INSERT 경로를 연다.
        PolicyJpaEntity entity = repository.findById(policy.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "존재하지 않는 계약 행에 전이 결과를 반영할 수 없습니다: id=" + policy.getId()));
        entity.applyTransitionResult(policy);
        return repository.saveAndFlush(entity).toDomain();
    }
}
