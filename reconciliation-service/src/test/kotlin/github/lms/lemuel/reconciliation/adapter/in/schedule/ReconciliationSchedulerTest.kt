package github.lms.lemuel.reconciliation.adapter.`in`.schedule

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.ReconciliationSource
import github.lms.lemuel.reconciliation.application.RunReconciliationUseCase
import github.lms.lemuel.reconciliation.application.SourceRole
import github.lms.lemuel.reconciliation.domain.Discrepancy
import github.lms.lemuel.reconciliation.domain.DiscrepancyType
import github.lms.lemuel.reconciliation.domain.ReconRecord
import github.lms.lemuel.reconciliation.domain.ReconciliationReport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The scheduled batch.
 *
 * Two failures this covers are ones the service actually had:
 *  - it ran with only one side configured and reported a "result" that was
 *    arithmetically meaningless (everything looked MISSING or EXTRA), and
 *  - the outcome lived only in logs, so a batch producing the same four fake
 *    discrepancies every night left no metric anyone could alert on.
 *
 * It also has to stay **fail-open**: a flaky PG endpoint must never kill the
 * scheduler thread.
 */
class ReconciliationSchedulerTest {

    private fun source(sourceName: String, sourceRole: SourceRole,
                       records: List<ReconRecord> = emptyList()) =
        object : ReconciliationSource {
            override val name = sourceName
            override val role = sourceRole
            override suspend fun fetch(period: ReconPeriod) = records
        }

    private fun report(
        discrepancies: List<Discrepancy> = emptyList(),
        expectedCount: Int = 2,
        actualCount: Int = 2,
    ) = ReconciliationReport(
        expectedCount = expectedCount,
        actualCount = actualCount,
        matchedCount = expectedCount - discrepancies.size,
        discrepancies = discrepancies,
        toleranceKrw = 1,
    )

    /** Records what the scheduler asked for; answers with a canned report. */
    private class FakeUseCase(
        private val answer: () -> ReconciliationReport,
    ) : RunReconciliationUseCase {
        var period: ReconPeriod? = null
        var tolerance: Long? = null
        var calls = 0

        override fun reconcileRecords(
            expected: List<ReconRecord>,
            actual: List<ReconRecord>,
            toleranceKrw: Long,
        ): ReconciliationReport = throw UnsupportedOperationException("not used by the scheduler")

        override suspend fun reconcileFromSources(
            sources: List<ReconciliationSource>,
            period: ReconPeriod,
            toleranceKrw: Long,
        ): ReconciliationReport {
            calls++
            this.period = period
            this.tolerance = toleranceKrw
            return answer()
        }
    }

    private fun gauge(registry: MeterRegistry, name: String, vararg tags: String): Double =
        registry.get(name).let { if (tags.isEmpty()) it else it.tags(*tags) }.gauge().value()

    private fun counter(registry: MeterRegistry, name: String, vararg tags: String): Double =
        registry.find(name).tags(*tags).counter()?.count() ?: 0.0

    @Test
    fun `gauges start at their not-yet-run sentinels`() {
        val registry = SimpleMeterRegistry()
        ReconciliationScheduler(FakeUseCase { report() }, emptyList(), registry)

        // 0 vs -1 is deliberate: "never ran" must be distinguishable from
        // "ran and found nothing", otherwise a dead batch looks healthy.
        assertEquals(0.0, gauge(registry, "recon.last.run.epoch.seconds"))
        assertEquals(-1.0, gauge(registry, "recon.last.discrepancies"))
        assertEquals(-1.0, gauge(registry, "recon.last.records", "role", "expected"))
        assertEquals(-1.0, gauge(registry, "recon.last.records", "role", "actual"))
    }

    @Test
    fun `a half-configured source set is refused instead of producing a fake result`() {
        val registry = SimpleMeterRegistry()
        val useCase = FakeUseCase { report() }
        val scheduler = ReconciliationScheduler(
            useCase,
            listOf(source("only-expected", SourceRole.EXPECTED)),
            registry,
        )

        scheduler.runScheduled()

        assertEquals(0, useCase.calls, "with one side missing there is nothing to reconcile against")
        assertEquals(1.0, counter(registry, "recon.runs", "result", "misconfigured"))
        assertEquals(-1.0, gauge(registry, "recon.last.discrepancies"), "no run means no result")
    }

    @Test
    fun `no sources at all is also refused`() {
        val registry = SimpleMeterRegistry()
        val useCase = FakeUseCase { report() }

        ReconciliationScheduler(useCase, emptyList(), registry).runScheduled()

        assertEquals(0, useCase.calls)
        assertEquals(1.0, counter(registry, "recon.runs", "result", "misconfigured"))
    }

    @Test
    fun `a clean run publishes counts and marks the run clean`() {
        val registry = SimpleMeterRegistry()
        val useCase = FakeUseCase { report(expectedCount = 5, actualCount = 5) }
        val scheduler = ReconciliationScheduler(
            useCase,
            listOf(source("e", SourceRole.EXPECTED), source("a", SourceRole.ACTUAL)),
            registry,
        )

        scheduler.runScheduled()

        assertEquals(1, useCase.calls)
        assertEquals(1.0, counter(registry, "recon.runs", "result", "clean"))
        assertEquals(0.0, counter(registry, "recon.runs", "result", "discrepancy"))
        assertEquals(0.0, gauge(registry, "recon.last.discrepancies"))
        assertEquals(5.0, gauge(registry, "recon.last.records", "role", "expected"))
        assertEquals(5.0, gauge(registry, "recon.last.records", "role", "actual"))
        assertTrue(gauge(registry, "recon.last.run.epoch.seconds") > 0.0, "run time must be stamped")
    }

    @Test
    fun `discrepancies are counted per type so alerts can route on them`() {
        val registry = SimpleMeterRegistry()
        val useCase = FakeUseCase {
            report(
                listOf(
                    Discrepancy.Missing("pay_1", ReconRecord("pay_1", 5_000, "PAID")),
                    Discrepancy.Extra("pay_9", ReconRecord("pay_9", 3_000, "PAID")),
                    Discrepancy.AmountMismatch(
                        "pay_2",
                        ReconRecord("pay_2", 25_000, "PAID"),
                        ReconRecord("pay_2", 24_000, "PAID"),
                        1_000,
                    ),
                ),
                expectedCount = 4,
                actualCount = 4,
            )
        }
        val scheduler = ReconciliationScheduler(
            useCase,
            listOf(source("e", SourceRole.EXPECTED), source("a", SourceRole.ACTUAL)),
            registry,
        )

        scheduler.runScheduled()

        assertEquals(1.0, counter(registry, "recon.runs", "result", "discrepancy"))
        assertEquals(0.0, counter(registry, "recon.runs", "result", "clean"))
        assertEquals(3.0, gauge(registry, "recon.last.discrepancies"))
        assertEquals(1.0, counter(registry, "recon.discrepancies", "type", DiscrepancyType.MISSING.name))
        assertEquals(1.0, counter(registry, "recon.discrepancies", "type", DiscrepancyType.EXTRA.name))
        assertEquals(
            1.0,
            counter(registry, "recon.discrepancies", "type", DiscrepancyType.AMOUNT_MISMATCH.name),
        )
        assertEquals(
            0.0,
            counter(registry, "recon.discrepancies", "type", DiscrepancyType.STATUS_MISMATCH.name),
        )
    }

    @Test
    fun `it reconciles the prior settlement day`() {
        val registry = SimpleMeterRegistry()
        val useCase = FakeUseCase { report() }

        ReconciliationScheduler(
            useCase,
            listOf(source("e", SourceRole.EXPECTED), source("a", SourceRole.ACTUAL)),
            registry,
        ).runScheduled()

        // Today's settlements are still moving; the closed day is the only one
        // both sides can agree on.
        val yesterday = LocalDate.now().minusDays(1)
        assertEquals(ReconPeriod.day(yesterday), useCase.period)
    }

    @Test
    fun `the configured tolerance actually reaches the use case`() {
        val registry = SimpleMeterRegistry()
        val useCase = FakeUseCase { report() }

        ReconciliationScheduler(
            useCase,
            listOf(source("e", SourceRole.EXPECTED), source("a", SourceRole.ACTUAL)),
            registry,
            toleranceKrw = 50,
        ).runScheduled()

        // This was the bug: the property was declared in yaml but nothing read
        // it, so raising APP_RECONCILIATION_TOLERANCE_KRW changed nothing.
        assertEquals(50L, useCase.tolerance)
    }

    @Test
    fun `a failing source is logged as an error run and never propagates`() {
        val registry = SimpleMeterRegistry()
        val useCase = FakeUseCase { throw IllegalStateException("PG endpoint down") }
        val scheduler = ReconciliationScheduler(
            useCase,
            listOf(source("e", SourceRole.EXPECTED), source("a", SourceRole.ACTUAL)),
            registry,
        )

        // fail-open: no exception escapes, or the scheduler thread dies for good.
        scheduler.runScheduled()

        assertEquals(1.0, counter(registry, "recon.runs", "result", "error"))
        assertEquals(-1.0, gauge(registry, "recon.last.discrepancies"), "a failed run must not publish a result")
        assertNull(registry.find("recon.discrepancies").counter())
    }
}
