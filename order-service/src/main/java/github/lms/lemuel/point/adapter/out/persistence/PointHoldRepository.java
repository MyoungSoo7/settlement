package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointHoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface PointHoldRepository extends JpaRepository<PointHoldJpaEntity, Long> {

    Optional<PointHoldJpaEntity> findByReferenceTypeAndReferenceId(String referenceType,
                                                                   String referenceId);

    /**
     * 계정이 지금 잠그고 있는 총액.
     *
     * <p>{@code coalesce} 로 0 을 보장한다 — 선점이 하나도 없는 계정에서 {@code sum} 은 0 이 아니라
     * null 을 돌려주고, 그대로 나가면 3자 대조가 계정을 볼 때마다 터진다.
     */
    @Query("""
            select coalesce(sum(h.amount), 0) from PointHoldJpaEntity h
            where h.accountId = :accountId and h.status = :status
            """)
    BigDecimal sumActive(@Param("accountId") Long accountId,
                         @Param("status") PointHoldStatus status);
}
