package github.lms.lemuel.card.application.port.in;

import java.math.BigDecimal;

/**
 * 부서 예산 소진율 조회 유스케이스 포트.
 *
 * <p>승인된 지출보고서 금액 합계 / 총 예산 = 소진율(%).
 */
public interface QueryBudgetUtilizationUseCase {

    BudgetUtilization getUtilization(Long organizationId, String departmentId,
                                     int year, int month);

    /**
     * 부서 예산 소진율.
     *
     * @param organizationId    조직 ID
     * @param departmentId      부서 ID
     * @param year              년도
     * @param month             월
     * @param totalBudget       총 예산(BigDecimal)
     * @param approvedAmount    승인된 지출액(BigDecimal)
     * @param utilizationPercent 소진율(%) = approvedAmount / totalBudget × 100, 소수점 2자리
     */
    record BudgetUtilization(
            Long organizationId,
            String departmentId,
            int year,
            int month,
            BigDecimal totalBudget,
            BigDecimal approvedAmount,
            BigDecimal utilizationPercent
    ) {
    }
}
