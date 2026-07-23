package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.application.port.out.SaveSellerBankAccountRegistrationPort;
import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SellerBankAccountRegistryPersistenceAdapter
        implements LoadSellerBankAccountRegistrationPort, SaveSellerBankAccountRegistrationPort {

    private final SpringDataSellerBankAccountRepository repository;

    public SellerBankAccountRegistryPersistenceAdapter(SpringDataSellerBankAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SellerBankAccountRegistration> findBySellerId(Long sellerId) {
        if (sellerId == null) {
            return Optional.empty();
        }
        return repository.findById(sellerId).map(SellerBankAccountRegistryPersistenceAdapter::toDomain);
    }

    @Override
    public SellerBankAccountRegistration save(SellerBankAccountRegistration registration) {
        SellerBankAccountJpaEntity entity = repository.findById(registration.getSellerId())
                .map(existing -> {
                    existing.applyChange(registration.getBankCode(), registration.getAccountNumber(),
                            registration.getAccountHolder(), registration.getUpdatedAt());
                    return existing;
                })
                .orElseGet(() -> new SellerBankAccountJpaEntity(
                        registration.getSellerId(), registration.getBankCode(),
                        registration.getAccountNumber(), registration.getAccountHolder(),
                        registration.getUpdatedAt()));
        // saveAndFlush(save 아님) — 최초 등록 동시 경합(PK UNIQUE 위반)이 트랜잭션 커밋 시점까지 지연되지
        // 않고 이 호출에서 동기적으로 드러나야 SellerBankAccountRegistryService 가 안전하게 catch 해
        // 재조회-정정으로 재시도할 수 있다(그렇지 않으면 예외가 서비스 메서드 밖 커밋 단계에서 터져 500 으로 샌다).
        return toDomain(repository.saveAndFlush(entity));
    }

    private static SellerBankAccountRegistration toDomain(SellerBankAccountJpaEntity e) {
        return SellerBankAccountRegistration.rehydrate(
                e.getSellerId(), e.getBankCode(), e.getAccountNumber(), e.getAccountHolder(), e.getUpdatedAt());
    }
}
