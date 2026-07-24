package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.LoanStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LoanAdvanceRepository extends JpaRepository<LoanAdvanceJpaEntity, Long> {

    List<LoanAdvanceJpaEntity> findBySellerIdOrderByIdAsc(Long sellerId);

    /** 셀러의 미상환 대출을 FIFO(id 오름차순)로 비관적 락 조회 — 상환 차감용. 여러 상태(DISBURSED·OVERDUE)를 함께 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from LoanAdvanceJpaEntity l
            where l.sellerId = :sellerId and l.status in :statuses
            order by l.id asc
            """)
    List<LoanAdvanceJpaEntity> findBySellerAndStatusesForUpdate(@Param("sellerId") Long sellerId,
                                                                @Param("statuses") java.util.Collection<LoanStatus> statuses);

    /**
     * 만기(dueAt) 경과분 스캔 — 자동 연체/상각 배치용. dueAt NULL(구 데이터)은 제외되고, 만기 오래된 순으로
     * 반환한다(오래된 연체부터 처리). 부분 인덱스 idx_loan_advances_due_at_active 가 핫패스를 받친다.
     */
    @Query("""
            select l from LoanAdvanceJpaEntity l
            where l.status = :status and l.dueAt is not null and l.dueAt < :asOf
            order by l.dueAt asc
            """)
    List<LoanAdvanceJpaEntity> findByStatusAndDueAtBefore(@Param("status") LoanStatus status,
                                                          @Param("asOf") LocalDateTime asOf);
}
