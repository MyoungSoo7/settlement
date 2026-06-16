package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.LoanStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LoanAdvanceRepository extends JpaRepository<LoanAdvanceJpaEntity, Long> {

    List<LoanAdvanceJpaEntity> findBySellerIdOrderByIdAsc(Long sellerId);

    /** 셀러의 미상환 대출을 FIFO(id 오름차순)로 비관적 락 조회 — 상환 차감용. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from LoanAdvanceJpaEntity l
            where l.sellerId = :sellerId and l.status = :status
            order by l.id asc
            """)
    List<LoanAdvanceJpaEntity> findBySellerAndStatusForUpdate(@Param("sellerId") Long sellerId,
                                                              @Param("status") LoanStatus status);
}
