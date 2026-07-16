package github.lms.lemuel.reconciliation.application

import github.lms.lemuel.reconciliation.domain.DiscrepancyType
import github.lms.lemuel.reconciliation.domain.ReconRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
class ReconciliationServiceTest {

    private val service = ReconciliationService()
    private val period = ReconPeriod.day(LocalDate.of(2026, 7, 16))

    /** Fake source that records invocation and delays, to prove concurrency. */
    private class FakeSource(
        override val name: String,
        override val role: SourceRole,
        private val records: List<ReconRecord>,
        private val delayMs: Long,
        private val callCounter: AtomicInteger,
    ) : ReconciliationSource {
        override suspend fun fetch(period: ReconPeriod): List<ReconRecord> {
            callCounter.incrementAndGet()
            delay(delayMs)
            return records
        }
    }

    @Test
    fun `reconcileRecords diffs supplied sets`() {
        val report = service.reconcileRecords(
            expected = listOf(ReconRecord("k1", 1000, "PAID")),
            actual = listOf(ReconRecord("k1", 2000, "PAID")),
            toleranceKrw = 1,
        )
        assertEquals(1, report.discrepancyCount)
        assertEquals(DiscrepancyType.AMOUNT_MISMATCH, report.discrepancies.single().type)
    }

    @Test
    fun `both sources are fetched and results merged`() = runTest {
        val calls = AtomicInteger(0)
        val expectedSrc = FakeSource(
            "exp", SourceRole.EXPECTED, listOf(ReconRecord("k1", 1000, "PAID")), 100, calls,
        )
        val actualSrc = FakeSource(
            "act", SourceRole.ACTUAL, listOf(ReconRecord("k1", 1000, "PAID")), 100, calls,
        )

        val report = service.reconcileFromSources(listOf(expectedSrc, actualSrc), period)

        assertEquals(2, calls.get()) // both sources called
        assertEquals(1, report.matchedCount)
        assertTrue(report.clean)
    }

    @Test
    fun `sources are fetched concurrently not serially`() = runTest {
        val calls = AtomicInteger(0)
        // Each source delays 500ms of virtual time. Serial => 1000ms, concurrent => 500ms.
        val expectedSrc = FakeSource(
            "exp", SourceRole.EXPECTED, listOf(ReconRecord("k1", 1000, "PAID")), 500, calls,
        )
        val actualSrc = FakeSource(
            "act", SourceRole.ACTUAL, listOf(ReconRecord("k1", 1000, "PAID")), 500, calls,
        )

        // runTest's virtual clock advances by the *max* of concurrently-delayed children.
        val start = testScheduler.currentTime
        service.reconcileFromSources(listOf(expectedSrc, actualSrc), period)
        val elapsed = testScheduler.currentTime - start

        assertEquals(500, elapsed, "concurrent fetch should take ~max(delay), not sum")
        assertEquals(2, calls.get())
    }

    @Test
    fun `multiple sources per role are all fetched and merged`() = runTest {
        val calls = AtomicInteger(0)
        val exp1 = FakeSource("e1", SourceRole.EXPECTED, listOf(ReconRecord("a", 100, "PAID")), 50, calls)
        val exp2 = FakeSource("e2", SourceRole.EXPECTED, listOf(ReconRecord("b", 200, "PAID")), 50, calls)
        val act1 = FakeSource("a1", SourceRole.ACTUAL, listOf(ReconRecord("a", 100, "PAID")), 50, calls)
        val act2 = FakeSource("a2", SourceRole.ACTUAL, listOf(ReconRecord("b", 200, "PAID")), 50, calls)

        val report = service.reconcileFromSources(listOf(exp1, exp2, act1, act2), period)

        assertEquals(4, calls.get())
        assertEquals(2, report.expectedCount)
        assertEquals(2, report.actualCount)
        assertEquals(2, report.matchedCount)
    }
}
