package github.lms.lemuel.report.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 대사 불변식 #2(|Σ(adjustments)| = Σ(refunds linked)) 전용 조정-환불 정합 집계 역할.
 *
 * <p>{@link LoadPeriodReconciliationPort} 의 응집 축 중 하나 — 조정/환불 원장 정합만 보는 소비처는
 * 이 역할만 의존하면 된다(ISP).
 */
public interface LoadAdjustmentReconciliationPort {

    /**
     * 기간 내 정산 조정(adjustments.amount) 절대값 합계.
     * 스키마 상 amount 는 항상 음수이므로 {@code -SUM(amount)} 로 양수 변환해서 반환.
     */
    BigDecimal sumAdjustmentsAbsolute(LocalDate from, LocalDate to);

    /**
     * 기간 내 조정에 연결된 refunds 의 amount 합계 (status='COMPLETED' 만).
     * adjustments JOIN refunds 로 1:1 연결을 집계. 정상이면 {@link #sumAdjustmentsAbsolute} 와 같아야 한다.
     */
    BigDecimal sumRefundsLinkedToAdjustments(LocalDate from, LocalDate to);
}
