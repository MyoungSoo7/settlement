package github.lms.lemuel.reconciliation.application

import github.lms.lemuel.reconciliation.domain.ReconRecord
import github.lms.lemuel.reconciliation.domain.ReconciliationEngine
import github.lms.lemuel.reconciliation.domain.ReconciliationReport
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Application service orchestrating reconciliation.
 *
 * The Kotlin win here is CONCURRENT fetch: expected and actual (and any extra
 * sources) are pulled inside a single `coroutineScope` with `async`, so N
 * sources are fetched in parallel, not serially, then diffed by the pure engine.
 */
@Service
class ReconciliationService {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Reconcile pre-supplied record lists (used by POST /reconciliation/run).
     * Engine is instantiated per-call so tolerance is request-scoped.
     */
    fun reconcileRecords(
        expected: List<ReconRecord>,
        actual: List<ReconRecord>,
        toleranceKrw: Long,
    ): ReconciliationReport =
        ReconciliationEngine(toleranceKrw).reconcile(expected, actual)

    /**
     * Reconcile by pulling from live sources CONCURRENTLY.
     *
     * All EXPECTED-role sources are merged into one expected set, all
     * ACTUAL-role into one actual set. Every source's `fetch` launches as its
     * own `async` inside the same scope, so they overlap. If any source throws,
     * the scope propagates it — callers that must fail-open (the scheduler)
     * wrap this in their own try/catch.
     */
    suspend fun reconcileFromSources(
        sources: List<ReconciliationSource>,
        period: ReconPeriod,
        toleranceKrw: Long = 1,
    ): ReconciliationReport = coroutineScope {
        require(sources.any { it.role == SourceRole.EXPECTED }) { "need >=1 EXPECTED source" }
        require(sources.any { it.role == SourceRole.ACTUAL }) { "need >=1 ACTUAL source" }

        // Kick every source off concurrently, keeping its role alongside the Deferred.
        val fetches = sources.map { src ->
            src.role to async {
                log.debug("fetching from source '{}' ({}) for {}", src.name, src.role, period)
                src.fetch(period)
            }
        }
        // Await all — parallelism happens here, not one-at-a-time.
        val results: List<Pair<SourceRole, List<ReconRecord>>> =
            fetches.map { (role, deferred) -> role to deferred.await() }

        val expected = results.filter { it.first == SourceRole.EXPECTED }.flatMap { it.second }
        val actual = results.filter { it.first == SourceRole.ACTUAL }.flatMap { it.second }

        ReconciliationEngine(toleranceKrw).reconcile(expected, actual)
    }
}
