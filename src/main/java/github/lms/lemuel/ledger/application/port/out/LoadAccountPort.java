package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.Account;
import java.util.Optional;

public interface LoadAccountPort {
    Optional<Account> findByCode(String code);
    Account getOrCreate(Account account);
}
