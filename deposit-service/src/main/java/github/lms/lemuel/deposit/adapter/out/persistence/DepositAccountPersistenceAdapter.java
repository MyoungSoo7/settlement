package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.application.port.out.LoadDepositAccountPort;
import github.lms.lemuel.deposit.application.port.out.SaveDepositAccountPort;
import github.lms.lemuel.deposit.domain.SellerDepositAccount;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DepositAccountPersistenceAdapter
        implements LoadDepositAccountPort, SaveDepositAccountPort {

    private final SpringDataDepositAccountRepository repo;

    public DepositAccountPersistenceAdapter(SpringDataDepositAccountRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<SellerDepositAccount> findBySellerId(Long sellerId) {
        return repo.findBySellerId(sellerId).map(this::toDomain);
    }

    @Override
    public Optional<SellerDepositAccount> findBySellerIdForUpdate(Long sellerId) {
        return repo.findBySellerIdForUpdate(sellerId).map(this::toDomain);
    }

    @Override
    public SellerDepositAccount save(SellerDepositAccount account) {
        DepositAccountJpaEntity entity;
        if (account.getId() == null) {
            entity = new DepositAccountJpaEntity(
                    account.getSellerId(),
                    account.getAvailable(),
                    account.getLocked(),
                    account.getTotal());
        } else {
            entity = repo.findById(account.getId())
                    .orElseThrow(() -> new IllegalStateException("계좌를 찾을 수 없습니다: " + account.getId()));
            entity.setAvailable(account.getAvailable());
            entity.setLocked(account.getLocked());
            entity.setTotal(account.getTotal());
        }
        DepositAccountJpaEntity saved = repo.save(entity);
        if (account.getId() == null) {
            account.assignId(saved.getId());
        }
        return account;
    }

    private SellerDepositAccount toDomain(DepositAccountJpaEntity e) {
        return SellerDepositAccount.rehydrate(
                e.getId(), e.getSellerId(),
                e.getAvailable(), e.getLocked(), e.getTotal(),
                e.getVersion(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
