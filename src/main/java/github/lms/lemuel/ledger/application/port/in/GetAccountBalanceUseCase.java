package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.Money;

public interface GetAccountBalanceUseCase {
    Money getBalance(String accountCode);
}
