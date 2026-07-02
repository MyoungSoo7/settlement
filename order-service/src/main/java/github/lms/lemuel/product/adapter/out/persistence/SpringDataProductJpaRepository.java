package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    Optional<ProductJpaEntity> findByName(String name);

    boolean existsByName(String name);

    List<ProductJpaEntity> findByStatus(ProductStatus status);

    @Query("SELECT p FROM ProductJpaEntity p WHERE p.status = 'ACTIVE' AND p.stockQuantity > 0")
    List<ProductJpaEntity> findAvailableProducts();

    @Query("""
            SELECT p FROM ProductJpaEntity p
            WHERE (:categoryId IS NULL OR p.categoryId = :categoryId)
              AND (:keyword IS NULL
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(COALESCE(p.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    List<ProductJpaEntity> search(String keyword, Long categoryId);

    /**
     * 일반 상품 재고 원자적 차감 — 단일 조건부 UPDATE 로 "재고 검증 + 차감 + 매진 전이" 를 한 번에 처리.
     *
     * <p>{@code WHERE stock >= :qty} 가 DB row 락 안에서 평가되어, 동시 주문이 폭주해도 락 대기·재시도
     * 없이 보유 수량만큼만 성공시킨다(초과판매 방지). 영향 행 0 = 재고 부족·단종·미존재.
     * (옵션 상품과 달리 products 에는 @Version 컬럼이 없어 version 증가 절은 두지 않는다.)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ProductJpaEntity p " +
           "SET p.stockQuantity = p.stockQuantity - :qty, " +
           "    p.status = CASE WHEN p.stockQuantity - :qty = 0 " +
           "                    THEN github.lms.lemuel.product.domain.ProductStatus.OUT_OF_STOCK " +
           "                    ELSE p.status END, " +
           "    p.updatedAt = :now " +
           "WHERE p.id = :id " +
           "  AND p.stockQuantity >= :qty " +
           "  AND p.status <> github.lms.lemuel.product.domain.ProductStatus.DISCONTINUED")
    int decreaseStockIfAvailable(@Param("id") Long id, @Param("qty") int qty, @Param("now") LocalDateTime now);
}
