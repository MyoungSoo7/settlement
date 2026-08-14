package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadExpenseReportPort;
import github.lms.lemuel.card.application.port.out.SaveExpenseReportPort;
import github.lms.lemuel.card.domain.ExpenseReport;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 지출보고서 영속 어댑터 — {@link LoadExpenseReportPort} + {@link SaveExpenseReportPort} 구현.
 */
@Component
public class ExpenseReportPersistenceAdapter
        implements LoadExpenseReportPort, SaveExpenseReportPort {

    private final SpringDataExpenseReportRepository repository;

    public ExpenseReportPersistenceAdapter(SpringDataExpenseReportRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ExpenseReport> findByReportId(String reportId) {
        return repository.findByReportId(reportId)
                .map(ExpenseReportJpaEntity::toDomain);
    }

    @Override
    public Optional<ExpenseReport> findByCaptureId(String captureId) {
        return repository.findByCaptureId(captureId)
                .map(ExpenseReportJpaEntity::toDomain);
    }

    @Override
    public ExpenseReport save(ExpenseReport report) {
        return repository.saveAndFlush(ExpenseReportJpaEntity.fromDomain(report)).toDomain();
    }
}
