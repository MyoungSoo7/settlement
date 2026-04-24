package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.Money;

public interface LoadAccountBalancePort {
    Money getBalance(Long accountId);
}
