package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.LoadLeaseContractPort;
import github.lms.lemuel.loan.application.port.out.SaveLeaseContractPort;
import github.lms.lemuel.loan.domain.LeaseContract;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 리스·할부 계약 영속 어댑터.
 */
@Component
public class LeaseContractPersistenceAdapter implements LoadLeaseContractPort, SaveLeaseContractPort {

    private final LeaseContractRepository repository;
    private final Clock clock;

    public LeaseContractPersistenceAdapter(LeaseContractRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public LeaseContract save(LeaseContract contract) {
        return repository.save(LeaseContractJpaEntity.from(contract, OffsetDateTime.now(clock))).toDomain();
    }

    @Override
    public Optional<LeaseContract> findById(Long contractId) {
        return repository.findById(contractId).map(LeaseContractJpaEntity::toDomain);
    }

    @Override
    public Optional<LeaseContract> findByIdForUpdate(Long contractId) {
        return repository.findWithLockById(contractId).map(LeaseContractJpaEntity::toDomain);
    }

    @Override
    public List<LeaseContract> findByBorrower(Long borrowerUserId, int limit) {
        return repository.findByBorrowerUserIdOrderByIdDesc(borrowerUserId, Limit.of(limit)).stream()
                .map(LeaseContractJpaEntity::toDomain)
                .toList();
    }
}
