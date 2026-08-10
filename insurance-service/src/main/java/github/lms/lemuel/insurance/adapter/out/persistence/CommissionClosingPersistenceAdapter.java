package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.application.port.out.CommissionClosingPort;
import github.lms.lemuel.insurance.domain.CommissionClosing;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

@Component
public class CommissionClosingPersistenceAdapter implements CommissionClosingPort {

    private final SpringDataCommissionClosingRepository repository;

    public CommissionClosingPersistenceAdapter(SpringDataCommissionClosingRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsByFcAndMonth(String fcId, YearMonth month) {
        return repository.existsByFcIdAndClosingMonth(fcId, month.atDay(1));
    }

    @Override
    public List<CommissionClosing> findByMonth(YearMonth month) {
        return repository.findByClosingMonth(month.atDay(1))
                .stream().map(CommissionClosingJpaEntity::toDomain).toList();
    }

    @Override
    public CommissionClosing save(CommissionClosing closing) {
        // append-only — 항상 INSERT. UPDATE 는 V6 트리거가 DB 레벨에서 거부한다.
        return repository.saveAndFlush(CommissionClosingJpaEntity.fromDomain(closing)).toDomain();
    }
}
