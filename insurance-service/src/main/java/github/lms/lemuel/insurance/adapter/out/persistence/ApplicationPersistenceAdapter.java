package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.application.port.out.LoadApplicationPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationPort;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class ApplicationPersistenceAdapter implements LoadApplicationPort, SaveApplicationPort {

    private final SpringDataInsuranceApplicationRepository repository;
    private final InsuredPersonPiiRepository insuredPiiRepository;
    private final ContractorPiiRepository contractorPiiRepository;

    public ApplicationPersistenceAdapter(
            SpringDataInsuranceApplicationRepository repository,
            InsuredPersonPiiRepository insuredPiiRepository,
            ContractorPiiRepository contractorPiiRepository) {
        this.repository = repository;
        this.insuredPiiRepository = insuredPiiRepository;
        this.contractorPiiRepository = contractorPiiRepository;
    }

    @Override
    public Optional<InsuranceApplication> findByApplicationId(String applicationId) {
        return repository.findByApplicationId(UUID.fromString(applicationId))
                .map(InsuranceApplicationJpaEntity::toDomain);
    }

    @Override
    public InsuranceApplication saveNew(InsuranceApplication application, ApplicationPii pii) {
        InsuranceApplicationJpaEntity saved =
                repository.saveAndFlush(InsuranceApplicationJpaEntity.fromDomain(application));

        // PII 는 청약 본문과 분리된 전용 테이블에 저장 — 컨버터가 저장 시 AES 암호화한다.
        UUID applicationId = saved.getApplicationId();
        if (pii.insuredRrn() != null && !pii.insuredRrn().isBlank()) {
            insuredPiiRepository.save(new InsuredPersonPiiJpaEntity(applicationId, pii.insuredRrn()));
        }
        if (pii.contractorPhone() != null && !pii.contractorPhone().isBlank()) {
            contractorPiiRepository.save(new ContractorPiiJpaEntity(applicationId, pii.contractorPhone()));
        }
        return saved.toDomain();
    }

    @Override
    public InsuranceApplication update(InsuranceApplication application) {
        InsuranceApplicationJpaEntity entity = repository.findById(application.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "존재하지 않는 청약 행에 전이 결과를 반영할 수 없습니다: id=" + application.getId()));
        entity.applyTransitionResult(application);
        return repository.saveAndFlush(entity).toDomain();
    }
}
