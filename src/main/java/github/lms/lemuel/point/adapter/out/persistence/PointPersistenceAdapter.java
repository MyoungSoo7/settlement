package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.application.port.out.LoadPointPort;
import github.lms.lemuel.point.application.port.out.SavePointPort;
import github.lms.lemuel.point.domain.Point;
import github.lms.lemuel.point.domain.PointTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PointPersistenceAdapter implements LoadPointPort, SavePointPort {

    private final SpringDataPointRepository pointRepository;
    private final SpringDataPointTransactionRepository transactionRepository;
    private final PointPersistenceMapper mapper;

    @Override
    public Optional<Point> findByUserId(Long userId) {
        return pointRepository.findByUserId(userId).map(mapper::toDomain);
    }

    @Override
    public List<PointTransaction> findTransactionsByUserId(Long userId) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Point save(Point point) {
        PointJpaEntity entity = mapper.toEntity(point);
        PointJpaEntity saved = pointRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public PointTransaction saveTransaction(PointTransaction transaction) {
        PointTransactionJpaEntity entity = mapper.toEntity(transaction);
        PointTransactionJpaEntity saved = transactionRepository.save(entity);
        return mapper.toDomain(saved);
    }
}
