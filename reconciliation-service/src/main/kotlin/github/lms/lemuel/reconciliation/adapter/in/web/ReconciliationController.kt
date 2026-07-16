package github.lms.lemuel.reconciliation.adapter.`in`.web

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.ReconciliationSource
import github.lms.lemuel.reconciliation.application.RunReconciliationUseCase
import github.lms.lemuel.reconciliation.application.SourceRole
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/reconciliation")
class ReconciliationController(
    private val service: RunReconciliationUseCase,
    private val sources: List<ReconciliationSource>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** POST /reconciliation/run — reconcile caller-supplied expected/actual sets. */
    @PostMapping("/run")
    fun run(@RequestBody req: RunRequest): ReportResponse {
        val tolerance = req.toleranceKrw ?: DEFAULT_TOLERANCE_KRW
        val report = service.reconcileRecords(
            expected = req.expected.map { it.toDomain() },
            actual = req.actual.map { it.toDomain() },
            toleranceKrw = tolerance,
        )
        log.info(report.summaryLine())
        return ReportResponse.from(report)
    }

    /**
     * GET /reconciliation/demo — runs the bundled sample sources end-to-end,
     * concurrently, and returns a report containing every discrepancy type.
     * MVC endpoint, so we bridge into the coroutine service via runBlocking.
     */
    @GetMapping("/demo")
    fun demo(): ReportResponse {
        val sampleSources = sources.filter {
            it.name == "sample-expected" || it.name == "sample-actual"
        }.ifEmpty { sources.filter { it.role == SourceRole.EXPECTED || it.role == SourceRole.ACTUAL } }

        val report = runBlocking {
            service.reconcileFromSources(
                sources = sampleSources,
                period = ReconPeriod.day(LocalDate.now()),
                toleranceKrw = DEFAULT_TOLERANCE_KRW,
            )
        }
        log.info("demo {}", report.summaryLine())
        return ReportResponse.from(report)
    }

    companion object { const val DEFAULT_TOLERANCE_KRW = 1L }
}
