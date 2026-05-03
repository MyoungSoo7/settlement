package github.lms.lemuel.pgreconciliation.application.port.out;

import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;

import java.util.List;
import java.util.Optional;

public interface LoadReconciliationRunPort {

    Optional<ReconciliationRun> findById(Long id);

    List<ReconciliationRun> findRecent(int limit);

    Optional<ReconciliationDiscrepancy> findDiscrepancyById(Long id);
}
