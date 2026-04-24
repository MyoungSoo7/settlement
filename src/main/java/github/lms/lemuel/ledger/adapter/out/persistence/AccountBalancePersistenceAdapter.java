package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadAccountBalancePort;
import github.lms.lemuel.ledger.application.port.out.SaveAccountBalanceSnapshotPort;
import github.lms.lemuel.ledger.domain.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

@Repository
@RequiredArgsConstructor
public class AccountBalancePersistenceAdapter implements LoadAccountBalancePort, SaveAccountBalanceSnapshotPort {

    private final SpringDataLedgerLineJpaRepository ledgerLineRepository;
    private final SpringDataAccountBalanceSnapshotJpaRepository snapshotRepository;

    @Override
    public Money getBalance(Long accountId) {
        var latestSnapshot = snapshotRepository.findTopByAccountIdOrderBySnapshotAtDesc(accountId);

        if (latestSnapshot.isPresent()) {
            var snapshot = latestSnapshot.get();
            BigDecimal delta = ledgerLineRepository.calculateBalanceDeltaSince(
                    accountId, snapshot.getSnapshotAt().atStartOfDay());
            return Money.krw(snapshot.getBalance().add(delta));
        }

        BigDecimal fullBalance = ledgerLineRepository.calculateFullBalance(accountId);
        return Money.krw(fullBalance);
    }

    @Override
    public void saveSnapshot(Long accountId, BigDecimal balance, LocalDate snapshotAt) {
        AccountBalanceSnapshotJpaEntity entity =
                new AccountBalanceSnapshotJpaEntity(accountId, balance, snapshotAt);
        snapshotRepository.save(entity);
    }
}
