package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.application.port.out.AppendAccountEntryPort;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class AccountEntryPersistenceAdapter implements AppendAccountEntryPort, LoadAccountEntryPort {

    private final AccountEntryRepository repository;

    public AccountEntryPersistenceAdapter(AccountEntryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(AccountEntry entry) {
        // LOW-1: 레이스-세이프 멱등 삽입. 과거의 check-then-save(existsBy → save)는 동시 중복 수신 시
        // 둘째가 자연키 UNIQUE 위반 예외를 던져 @Transactional 을 rollback-only 로 오염시켰다(노이즈·DLT 낭비).
        // ON CONFLICT DO NOTHING 네이티브 upsert 로 중복을 조용히 no-op 처리해 예외·tx 오염을 없앤다.
        // 도메인 구성적 균형 불변식은 AccountEntry 생성 시점에 이미 강제됐다.
        repository.insertIgnoreConflict(
                entry.getOwnerType().name(),
                entry.getOwnerId(),
                entry.getDebitAccount().name(),
                entry.getCreditAccount().name(),
                entry.getAmount(),
                entry.getRefType(),
                entry.getRefId(),
                entry.getSourceTopic(),
                entry.getOccurredAt());
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
    public BigDecimal sellerPayableBalance(String sellerId) {
        return repository.netBalanceByOwnerAndAccount(OwnerType.SELLER, sellerId, GlAccount.SELLER_PAYABLE);
    }

    @Override
    public List<AccountEntry> findAll() {
        return repository.findAll().stream()
                .map(AccountEntryPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<AccountEntry> findByOccurredAtBetween(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return repository.findByOccurredAtGreaterThanEqualAndOccurredAtLessThan(fromInclusive, toExclusive).stream()
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
