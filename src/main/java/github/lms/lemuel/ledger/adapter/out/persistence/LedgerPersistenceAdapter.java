package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadJournalEntryPort;
import github.lms.lemuel.ledger.application.port.out.SaveJournalEntryPort;
import github.lms.lemuel.ledger.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class LedgerPersistenceAdapter implements SaveJournalEntryPort, LoadJournalEntryPort {

    private final SpringDataJournalEntryJpaRepository journalEntryRepository;
    private final SpringDataAccountJpaRepository accountRepository;
    private final LedgerPersistenceMapper mapper;

    @Override
    public JournalEntry save(JournalEntry journalEntry) {
        JournalEntryJpaEntity entity = mapper.toJpaEntity(journalEntry);
        JournalEntryJpaEntity saved = journalEntryRepository.save(entity);

        List<LedgerLine> lines = saved.getLines().stream()
                .map(lineEntity -> {
                    AccountJpaEntity accountEntity = accountRepository.findById(lineEntity.getAccountId())
                            .orElseThrow();
                    Account account = mapper.toDomain(accountEntity);
                    return new LedgerLine(
                            lineEntity.getId(),
                            account,
                            DebitCredit.valueOf(lineEntity.getSide()),
                            Money.krw(lineEntity.getAmount()),
                            lineEntity.getCreatedAt()
                    );
                })
                .toList();

        return new JournalEntry(
                saved.getId(),
                saved.getEntryType(),
                saved.getReferenceType(),
                saved.getReferenceId(),
                lines,
                saved.getDescription(),
                saved.getIdempotencyKey(),
                saved.getCreatedAt()
        );
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return journalEntryRepository.existsByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<JournalEntry> findByReference(String referenceType, Long referenceId) {
        return journalEntryRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId)
                .stream()
                .map(entity -> {
                    List<LedgerLine> lines = entity.getLines().stream()
                            .map(lineEntity -> {
                                AccountJpaEntity accountEntity = accountRepository.findById(lineEntity.getAccountId())
                                        .orElseThrow();
                                return new LedgerLine(
                                        lineEntity.getId(),
                                        mapper.toDomain(accountEntity),
                                        DebitCredit.valueOf(lineEntity.getSide()),
                                        Money.krw(lineEntity.getAmount()),
                                        lineEntity.getCreatedAt()
                                );
                            })
                            .toList();
                    return new JournalEntry(
                            entity.getId(), entity.getEntryType(), entity.getReferenceType(),
                            entity.getReferenceId(), lines, entity.getDescription(),
                            entity.getIdempotencyKey(), entity.getCreatedAt()
                    );
                })
                .toList();
    }
}
