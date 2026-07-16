package github.lms.lemuel.reconciliation.adapter.`in`.schedule

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.ReconciliationService
import github.lms.lemuel.reconciliation.application.ReconciliationSource
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Scheduled reconciliation over the configured sources.
 *
 * Cron is env-driven (`app.reconciliation.cron`, default daily post-close
 * 19:00 Asia/Seoul). FAIL-OPEN: any source failure is caught and logged — a
 * flaky PG endpoint must never crash the scheduler thread or the app.
 */
@Component
class ReconciliationScheduler(
    private val service: ReconciliationService,
    private val sources: List<ReconciliationSource>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.reconciliation.cron:0 0 19 * * *}", zone = "Asia/Seoul")
    fun runScheduled() {
        val period = ReconPeriod.day(LocalDate.now().minusDays(1)) // reconcile prior settlement day
        log.info("scheduled reconciliation starting for {}", period)
        try {
            val report = runBlocking {
                service.reconcileFromSources(sources = sources, period = period)
            }
            log.info("scheduled {}", report.summaryLine())
            if (!report.clean) {
                log.warn(
                    "scheduled reconciliation found {} discrepancies: {}",
                    report.discrepancyCount, report.byType,
                )
                // TODO: emit to notification-service for alerting.
            }
        } catch (ex: Exception) {
            // fail-open: log and move on; never propagate.
            log.error("scheduled reconciliation failed (source unavailable?) — skipping run", ex)
        }
    }
}
