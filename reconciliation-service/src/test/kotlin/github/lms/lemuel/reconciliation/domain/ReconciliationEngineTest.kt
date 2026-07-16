package github.lms.lemuel.reconciliation.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconciliationEngineTest {

    private val engine = ReconciliationEngine(toleranceKrw = 1)

    @Test
    fun `exact matches are omitted and counted as matched`() {
        val expected = listOf(ReconRecord("k1", 1000, "PAID"), ReconRecord("k2", 2000, "PAID"))
        val actual = listOf(ReconRecord("k1", 1000, "PAID"), ReconRecord("k2", 2000, "PAID"))

        val report = engine.reconcile(expected, actual)

        assertEquals(2, report.matchedCount)
        assertTrue(report.clean)
        assertEquals(0, report.discrepancyCount)
    }

    @Test
    fun `amount within tolerance is a match`() {
        val report = engine.reconcile(
            expected = listOf(ReconRecord("k1", 1000, "PAID")),
            actual = listOf(ReconRecord("k1", 1001, "PAID")), // diff 1 == tolerance
        )
        assertEquals(1, report.matchedCount)
        assertTrue(report.clean)
    }

    @Test
    fun `amount outside tolerance is AMOUNT_MISMATCH with signed diff`() {
        val report = engine.reconcile(
            expected = listOf(ReconRecord("k1", 1000, "PAID")),
            actual = listOf(ReconRecord("k1", 998, "PAID")), // diff 2 > tolerance
        )
        assertEquals(0, report.matchedCount)
        val d = report.discrepancies.single()
        assertTrue(d is Discrepancy.AmountMismatch)
        d as Discrepancy.AmountMismatch
        assertEquals(2, d.diffKrw) // expected - actual = 1000 - 998
        assertEquals(DiscrepancyType.AMOUNT_MISMATCH, d.type)
    }

    @Test
    fun `key only in expected is MISSING`() {
        val report = engine.reconcile(
            expected = listOf(ReconRecord("k1", 1000, "PAID")),
            actual = emptyList(),
        )
        val d = report.discrepancies.single()
        assertTrue(d is Discrepancy.Missing)
        assertEquals(DiscrepancyType.MISSING, d.type)
    }

    @Test
    fun `key only in actual is EXTRA`() {
        val report = engine.reconcile(
            expected = emptyList(),
            actual = listOf(ReconRecord("k9", 3000, "PAID")),
        )
        val d = report.discrepancies.single()
        assertTrue(d is Discrepancy.Extra)
        assertEquals(DiscrepancyType.EXTRA, d.type)
    }

    @Test
    fun `same amount different status is STATUS_MISMATCH`() {
        val report = engine.reconcile(
            expected = listOf(ReconRecord("k1", 1000, "PAID")),
            actual = listOf(ReconRecord("k1", 1000, "REFUNDED")),
        )
        val d = report.discrepancies.single()
        assertTrue(d is Discrepancy.StatusMismatch)
        assertEquals(DiscrepancyType.STATUS_MISMATCH, d.type)
    }

    @Test
    fun `amount mismatch takes precedence over status mismatch`() {
        val report = engine.reconcile(
            expected = listOf(ReconRecord("k1", 1000, "PAID")),
            actual = listOf(ReconRecord("k1", 5000, "REFUNDED")),
        )
        // both amount and status differ -> reported once as AMOUNT_MISMATCH
        val d = report.discrepancies.single()
        assertEquals(DiscrepancyType.AMOUNT_MISMATCH, d.type)
    }

    @Test
    fun `mixed set aggregates counts by type`() {
        val expected = listOf(
            ReconRecord("m", 100, "PAID"),   // match
            ReconRecord("am", 200, "PAID"),  // amount mismatch
            ReconRecord("mi", 300, "PAID"),  // missing
            ReconRecord("st", 400, "PAID"),  // status mismatch
        )
        val actual = listOf(
            ReconRecord("m", 100, "PAID"),
            ReconRecord("am", 250, "PAID"),
            ReconRecord("st", 400, "REFUNDED"),
            ReconRecord("ex", 500, "PAID"),  // extra
        )

        val report = engine.reconcile(expected, actual)

        assertEquals(1, report.matchedCount)
        assertEquals(4, report.discrepancyCount)
        assertEquals(1, report.byType[DiscrepancyType.AMOUNT_MISMATCH])
        assertEquals(1, report.byType[DiscrepancyType.MISSING])
        assertEquals(1, report.byType[DiscrepancyType.STATUS_MISMATCH])
        assertEquals(1, report.byType[DiscrepancyType.EXTRA])
        assertEquals(4, report.expectedCount)
        assertEquals(4, report.actualCount)
    }

    @Test
    fun `zero tolerance flags any amount difference`() {
        val strict = ReconciliationEngine(toleranceKrw = 0)
        val report = strict.reconcile(
            expected = listOf(ReconRecord("k1", 1000, "PAID")),
            actual = listOf(ReconRecord("k1", 1001, "PAID")),
        )
        assertEquals(1, report.discrepancyCount)
        assertEquals(DiscrepancyType.AMOUNT_MISMATCH, report.discrepancies.single().type)
    }
}
