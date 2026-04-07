package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadStockReservationPort;
import github.lms.lemuel.product.application.port.out.SaveStockReservationPort;
import github.lms.lemuel.product.domain.ReservationStatus;
import github.lms.lemuel.product.domain.StockReservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class StockReservationPersistenceAdapter implements LoadStockReservationPort, SaveStockReservationPort {

    private final SpringDataStockReservationRepository repository;
    private final StockReservationPersistenceMapper mapper;

    @Override
    public StockReservation save(StockReservation reservation) {
        StockReservationJpaEntity entity = mapper.toJpaEntity(reservation);
        StockReservationJpaEntity saved = repository.save(entity);
        return mapper.toDomainEntity(saved);
    }

    @Override
    public Optional<StockReservation> findById(Long id) {
        return repository.findById(id)
                .map(mapper::toDomainEntity);
    }

    @Override
    public List<StockReservation> findActiveByProductId(Long productId) {
        return repository.findByProductIdAndStatus(productId, ReservationStatus.RESERVED.name()).stream()
                .map(mapper::toDomainEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<StockReservation> findExpiredReservations() {
        return repository.findExpiredReservations().stream()
                .map(mapper::toDomainEntity)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<StockReservation> findByUserIdAndProductId(Long userId, Long productId) {
        return repository.findByUserIdAndProductIdAndStatus(userId, productId, ReservationStatus.RESERVED.name())
                .map(mapper::toDomainEntity);
    }
}
