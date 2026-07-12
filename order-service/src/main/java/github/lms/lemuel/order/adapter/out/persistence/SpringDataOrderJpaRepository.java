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

    /**
     * 사용자 주문 목록 (선택적 status/기간 필터).
     *
     * <p>null-가드 파라미터에는 반드시 {@code CAST(:param AS ...)} 를 준다. PostgreSQL 은
     * {@code $n IS NULL} 형태로만 등장하는 bind 파라미터의 타입을 추론하지 못해
     * {@code SQLState 42P18 "could not determine data type of parameter"} 로 쿼리 전체가
     * 실패한다(프론트가 status/from/to 를 모두 null 로 호출하는 주문내역 조회에서 재현).
     * 명시적 CAST 로 타입을 고정해 우회한다.
     */
    @Query("""
            SELECT o FROM OrderJpaEntity o
            WHERE o.userId = :userId
              AND (CAST(:status AS string) IS NULL OR o.status = :status)
              AND (CAST(:from AS LocalDateTime) IS NULL OR o.createdAt >= :from)
              AND (CAST(:to AS LocalDateTime) IS NULL OR o.createdAt < :to)
            ORDER BY o.createdAt DESC
            """)
    List<OrderJpaEntity> findUserOrders(Long userId, String status, LocalDateTime from, LocalDateTime to);

    /** 전체 주문 금액 합계 — cross-DB 금액 대사(ADR 0020 Phase 5.2)의 원천. */
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM OrderJpaEntity o")
    java.math.BigDecimal sumAmount();
}
