package github.lms.lemuel.ledger.application.port.in;

import java.util.List;

public interface GetTrialBalanceUseCase {
    record TrialBalanceEntry(String accountCode, String accountType,
                              java.math.BigDecimal debit, java.math.BigDecimal credit,
                              java.math.BigDecimal balance) {}
    List<TrialBalanceEntry> getTrialBalance();
}
