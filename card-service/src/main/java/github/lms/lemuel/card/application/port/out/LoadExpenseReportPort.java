package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.ExpenseReport;

import java.util.Optional;

/**
 * 지출보고서 조회 포트.
 */
public interface LoadExpenseReportPort {

    /** reportId(자연키)로 조회 */
    Optional<ExpenseReport> findByReportId(String reportId);

    /** captureId(매입번호 = 멱등 키)로 조회 */
    Optional<ExpenseReport> findByCaptureId(String captureId);
}
