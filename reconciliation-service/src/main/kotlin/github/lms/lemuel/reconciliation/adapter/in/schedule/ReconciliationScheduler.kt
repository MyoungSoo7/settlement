package github.lms.lemuel.reconciliation.adapter.`in`.schedule

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.ReconciliationSource
import github.lms.lemuel.reconciliation.application.RunReconciliationUseCase
import github.lms.lemuel.reconciliation.application.SourceRole
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * Scheduled reconciliation over the configured sources.
 *
 * Cron is env-driven (`app.reconciliation.cron`, default daily post-close
 * 19:00 Asia/Seoul). FAIL-OPEN: any source failure is caught and logged — a
 * flaky PG endpoint must never crash the scheduler thread or the app.
 *
 * 2026-07-30: 결과를 Micrometer 로도 내보낸다. 그전에는 로그에만 남아서, 배치가 샘플
 * 데이터로 매일 같은 가짜 불일치 4건을 뱉는 동안 아무 지표에도 흔적이 없었다.
 * 로그는 사람이 봐야 보이지만 지표는 알림을 걸 수 있다.
 *
 * 2026-08-20: 허용오차를 설정에서 읽는다. 그전에는 `app.reconciliation.tolerance-krw` 가
 * application.yml 에 선언돼 있는데도 읽는 코드가 없어(PRD 알려진 갭 G-3), 스케줄러는 인터페이스
 * 기본값 1 로 돌았다 — 운영자가 APP_RECONCILIATION_TOLERANCE_KRW 를 올려도 아무 일도 없었다.
 * 기본값이 그 1 과 같으므로 이 배선 자체가 판정을 바꾸지는 않는다. 바뀌는 건 "이제 조절이 된다" 는 것.
 */
@Component
class ReconciliationScheduler(
    private val service: RunReconciliationUseCase,
    private val sources: List<ReconciliationSource>,
    meterRegistry: MeterRegistry,
    @param:Value("\${app.reconciliation.tolerance-krw:1}") private val toleranceKrw: Long = 1,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val lastRunEpochSeconds = AtomicLong(0)
    private val lastDiscrepancies = AtomicLong(-1)
    private val lastExpectedCount = AtomicLong(-1)
    private val lastActualCount = AtomicLong(-1)

    private val runs = meterRegistry

    init {
        Gauge.builder("recon.last.run.epoch.seconds", lastRunEpochSeconds, AtomicLong::toDouble)
            .description("마지막 대사 실행 시각(epoch seconds) — 0=기동 후 아직 실행 없음")
            .register(meterRegistry)
        Gauge.builder("recon.last.discrepancies", lastDiscrepancies, AtomicLong::toDouble)
            .description("마지막 대사의 불일치 건수 — -1=아직 성공한 실행 없음")
            .register(meterRegistry)
        Gauge.builder("recon.last.records", lastExpectedCount, AtomicLong::toDouble)
            .tag("role", "expected")
            .description("마지막 대사에서 원천이 돌려준 행 수 — 0 이면 소스가 비었다는 뜻")
            .register(meterRegistry)
        Gauge.builder("recon.last.records", lastActualCount, AtomicLong::toDouble)
            .tag("role", "actual")
            .description("마지막 대사에서 원천이 돌려준 행 수 — 0 이면 소스가 비었다는 뜻")
            .register(meterRegistry)
    }

    @Scheduled(cron = "\${app.reconciliation.cron:0 0 19 * * *}", zone = "Asia/Seoul")
    fun runScheduled() {
        val period = ReconPeriod.day(LocalDate.now().minusDays(1)) // reconcile prior settlement day

        // 소스가 반쪽이면 대사 자체가 성립하지 않는다. 예전엔 이 경우가 없다고 가정해
        // (샘플이 항상 떠 있었으므로) 그냥 돌렸고, 결과가 허구여도 아무도 몰랐다.
        val roles = sources.map { it.role }.toSet()
        if (SourceRole.EXPECTED !in roles || SourceRole.ACTUAL !in roles) {
            runs.counter("recon.runs", "result", "misconfigured").increment()
            log.error(
                "대사 소스 미구성 — 실행하지 않는다 (등록된 소스={}). EXPECTED·ACTUAL 이 각각 1개 이상 필요하다.",
                sources.map { "${it.name}(${it.role})" },
            )
            return
        }

        log.info("scheduled reconciliation starting for {} (sources={})", period, sources.map { it.name })
        try {
            val report = runBlocking {
                service.reconcileFromSources(
                    sources = sources,
                    period = period,
                    toleranceKrw = toleranceKrw,
                )
            }
            lastRunEpochSeconds.set(Instant.now().epochSecond)
            lastDiscrepancies.set(report.discrepancyCount.toLong())
            lastExpectedCount.set(report.expectedCount.toLong())
            lastActualCount.set(report.actualCount.toLong())
            report.byType.forEach { (type, count) ->
                runs.counter("recon.discrepancies", "type", type.name).increment(count.toDouble())
            }
            log.info("scheduled {}", report.summaryLine())
            if (report.clean) {
                runs.counter("recon.runs", "result", "clean").increment()
            } else {
                runs.counter("recon.runs", "result", "discrepancy").increment()
                log.warn(
                    "scheduled reconciliation found {} discrepancies: {}",
                    report.discrepancyCount, report.byType,
                )
            }
        } catch (ex: Exception) {
            // fail-open: log and move on; never propagate.
            runs.counter("recon.runs", "result", "error").increment()
            log.error("scheduled reconciliation failed (source unavailable?) — skipping run", ex)
        }
    }
}
