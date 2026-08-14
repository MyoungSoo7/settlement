package github.lms.lemuel.card.application.port.out;

import java.math.BigDecimal;

/**
 * 부서 예산 소진액 갱신 포트.
 *
 * <p>지출보고서 승인 시 해당 부서·기간의 {@code approved_amount} 를 증가시킨다.
 * 예산 레코드가 없으면 신규 생성(upsert).
 */
public interface UpdateDepartmentBudgetPort {

    /**
     * 승인 금액을 누적한다.
     *
     * @param organizationId 조직 ID
     * @param departmentId   부서 ID
     * @param year           년도
     * @param month          월
     * @param approvedAmount 증가시킬 금액(양수)
     */
    void incrementApprovedAmount(Long organizationId, String departmentId,
                                  int year, int month, BigDecimal approvedAmount);
}
