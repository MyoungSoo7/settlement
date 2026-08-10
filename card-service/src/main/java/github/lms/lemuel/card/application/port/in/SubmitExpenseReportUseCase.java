package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.ExpenseReport;

/**
 * 임직원이 영수증·카테고리·메모를 첨부해 지출보고서를 제출하는 유스케이스 포트.
 *
 * <p>DRAFT 또는 REJECTED(반려 후 재제출) 상태에서 SUBMITTED 로 전이한다.
 */
public interface SubmitExpenseReportUseCase {

    ExpenseReport submit(SubmitExpenseReportCommand command);

    /**
     * 지출보고서 제출 커맨드.
     *
     * @param reportId        지출보고서 ID
     * @param receiptUrl      영수증 URL(optional — 재제출 시 필수 권장)
     * @param expenseCategory 경비 카테고리(예: MEAL, OFFICE_SUPPLIES, MEETING, TRANSPORT)
     * @param memo            메모(optional)
     */
    record SubmitExpenseReportCommand(
            String reportId,
            String receiptUrl,
            String expenseCategory,
            String memo
    ) {
    }
}
