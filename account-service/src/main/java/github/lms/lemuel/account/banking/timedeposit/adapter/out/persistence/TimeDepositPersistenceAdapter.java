package github.lms.lemuel.account.banking.timedeposit.adapter.out.persistence;

import github.lms.lemuel.account.banking.timedeposit.application.port.out.LoadTimeDepositPort;
import github.lms.lemuel.account.banking.timedeposit.application.port.out.SaveTimeDepositPort;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 정기예금 계좌 영속 어댑터.
 *
 * <p>도메인이 불변(모든 필드 final)이라 해지는 "id 가 같은 새 인스턴스"로 돌아온다.
 * {@code save} 에 id 가 실려 있으면 JPA merge 로 같은 행이 갱신되므로, 해지 시 행이 하나 더 생기지 않는다.
 */
@Component
public class TimeDepositPersistenceAdapter implements SaveTimeDepositPort, LoadTimeDepositPort {

    private final TimeDepositRepository repository;

    public TimeDepositPersistenceAdapter(TimeDepositRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public TimeDeposit save(TimeDeposit deposit) {
        return toDomain(repository.save(new TimeDepositJpaEntity(
                deposit.getId(),
                deposit.getDepositorId(),
                deposit.getProductName(),
                deposit.getPrincipal(),
                deposit.getAnnualRate(),
                deposit.getEarlyTerminationRate(),
                deposit.getCompounding(),
                deposit.getTermMonths(),
                deposit.getOpenedOn(),
                deposit.getMaturityDate(),
                deposit.getStatus(),
                deposit.getClosedOn(),
                deposit.getSettledInterest(),
                deposit.getPayoutAmount())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TimeDeposit> findById(Long depositId) {
        return repository.findById(depositId).map(TimeDepositPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeDeposit> findByDepositorId(String depositorId) {
        return repository.findByDepositorIdOrderByIdDesc(depositorId).stream()
                .map(TimeDepositPersistenceAdapter::toDomain)
                .toList();
    }

    private static TimeDeposit toDomain(TimeDepositJpaEntity e) {
        return TimeDeposit.reconstitute(
                e.getId(), e.getDepositorId(), e.getProductName(), e.getPrincipal(),
                e.getAnnualRate(), e.getEarlyTerminationRate(), e.getCompounding(), e.getTermMonths(),
                e.getOpenedOn(), e.getMaturityDate(), e.getStatus(), e.getClosedOn(),
                e.getSettledInterest(), e.getPayoutAmount());
    }
}
