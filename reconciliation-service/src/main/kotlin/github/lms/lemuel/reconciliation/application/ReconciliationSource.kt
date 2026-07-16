package github.lms.lemuel.reconciliation.application

import github.lms.lemuel.reconciliation.domain.ReconRecord
import java.time.LocalDate

/** Reconciliation period — closed range of settlement dates to reconcile. */
data class ReconPeriod(val from: LocalDate, val to: LocalDate) {
    companion object {
        fun day(date: LocalDate) = ReconPeriod(date, date)
    }
}

/** Which side of the reconciliation a source feeds. */
enum class SourceRole { EXPECTED, ACTUAL }

/**
 * Pluggable reconciliation source (a port).
 *
 * `fetch` is a **suspend** function so the application service can pull N
 * sources concurrently via coroutines. Implementations: bundled sample sources
 * (zero external deps) and HTTP skeletons pointing at settlement / payment.
 */
interface ReconciliationSource {
    val name: String
    val role: SourceRole
    suspend fun fetch(period: ReconPeriod): List<ReconRecord>
}
