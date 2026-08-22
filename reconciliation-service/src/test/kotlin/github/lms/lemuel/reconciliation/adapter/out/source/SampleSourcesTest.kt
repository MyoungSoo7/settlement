package github.lms.lemuel.reconciliation.adapter.out.source

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.SourceRole
import github.lms.lemuel.reconciliation.domain.DiscrepancyType
import github.lms.lemuel.reconciliation.domain.ReconciliationEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The bundled demo sources.
 *
 * They exist so the service demonstrates every discrepancy type with no
 * external dependency — which is only true if the two datasets actually diverge
 * in all four ways. If someone "fixes" one of the rows the demo silently stops
 * demonstrating anything, and nobody notices because the run goes green.
 */
class SampleSourcesTest {

    private val period = ReconPeriod.day(LocalDate.of(2026, 8, 21))

    @Test
    fun `the sample sources declare the two sides of a reconciliation`() {
        assertEquals(SourceRole.EXPECTED, SampleExpectedSource().role)
        assertEquals(SourceRole.ACTUAL, SampleActualSource().role)
        assertEquals("sample-expected", SampleExpectedSource().name)
        assertEquals("sample-actual", SampleActualSource().name)
    }

    @Test
    fun `the demo dataset surfaces every discrepancy type`() {
        val expected = runBlocking { SampleExpectedSource().fetch(period) }
        val actual = runBlocking { SampleActualSource().fetch(period) }

        val report = ReconciliationEngine(toleranceKrw = 1).reconcile(expected, actual)

        val byType = report.byType
        assertEquals(1, byType[DiscrepancyType.MISSING], "pay_1003 should be missing from actual")
        assertEquals(1, byType[DiscrepancyType.EXTRA], "pay_9001 should be extra in actual")
        assertEquals(1, byType[DiscrepancyType.AMOUNT_MISMATCH], "pay_1002 differs by 1,000 KRW")
        assertEquals(1, byType[DiscrepancyType.STATUS_MISMATCH], "pay_1004 is PAID vs REFUNDED")
    }

    @Test
    fun `a 1 KRW difference stays inside the tolerance and is not a discrepancy`() {
        val expected = runBlocking { SampleExpectedSource().fetch(period) }
        val actual = runBlocking { SampleActualSource().fetch(period) }

        val report = ReconciliationEngine(toleranceKrw = 1).reconcile(expected, actual)

        // pay_1005 is 12,000 vs 12,001 — the whole point of having a tolerance.
        assertTrue(report.discrepancies.none { it.businessKey == "pay_1005" },
            "pay_1005 must reconcile within tolerance: ${report.discrepancies}")
        assertEquals(2, report.matchedCount, "pay_1001 (exact) and pay_1005 (within tolerance)")
    }

    @Test
    fun `both sample sources simulate latency so concurrent fetch is observable`() {
        // Serial fetch of two 150ms sources would take ~300ms; the application
        // service pulls them concurrently. A zero delay would make that
        // improvement invisible and the demo pointless.
        assertTrue(SampleExpectedSource.SIMULATED_LATENCY_MS > 0)
        assertEquals(SampleExpectedSource.SIMULATED_LATENCY_MS, SampleActualSource.SIMULATED_LATENCY_MS)
    }
}
