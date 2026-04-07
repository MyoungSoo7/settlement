package github.lms.lemuel.returns.adapter.out.persistence;

import github.lms.lemuel.returns.application.port.out.LoadReturnPort;
import github.lms.lemuel.returns.application.port.out.SaveReturnPort;
import github.lms.lemuel.returns.domain.ReturnOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Return Persistence Adapter
 */
@Component
@RequiredArgsConstructor
public class ReturnPersistenceAdapter implements LoadReturnPort, SaveReturnPort {

    private final SpringDataReturnRepository repository;

    @Override
    public Optional<ReturnOrder> findById(Long returnId) {
        return repository.findById(returnId)
                .map(ReturnPersistenceMapper::toDomain);
    }

    @Override
    public List<ReturnOrder> findByOrderId(Long orderId) {
        return repository.findByOrderId(orderId)
                .stream()
                .map(ReturnPersistenceMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<ReturnOrder> findByUserId(Long userId) {
        return repository.findByUserId(userId)
                .stream()
                .map(ReturnPersistenceMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<ReturnOrder> findByStatus(String status) {
        return repository.findByStatus(status)
                .stream()
                .map(ReturnPersistenceMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public ReturnOrder save(ReturnOrder returnOrder) {
        ReturnOrderJpaEntity entity = ReturnPersistenceMapper.toEntity(returnOrder);
        ReturnOrderJpaEntity saved = repository.save(entity);
        return ReturnPersistenceMapper.toDomain(saved);
    }
}
