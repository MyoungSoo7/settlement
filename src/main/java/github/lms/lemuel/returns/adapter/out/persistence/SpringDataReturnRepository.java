package github.lms.lemuel.returns.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA Repository for Return
 */
public interface SpringDataReturnRepository extends JpaRepository<ReturnOrderJpaEntity, Long> {

    List<ReturnOrderJpaEntity> findByOrderId(Long orderId);

    List<ReturnOrderJpaEntity> findByUserId(Long userId);

    List<ReturnOrderJpaEntity> findByStatus(String status);
}
