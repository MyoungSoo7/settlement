package github.lms.lemuel.tax.adapter.out.persistence;

import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.SaveSellerTaxProfilePort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxType;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SellerTaxProfileRegistryPersistenceAdapter
        implements LoadSellerTaxProfilePort, SaveSellerTaxProfilePort {

    private final SpringDataSellerTaxProfileRepository repository;

    public SellerTaxProfileRegistryPersistenceAdapter(SpringDataSellerTaxProfileRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SellerTaxProfile> findBySellerId(Long sellerId) {
        if (sellerId == null) {
            return Optional.empty();
        }
        return repository.findById(sellerId).map(SellerTaxProfileRegistryPersistenceAdapter::toDomain);
    }

    @Override
    public SellerTaxProfile save(SellerTaxProfile profile) {
        SellerTaxProfileJpaEntity entity = repository.findById(profile.getSellerId())
                .map(existing -> {
                    existing.applyChange(profile.getTaxType().name(), profile.getBusinessRegNo(),
                            profile.getUpdatedAt());
                    return existing;
                })
                .orElseGet(() -> new SellerTaxProfileJpaEntity(profile.getSellerId(),
                        profile.getTaxType().name(), profile.getBusinessRegNo(), profile.getUpdatedAt()));
        // saveAndFlush — 최초 등록 동시 경합(PK UNIQUE 위반)을 저장 호출 시점에 동기적으로 드러내
        // SellerTaxProfileRegistryService 가 재조회-정정으로 재시도할 수 있게 한다(SellerBankAccount 와 동형).
        return toDomain(repository.saveAndFlush(entity));
    }

    private static SellerTaxProfile toDomain(SellerTaxProfileJpaEntity e) {
        return SellerTaxProfile.rehydrate(e.getSellerId(), TaxType.valueOf(e.getTaxType()),
                e.getBusinessRegNo(), e.getUpdatedAt());
    }
}
