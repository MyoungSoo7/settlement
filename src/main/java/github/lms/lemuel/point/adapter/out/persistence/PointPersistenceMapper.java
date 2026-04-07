package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.Point;
import github.lms.lemuel.point.domain.PointTransaction;
import github.lms.lemuel.point.domain.PointTransactionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class PointPersistenceMapper {

    public Point toDomain(PointJpaEntity entity) {
        Point point = new Point();
        point.setId(entity.getId());
        point.setUserId(entity.getUserId());
        point.setBalance(entity.getBalance());
        point.setTotalEarned(entity.getTotalEarned());
        point.setTotalUsed(entity.getTotalUsed());
        point.setTransactions(new ArrayList<>());
        point.setCreatedAt(entity.getCreatedAt());
        point.setUpdatedAt(entity.getUpdatedAt());
        return point;
    }

    public PointJpaEntity toEntity(Point domain) {
        PointJpaEntity entity = new PointJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setBalance(domain.getBalance());
        entity.setTotalEarned(domain.getTotalEarned());
        entity.setTotalUsed(domain.getTotalUsed());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public PointTransaction toDomain(PointTransactionJpaEntity entity) {
        PointTransaction tx = new PointTransaction();
        tx.setId(entity.getId());
        tx.setUserId(entity.getUserId());
        tx.setPointId(entity.getPointId());
        tx.setType(PointTransactionType.fromString(entity.getType()));
        tx.setAmount(entity.getAmount());
        tx.setBalanceAfter(entity.getBalanceAfter());
        tx.setDescription(entity.getDescription());
        tx.setReferenceType(entity.getReferenceType());
        tx.setReferenceId(entity.getReferenceId());
        tx.setExpiresAt(entity.getExpiresAt());
        tx.setCreatedAt(entity.getCreatedAt());
        return tx;
    }

    public PointTransactionJpaEntity toEntity(PointTransaction domain) {
        PointTransactionJpaEntity entity = new PointTransactionJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setPointId(domain.getPointId());
        entity.setType(domain.getType().name());
        entity.setAmount(domain.getAmount());
        entity.setBalanceAfter(domain.getBalanceAfter());
        entity.setDescription(domain.getDescription());
        entity.setReferenceType(domain.getReferenceType());
        entity.setReferenceId(domain.getReferenceId());
        entity.setExpiresAt(domain.getExpiresAt());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }
}
