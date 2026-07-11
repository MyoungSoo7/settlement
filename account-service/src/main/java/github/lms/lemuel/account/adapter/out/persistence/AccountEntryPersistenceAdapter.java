package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.application.port.out.AppendAccountEntryPort;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.OwnerType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class AccountEntryPersistenceAdapter implements AppendAccountEntryPort, LoadAccountEntryPort {

    private final AccountEntryRepository repository;

    public AccountEntryPersistenceAdapter(AccountEntryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(AccountEntry entry) {
        // 자연키 선점 체크(앱 레벨 멱등). 경합 시 최종 방어는 DB UNIQUE(source_topic, ref_type, ref_id).
        if (repository.existsBySourceTopicAndRefTypeAndRefId(
                entry.getSourceTopic(), entry.getRefType(), entry.getRefId())) {
            return;
        }
        repository.save(new AccountEntryJpaEntity(
                entry.getOwnerType(),
                entry.getOwnerId(),
                entry.getDebitAccount(),
                entry.getCreditAccount(),
                entry.getAmount(),
                entry.getRefType(),
                entry.getRefId(),
                entry.getSourceTopic(),
                entry.getOccurredAt()));
    }

    @Override
    public List<AccountEntry> findByOwner(OwnerType ownerType, String ownerId) {
        return repository.findByOwnerTypeAndOwnerIdOrderByIdDesc(ownerType, ownerId).stream()
                .map(AccountEntryPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<AccountEntry> findByOwnerPaged(OwnerType ownerType, String ownerId, int page, int size) {
        return repository.findByOwnerTypeAndOwnerId(ownerType, ownerId,
                        PageRequest.of(page, size, org.springframework.data.domain.Sort.by("id").descending())).stream()
                .map(AccountEntryPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public long countByOwner(OwnerType ownerType, String ownerId) {
        return repository.countByOwnerTypeAndOwnerId(ownerType, ownerId);
    }

    @Override
    public BigDecimal sumAmountByRefType(String refType) {
        return repository.sumAmountByRefType(refType);
    }

    @Override
    public long countByRefType(String refType) {
        return repository.countByRefType(refType);
    }

    @Override
    public List<AccountEntry> findAll() {
        return repository.findAll().stream()
                .map(AccountEntryPersistenceAdapter::toDomain)
                .toList();
    }

    private static AccountEntry toDomain(AccountEntryJpaEntity e) {
        return AccountEntry.reconstitute(
                e.getId(), e.getOwnerType(), e.getOwnerId(),
                e.getDebitAccount(), e.getCreditAccount(), e.getAmount(),
                e.getRefType(), e.getRefId(), e.getSourceTopic(), e.getOccurredAt());
    }
}
