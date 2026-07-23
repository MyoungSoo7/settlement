package github.lms.lemuel.integrity.adapter.in.batch;

import github.lms.lemuel.integrity.application.port.in.IntegrityQueryUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * 정합성 검증 상시 실행 스케줄러 — Integrity Suite 판정 6종(INV-5·6·7·8·11·13)을 매일 자동 실행한다.
 *
 * <p>기존엔 {@code /admin/integrity/**} 또는 MCP 호출식이라 사람이 트리거하지 않으면 위반이 방치됐다.
 * 이 스케줄러가 상시 감시를 보장한다: 각 체크의 {@code ok=false} 는 ERROR 로그(reasons 포함) + 메트릭
 * {@code settlement.integrity.violation{check}} 로 노출해 알람으로 연계한다.
 *
 * <p><b>체크별 격리(fail-soft):</b> refund-adjustments 는 order 내부 API 를 호출하므로 order 장애 시 예외가
 * 오를 수 있다. 각 체크를 독립 try/catch 로 감싸 한 체크 실패가 나머지를 막지 않게 하고, 예외도 위반과
 * 동일하게 메트릭({@code result=error})으로 집계한다.
 *
 * <p>INV-10(processed-count)은 발행측 대조가 있어야 판정이 서므로(소비 분자만 노출) 이 자동 감시에서는 제외한다.
 */
@Component
public class IntegrityMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntegrityMonitorScheduler.class);

    private static final String METRIC_VIOLATION = "settlement.integrity.violation";

    private final IntegrityQueryUseCase useCase;
    private final MeterRegistry meterRegistry;
    /** KST 기준 시각 소스 — 전일 기준일 계산이 JVM 타임존에 흔들리지 않게 한다. */
    private final Clock clock;

    public IntegrityMonitorScheduler(IntegrityQueryUseCase useCase,
                                     MeterRegistry meterRegistry,
                                     Clock clock) {
        this.useCase = useCase;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** 매일 06:00 (KST) — 대사(05:00) 이후. cron zone·본문 now(clock) 모두 KST. */
    @Scheduled(cron = "${app.integrity.monitor-cron:0 0 6 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-integrity-monitor", lockAtMostFor = "PT15M")
    public void runDailyChecks() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        log.info("[IntegrityMonitor] 시작: 기준일={}", yesterday);

        runCheck("ledger-completeness", () -> {
            var r = useCase.checkLedgerCompleteness(yesterday, null);
            return new Verdict(r.ok(), r.reasons());
        });
        runCheck("payout-recon", () -> {
            var r = useCase.checkPayoutRecon(yesterday);
            return new Verdict(r.ok(), r.reasons());
        });
        runCheck("holdback-status", () -> {
            var r = useCase.checkHoldbackStatus();
            return new Verdict(r.ok(), r.reasons());
        });
        runCheck("stuck-states", () -> {
            var r = useCase.checkStuckStates(null);
            return new Verdict(r.ok(), r.reasons());
        });
        runCheck("refund-adjustments", () -> {
            var r = useCase.checkRefundAdjustments(yesterday, yesterday);
            return new Verdict(r.ok(), r.reasons());
        });
        runCheck("payout-bounce-recon", () -> {
            var r = useCase.checkPayoutBounceRecon();
            return new Verdict(r.ok(), r.reasons());
        });

        log.info("[IntegrityMonitor] 완료: 기준일={}", yesterday);
    }

    /**
     * 체크 1건을 독립 실행. 위반 시 ERROR + violation{check,result=violation}, 예외 시 ERROR +
     * violation{check,result=error}. 어느 경우도 다음 체크로 진행한다.
     */
    private void runCheck(String check, Supplier<Verdict> checkFn) {
        try {
            Verdict v = checkFn.get();
            if (!v.ok()) {
                meterRegistry.counter(METRIC_VIOLATION, "check", check, "result", "violation").increment();
                log.error("[IntegrityMonitor] 위반 감지 check={} reasons={}", check, v.reasons());
            }
        } catch (RuntimeException e) {
            meterRegistry.counter(METRIC_VIOLATION, "check", check, "result", "error").increment();
            log.error("[IntegrityMonitor] 체크 실패(스킵) check={} — {}", check, e.toString());
        }
    }

    private record Verdict(boolean ok, List<String> reasons) {
    }
}
