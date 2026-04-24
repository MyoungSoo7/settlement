package github.lms.lemuel.ledger.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface SaveAccountBalanceSnapshotPort {
    void saveSnapshot(Long accountId, BigDecimal balance, LocalDate snapshotAt);
}
