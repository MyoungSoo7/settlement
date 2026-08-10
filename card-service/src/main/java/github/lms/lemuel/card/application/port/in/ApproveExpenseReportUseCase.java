package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.ExpenseReport;

/**
 * 관리자(MANAGER/OWNER)가 지출보고서를 승인하는 유스케이스 포트.
 *
 * <p>SUBMITTED → APPROVED 전이. 승인 시 부서 예산 소진액이 갱신된다.
 */
public interface ApproveExpenseReportUseCase {

    ExpenseReport approve(ApproveExpenseReportCommand command);

    /**
     * 지출보고서 승인 커맨드.
     *
     * @param reportId   지출보고서 ID
     * @param reviewerId 승인자 userId
     */
    record ApproveExpenseReportCommand(
            String reportId,
            Long reviewerId
    ) {
    }
}
