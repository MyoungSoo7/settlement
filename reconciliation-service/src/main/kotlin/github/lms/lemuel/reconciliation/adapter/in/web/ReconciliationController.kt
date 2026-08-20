package github.lms.lemuel.reconciliation.adapter.`in`.web

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.ReconciliationSource
import github.lms.lemuel.reconciliation.application.RunReconciliationUseCase
import github.lms.lemuel.reconciliation.application.SourceRole
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    /**
     * 요청이 toleranceKrw 를 생략했을 때 쓰는 허용오차.
     *
     * 예전엔 컨트롤러 자기 상수(DEFAULT_TOLERANCE_KRW)를 썼고 application.yml 의
     * `app.reconciliation.tolerance-krw` 는 아무도 읽지 않았다(PRD 갭 G-3, 2026-08-20 감사에서
     * 재확인). 이제 스케줄러와 같은 값을 본다 — 같은 대사를 배치로 돌리든 API 로 돌리든 판정 기준이
     * 갈리면 안 되기 때문이다. 기본값은 종전 상수와 같은 1 이라 현재 동작은 그대로다.
     */
    @param:Value("\${app.reconciliation.tolerance-krw:1}") private val defaultToleranceKrw: Long = DEFAULT_TOLERANCE_KRW,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** POST /reconciliation/run — reconcile caller-supplied expected/actual sets. */
    @PostMapping("/run")
    fun run(@RequestBody req: RunRequest): ReportResponse {
        val tolerance = req.toleranceKrw ?: defaultToleranceKrw
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
                toleranceKrw = defaultToleranceKrw,
            )
        }
        log.info("demo {}", report.summaryLine())
        return ReportResponse.from(report)
    }

    companion object { const val DEFAULT_TOLERANCE_KRW = 1L }
}
