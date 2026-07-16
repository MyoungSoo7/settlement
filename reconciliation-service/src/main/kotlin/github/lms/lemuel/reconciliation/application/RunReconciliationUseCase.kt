package github.lms.lemuel.reconciliation.application

import github.lms.lemuel.reconciliation.domain.ReconRecord
import github.lms.lemuel.reconciliation.domain.ReconciliationReport

/**
 * Inbound use-case port. Web/scheduler adapters depend on this interface
 * instead of the concrete [ReconciliationService], keeping the dependency
 * direction adapter → application-port (DIP) and letting tests substitute
 * the orchestration core.
 */
interface RunReconciliationUseCase {

    /** Reconcile pre-supplied record lists (POST /reconciliation/run). */
    fun reconcileRecords(
        expected: List<ReconRecord>,
        actual: List<ReconRecord>,
        toleranceKrw: Long,
    ): ReconciliationReport

    /** Reconcile by pulling from live sources concurrently. */
    suspend fun reconcileFromSources(
        sources: List<ReconciliationSource>,
        period: ReconPeriod,
        toleranceKrw: Long = 1,
    ): ReconciliationReport
}
