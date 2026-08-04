package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.ExpenseReport;

/**
 * 관리자(MANAGER/OWNER)가 지출보고서를 반려하는 유스케이스 포트.
 *
 * <p>SUBMITTED → REJECTED 전이. 임직원은 수정 후 재제출 가능.
 */
public interface RejectExpenseReportUseCase {

    ExpenseReport reject(RejectExpenseReportCommand command);

    /**
     * 지출보고서 반려 커맨드.
     *
     * @param reportId     지출보고서 ID
     * @param reviewerId   반려자 userId
     * @param rejectReason 반려 사유
     */
    record RejectExpenseReportCommand(
            String reportId,
            Long reviewerId,
            String rejectReason
    ) {
    }
}
