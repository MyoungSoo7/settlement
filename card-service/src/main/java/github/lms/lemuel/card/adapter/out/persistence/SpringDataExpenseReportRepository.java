package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * expense_reports Spring Data 레포지터리.
 */
public interface SpringDataExpenseReportRepository extends JpaRepository<ExpenseReportJpaEntity, Long> {

    Optional<ExpenseReportJpaEntity> findByReportId(String reportId);

    Optional<ExpenseReportJpaEntity> findByCaptureId(String captureId);
}
