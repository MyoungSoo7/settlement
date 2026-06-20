package github.lms.lemuel.coupon.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataCouponJpaRepository extends JpaRepository<CouponJpaEntity, Long> {
    Optional<CouponJpaEntity> findByCode(String code);

    /**
     * 사용 한도 내에서만 사용 횟수를 1 증가시키는 원자적 UPDATE.
     * 동시 요청에서도 used_count <= max_uses 불변식을 DB가 보장한다.
     * 영향 행 수가 0이면 이미 소진된 것.
     */
    @Modifying
    @Query("UPDATE CouponJpaEntity c SET c.usedCount = c.usedCount + 1, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.usedCount < c.maxUses")
    int incrementUsedCountIfAvailable(@Param("id") Long id);
}