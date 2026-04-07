package github.lms.lemuel.point.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataPointTransactionRepository extends JpaRepository<PointTransactionJpaEntity, Long> {
    List<PointTransactionJpaEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<PointTransactionJpaEntity> findByPointId(Long pointId);
}
