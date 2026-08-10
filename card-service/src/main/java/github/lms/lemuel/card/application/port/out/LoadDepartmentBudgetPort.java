package github.lms.lemuel.card.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 부서 예산 조회 포트.
 */
public interface LoadDepartmentBudgetPort {

    /**
     * 조직·부서·기간으로 예산 레코드 조회.
     *
     * @return 예산 레코드가 없으면 {@code Optional.empty()}
     */
    Optional<DepartmentBudgetRecord> findBudget(Long organizationId, String departmentId,
                                                 int year, int month);

    /**
     * 조회 결과 레코드.
     *
     * @param id            PK
     * @param organizationId 조직 ID
     * @param departmentId  부서 ID
     * @param year          년도
     * @param month         월
     * @param totalBudget   총 예산
     * @param approvedAmount 승인된 지출액
     */
    record DepartmentBudgetRecord(
            Long id,
            Long organizationId,
            String departmentId,
            int year,
            int month,
            BigDecimal totalBudget,
            BigDecimal approvedAmount
    ) {
    }
}
