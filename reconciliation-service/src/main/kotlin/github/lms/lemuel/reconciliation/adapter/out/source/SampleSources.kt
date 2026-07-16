package github.lms.lemuel.reconciliation.adapter.out.source

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.ReconciliationSource
import github.lms.lemuel.reconciliation.application.SourceRole
import github.lms.lemuel.reconciliation.domain.ReconRecord
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component

/**
 * Bundled EXPECTED source (settlement's view). Deterministic data crafted so the
 * demo surfaces every discrepancy type when diffed against [SampleActualSource].
 *
 * A small `delay` simulates network latency — with the ACTUAL source also
 * delaying, concurrent fetch is observably faster than serial.
 */
@Component
class SampleExpectedSource : ReconciliationSource {
    override val name = "sample-expected"
    override val role = SourceRole.EXPECTED

    override suspend fun fetch(period: ReconPeriod): List<ReconRecord> {
        delay(SIMULATED_LATENCY_MS)
        return listOf(
            ReconRecord("pay_1001", 10_000, "PAID"),   // clean match
            ReconRecord("pay_1002", 25_000, "PAID"),   // AMOUNT_MISMATCH (actual 24_000)
            ReconRecord("pay_1003", 5_000, "PAID"),    // MISSING (absent from actual)
            ReconRecord("pay_1004", 8_000, "PAID"),    // STATUS_MISMATCH (actual REFUNDED)
            ReconRecord("pay_1005", 12_000, "PAID"),   // within-tolerance match (actual 12_001)
        )
    }

    companion object { const val SIMULATED_LATENCY_MS = 150L }
}

/**
 * Bundled ACTUAL source (PG / payout view), intentionally diverging from
 * [SampleExpectedSource] so a demo run yields MISSING, EXTRA, AMOUNT_MISMATCH
 * and STATUS_MISMATCH.
 */
@Component
class SampleActualSource : ReconciliationSource {
    override val name = "sample-actual"
    override val role = SourceRole.ACTUAL

    override suspend fun fetch(period: ReconPeriod): List<ReconRecord> {
        delay(SIMULATED_LATENCY_MS)
        return listOf(
            ReconRecord("pay_1001", 10_000, "PAID"),     // clean match
            ReconRecord("pay_1002", 24_000, "PAID"),     // AMOUNT_MISMATCH (expected 25_000)
            // pay_1003 absent -> MISSING
            ReconRecord("pay_1004", 8_000, "REFUNDED"),  // STATUS_MISMATCH
            ReconRecord("pay_1005", 12_001, "PAID"),     // within 1 KRW tolerance -> match
            ReconRecord("pay_9001", 3_000, "PAID"),      // EXTRA (not in expected)
        )
    }

    companion object { const val SIMULATED_LATENCY_MS = 150L }
}
