package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerEntryPort;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.LedgerStatus;
import github.lms.lemuel.ledger.domain.ReferenceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class LedgerPersistenceAdapter implements SaveLedgerEntryPort, LoadLedgerEntryPort {

    private final SpringDataLedgerJpaRepository repository;

    @Override
    public LedgerEntry save(LedgerEntry domain) {
        LedgerEntryJpaEntity entity = toEntity(domain);
        LedgerEntryJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<LedgerEntry> findById(Long id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public boolean existsByReference(Long referenceId, ReferenceType referenceType) {
        return repository.existsByReferenceIdAndReferenceType(referenceId, referenceType.name());
    }

    @Override
    public List<LedgerEntry> findByReference(Long referenceId, ReferenceType referenceType) {
        return repository.findByReferenceIdAndReferenceType(referenceId, referenceType.name())
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<LedgerEntry> findBySettlementDateBetween(LocalDate from, LocalDate to) {
        return repository.findBySettlementDateBetween(from, to)
                .stream().map(this::toDomain).toList();
    }

    private LedgerEntryJpaEntity toEntity(LedgerEntry domain) {
        LedgerEntryJpaEntity entity = new LedgerEntryJpaEntity();
        entity.setId(domain.getId());
        entity.setReferenceId(domain.getReferenceId());
        entity.setReferenceType(domain.getReferenceType().name());
        entity.setEntryType(domain.getEntryType().name());
        entity.setDebitAccount(domain.getDebitAccount().name());
        entity.setCreditAccount(domain.getCreditAccount().name());
        entity.setAmount(domain.getAmount());
        entity.setStatus(domain.getStatus().name());
        entity.setSettlementDate(domain.getSettlementDate());
        entity.setPostedAt(domain.getPostedAt());
        entity.setMemo(domain.getMemo());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(domain.getCreatedAt() != null ? domain.getCreatedAt() : now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private LedgerEntry toDomain(LedgerEntryJpaEntity entity) {
        return LedgerEntry.rehydrate(
                entity.getId(),
                entity.getReferenceId(),
                ReferenceType.valueOf(entity.getReferenceType()),
                LedgerEntryType.valueOf(entity.getEntryType()),
                AccountType.valueOf(entity.getDebitAccount()),
                AccountType.valueOf(entity.getCreditAccount()),
                entity.getAmount(),
                LedgerStatus.valueOf(entity.getStatus()),
                entity.getSettlementDate(),
                entity.getPostedAt(),
                entity.getMemo(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
