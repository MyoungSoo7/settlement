package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.ExpenseReport;

/**
 * 지출보고서 저장 포트.
 */
public interface SaveExpenseReportPort {

    ExpenseReport save(ExpenseReport report);
}
