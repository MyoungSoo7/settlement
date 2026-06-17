package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA Repository for Order
 */
public interface SpringDataOrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {

    List<OrderJpaEntity> findByUserId(Long userId);

    @Query("""
            SELECT o FROM OrderJpaEntity o
            WHERE o.userId = :userId
              AND (:status IS NULL OR o.status = :status)
              AND (:from IS NULL OR o.createdAt >= :from)
              AND (:to IS NULL OR o.createdAt < :to)
            ORDER BY o.createdAt DESC
            """)
    List<OrderJpaEntity> findUserOrders(Long userId, String status, LocalDateTime from, LocalDateTime to);

    /** 전체 주문 금액 합계 — cross-DB 금액 대사(ADR 0020 Phase 5.2)의 원천. */
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM OrderJpaEntity o")
    java.math.BigDecimal sumAmount();
}
