package github.lms.lemuel.insurance.application.port.out;

import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.CommissionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 수수료 회차(CommissionSchedule) 조회 포트 — 지급·환수 배치 스캔.
 */
public interface LoadCommissionSchedulePort {

    /**
     * 지급 배치 스캔 — SCHEDULED 이면서 지급 예정일이 {@code date} 이전(포함)인 회차.
     *
     * <p>V1 부분 인덱스 {@code idx_commission_due_scheduled (WHERE status='SCHEDULED')} 사용.
     */
    List<CommissionSchedule> findDueScheduledOnOrBefore(LocalDate date);

    /** 특정 계약의 특정 상태 회차 전부. */
    List<CommissionSchedule> findByPolicyIdAndStatus(String policyId, CommissionStatus status);

    /**
     * 특정 계약의 기지급 합계 — D6 환수 계산의 paidTotal.
     *
     * <p>지급이 일어난 모든 회차의 {@code paid_amount} 합 (이후 환수 상태로 전이했어도 포함).
     * 지급 이력이 전혀 없으면 0.
     */
    BigDecimal sumPaidTotalByPolicyId(String policyId);

    /**
     * 월 마감 집계 — 해당 월에 지급(paid_at 기준)이 일어난 회차를 FC별로 합산한다.
     *
     * <p>이후 환수 상태로 전이한 회차도 포함한다 — "그 달에 지급된 사실"이 마감 기준이고,
     * 환수는 별도 이벤트 흐름으로 회계 처리된다.
     */
    List<FcPaidSummary> summarizePaidByFcInMonth(java.time.YearMonth month);

    /**
     * 월 마감 집계 행 — FC별 당월 지급 합계.
     *
     * @param fcId             설계사 식별자
     * @param totalPaidAmount  당월 지급 합계
     * @param installmentCount 당월 지급 회차 수
     */
    record FcPaidSummary(String fcId, BigDecimal totalPaidAmount, long installmentCount) {
    }
}
