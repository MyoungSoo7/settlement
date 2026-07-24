package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerPeriodPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerPeriodPort;
import github.lms.lemuel.ledger.domain.LedgerPeriod;
import github.lms.lemuel.ledger.domain.LedgerPeriodStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class LedgerPeriodPersistenceAdapter implements LoadLedgerPeriodPort, SaveLedgerPeriodPort {

    private final SpringDataLedgerPeriodRepository repository;

    @Override
    public Optional<LedgerPeriod> findByPeriod(YearMonth period) {
        return repository.findByPeriodYm(period.toString()).map(this::toDomain);
    }

    @Override
    public boolean isClosed(YearMonth period) {
        return repository.existsByPeriodYmAndStatus(period.toString(), LedgerPeriodStatus.CLOSED.name());
    }

    @Override
    public LedgerPeriod save(LedgerPeriod domain) {
        LedgerPeriodJpaEntity entity = repository.findByPeriodYm(domain.getPeriodYm())
                .orElseGet(LedgerPeriodJpaEntity::new);
        entity.setPeriodYm(domain.getPeriodYm());
        entity.setStatus(domain.getStatus().name());
        entity.setClosedAt(domain.getClosedAt());
        entity.setClosedBy(domain.getClosedBy());
        entity.setTotalDebit(domain.getTotalDebit());
        entity.setTotalCredit(domain.getTotalCredit());
        entity.setCreatedAt(domain.getCreatedAt() != null ? domain.getCreatedAt() : LocalDateTime.now());
        return toDomain(repository.save(entity));
    }

    private LedgerPeriod toDomain(LedgerPeriodJpaEntity entity) {
        return LedgerPeriod.rehydrate(
                entity.getId(),
                YearMonth.parse(entity.getPeriodYm()),
                LedgerPeriodStatus.valueOf(entity.getStatus()),
                entity.getClosedAt(),
                entity.getClosedBy(),
                entity.getTotalDebit(),
                entity.getTotalCredit(),
                entity.getCreatedAt());
    }
}
