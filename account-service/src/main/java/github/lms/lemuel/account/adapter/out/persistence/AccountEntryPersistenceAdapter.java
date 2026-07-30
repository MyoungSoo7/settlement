package github.lms.lemuel.account.adapter.out.persistence;

import github.lms.lemuel.account.application.port.out.AppendAccountEntryPort;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.application.port.out.ReconcileBalancesPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class AccountEntryPersistenceAdapter
        implements AppendAccountEntryPort, LoadAccountEntryPort, ReconcileBalancesPort {

    private final AccountEntryRepository repository;
    private final AccountBalanceRepository balanceRepository;

    public AccountEntryPersistenceAdapter(AccountEntryRepository repository,
                                          AccountBalanceRepository balanceRepository) {
        this.repository = repository;
        this.balanceRepository = balanceRepository;
    }

    /**
     * 전표를 적재하고, <b>실제로 삽입됐을 때만</b> 실체화 잔액을 갱신한다(ADR 0030 Phase 1).
     *
     * <p>원장과 파생 캐시가 같은 트랜잭션에서 함께 움직여야 둘이 어긋나지 않는다. 중복 수신은
     * {@code ON CONFLICT DO NOTHING} 이 0행으로 알려주므로, 그 경우 잔액도 건드리지 않는다 —
     * 이 분기가 없으면 재수신마다 잔액이 부풀어 원장 재합산과 영구히 벌어진다.
     */
    @Override
    @Transactional
    public void append(AccountEntry entry) {
        // LOW-1: 레이스-세이프 멱등 삽입. 과거의 check-then-save(existsBy → save)는 동시 중복 수신 시
        // 둘째가 자연키 UNIQUE 위반 예외를 던져 @Transactional 을 rollback-only 로 오염시켰다(노이즈·DLT 낭비).
        // ON CONFLICT DO NOTHING 네이티브 upsert 로 중복을 조용히 no-op 처리해 예외·tx 오염을 없앤다.
        // 도메인 구성적 균형 불변식은 AccountEntry 생성 시점에 이미 강제됐다.
        int inserted = repository.insertIgnoreConflict(
                entry.getOwnerType().name(),
                entry.getOwnerId(),
                entry.getDebitAccount().name(),
                entry.getCreditAccount().name(),
                entry.getAmount(),
                entry.getRefType(),
                entry.getRefId(),
                entry.getSourceTopic(),
                entry.getOccurredAt());
        if (inserted == 0) {
            return;   // 중복 수신 — 기표가 없었으므로 잔액도 불변
        }
        // credit-positive 규약: 대변 계정은 +amount, 차변 계정은 −amount.
        // 전표가 구성적으로 균형(차1·대1·금액1)이라 두 레그의 델타 합은 항상 0 — 총합 불변식이 보존된다.
        String ownerType = entry.getOwnerType().name();
        String ownerId = entry.getOwnerId();
        balanceRepository.upsertDelta(ownerType, ownerId, entry.getCreditAccount().name(), entry.getAmount());
        balanceRepository.upsertDelta(ownerType, ownerId, entry.getDebitAccount().name(), entry.getAmount().negate());
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

    /**
     * 실체화 잔액을 (owner, account) 유일키로 조회한다 — O(1)(ADR 0030 Phase 1).
     *
     * <p>이전에는 셀러의 누적 전표 전량을 재합산했고(O(셀러 전표 수)), payout 이 advisory 락을 쥔 채
     * 그 집계를 돌아 락 보유시간이 이력 길이에 비례했다. 부호 규약이 이전 재합산식과 동일해
     * (Σcredit − Σdebit) 반환 의미는 바뀌지 않는다.
     */
    // ─── ADR 0030 Phase 3 — 대사(실체화 잔액 vs 원장 재합산) ─────────────────

    @Override
    public BalanceReconSnapshot reconcileBalances(int driftLimit) {
        List<Object[]> rows = balanceRepository.findBalanceReconRows(driftLimit);
        if (rows.isEmpty()) {
            // 드리프트 0 — 요약(쌍 총수)만 보충한다. 별도 문장이지만 드리프트 관련 값이 아니라
            // 스냅샷 자기모순(MED-3)의 대상이 아니다.
            return new BalanceReconSnapshot(balanceRepository.countBalancePairs(), 0L, List.of());
        }
        Object[] first = rows.get(0);
        long driftCount = ((Number) first[5]).longValue();
        long checkedPairs = ((Number) first[6]).longValue();
        List<BalanceDriftRow> drifts = rows.stream()
                .map(row -> new BalanceDriftRow(
                        OwnerType.valueOf((String) row[0]),
                        (String) row[1],
                        GlAccount.valueOf((String) row[2]),
                        (BigDecimal) row[3],
                        (BigDecimal) row[4]))
                .toList();
        return new BalanceReconSnapshot(checkedPairs, driftCount, drifts);
    }

    @Override
    public BigDecimal sellerPayableBalance(String sellerId) {
        return balanceRepository
                .findByOwnerTypeAndOwnerIdAndAccount(OwnerType.SELLER, sellerId, GlAccount.SELLER_PAYABLE)
                .map(AccountBalanceJpaEntity::getBalance)
                .orElse(BigDecimal.ZERO);
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
