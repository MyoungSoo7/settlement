package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.domain.Account;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AccountPersistenceAdapter implements LoadAccountPort {

    private final SpringDataAccountJpaRepository accountRepository;
    private final LedgerPersistenceMapper mapper;

    @Override
    public Optional<Account> findByCode(String code) {
        return accountRepository.findByCode(code).map(mapper::toDomain);
    }

    @Override
    public Account getOrCreate(Account account) {
        return accountRepository.findByCode(account.getCode())
                .map(mapper::toDomain)
                .orElseGet(() -> {
                    AccountJpaEntity entity = new AccountJpaEntity(
                            account.getCode(), account.getName(), account.getType().name());
                    AccountJpaEntity saved = accountRepository.save(entity);
                    return mapper.toDomain(saved);
                });
    }
}
