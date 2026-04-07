package github.lms.lemuel.seller.adapter.out.persistence;

import github.lms.lemuel.seller.application.port.out.LoadSellerPort;
import github.lms.lemuel.seller.application.port.out.SaveSellerPort;
import github.lms.lemuel.seller.domain.Seller;
import github.lms.lemuel.seller.domain.SellerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SellerPersistenceAdapter implements LoadSellerPort, SaveSellerPort {

    private final SpringDataSellerRepository repository;

    // ── SaveSellerPort ──────────────────────────────────────────────────

    @Override
    public Seller save(Seller seller) {
        SellerJpaEntity entity = SellerPersistenceMapper.toEntity(seller);
        SellerJpaEntity saved = repository.save(entity);
        return SellerPersistenceMapper.toDomain(saved);
    }

    // ── LoadSellerPort ──────────────────────────────────────────────────

    @Override
    public Optional<Seller> findById(Long sellerId) {
        return repository.findById(sellerId).map(SellerPersistenceMapper::toDomain);
    }

    @Override
    public Optional<Seller> findByUserId(Long userId) {
        return repository.findByUserId(userId).map(SellerPersistenceMapper::toDomain);
    }

    @Override
    public Optional<Seller> findByBusinessNumber(String businessNumber) {
        return repository.findByBusinessNumber(businessNumber).map(SellerPersistenceMapper::toDomain);
    }

    @Override
    public List<Seller> findAll() {
        return repository.findAll().stream()
                .map(SellerPersistenceMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Seller> findByStatus(SellerStatus status) {
        return repository.findByStatus(status.name()).stream()
                .map(SellerPersistenceMapper::toDomain)
                .collect(Collectors.toList());
    }
}
