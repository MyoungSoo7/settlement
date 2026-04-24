package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.GetAccountBalanceUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadAccountBalancePort;
import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.domain.Account;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.ledger.domain.exception.AccountNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountBalanceService implements GetAccountBalanceUseCase {

    private final LoadAccountPort loadAccountPort;
    private final LoadAccountBalancePort loadAccountBalancePort;

    @Override
    public Money getBalance(String accountCode) {
        Account account = loadAccountPort.findByCode(accountCode)
                .orElseThrow(() -> new AccountNotFoundException(accountCode));
        return loadAccountBalancePort.getBalance(account.getId());
    }
}
