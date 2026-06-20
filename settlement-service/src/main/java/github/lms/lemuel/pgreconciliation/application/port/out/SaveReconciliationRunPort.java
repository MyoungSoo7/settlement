package github.lms.lemuel.pgreconciliation.application.port.out;

import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;

public interface SaveReconciliationRunPort {

    /**
     * Run 메타와 자식 discrepancy 들을 한 번에 저장.
     */
    ReconciliationRun saveAll(ReconciliationRun run);

    /**
     * 운영자 액션 후 단건 update.
     */
    ReconciliationDiscrepancy save(ReconciliationDiscrepancy discrepancy);
}
