package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.LoadCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.SaveCompanyReputationPort;
import github.lms.lemuel.loan.domain.CompanyReputation;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class CompanyReputationPersistenceAdapter implements SaveCompanyReputationPort, LoadCompanyReputationPort {

    private final CompanyReputationRepository repository;

    public CompanyReputationPersistenceAdapter(CompanyReputationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void upsert(CompanyReputation reputation) {
        // stockCode 가 할당 식별자이므로 save() 는 merge(존재 시 UPDATE / 없으면 INSERT) = 멱등 UPSERT.
        repository.save(new CompanyReputationJpaEntity(
                reputation.getStockCode(),
                reputation.getScore(),
                reputation.getGrade(),
                reputation.getPreviousGrade(),
                reputation.getSnapshotDate(),
                LocalDateTime.now()));
    }

    @Override
    public Optional<CompanyReputation> findByStockCode(String stockCode) {
        return repository.findById(stockCode).map(CompanyReputationJpaEntity::toDomain);
    }
}
