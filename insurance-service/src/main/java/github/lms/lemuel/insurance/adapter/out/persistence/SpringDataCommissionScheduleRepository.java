package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.CommissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SpringDataCommissionScheduleRepository
        extends JpaRepository<CommissionScheduleJpaEntity, Long> {

    /** 지급 배치 스캔 — idx_commission_due_scheduled 부분 인덱스와 일치. */
    List<CommissionScheduleJpaEntity> findByStatusAndDueDateLessThanEqual(
            CommissionStatus status, LocalDate date);

    List<CommissionScheduleJpaEntity> findByPolicyIdAndStatus(UUID policyId, CommissionStatus status);

    /**
     * D6 환수 계산의 paidTotal — 지급이 일어난 모든 회차의 paid_amount 합.
     * 이후 환수 상태(CLAWBACK_PENDING·CLAWED_BACK)로 전이한 회차도 "기지급"이므로 포함한다.
     */
    @Query("""
            select coalesce(sum(c.paidAmount), 0)
            from CommissionScheduleJpaEntity c
            where c.policyId = :policyId and c.paidAmount is not null
            """)
    BigDecimal sumPaidTotalByPolicyId(@Param("policyId") UUID policyId);

    /**
     * 월 마감 집계 — [from, to) 구간에 지급된 회차의 FC별 합계.
     * 이후 환수 상태로 전이한 회차도 포함 (paid_at 존재가 기준).
     */
    @Query("""
            select new github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort$FcPaidSummary(
                c.fcId, sum(c.paidAmount), count(c))
            from CommissionScheduleJpaEntity c
            where c.paidAt >= :fromInclusive and c.paidAt < :toExclusive
              and c.paidAmount is not null
            group by c.fcId
            order by c.fcId
            """)
    List<github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort.FcPaidSummary>
    summarizePaidByFcBetween(
            @Param("fromInclusive") java.time.Instant fromInclusive,
            @Param("toExclusive") java.time.Instant toExclusive);
}
