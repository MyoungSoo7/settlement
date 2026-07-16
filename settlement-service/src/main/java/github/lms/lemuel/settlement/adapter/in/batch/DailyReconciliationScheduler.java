package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.settlement.application.port.in.ReconcileDailyTotalsUseCase;
import github.lms.lemuel.settlement.domain.ReconciliationReport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/**
 * 일일 대사 상시 실행 스케줄러 — 전일 캡처일 기준 양축(금액·건수) 대사를 매일 자동 실행한다.
 *
 * <p>기존엔 {@code /admin/reconciliation} 또는 MCP 호출식이라 사람이 트리거하지 않으면 돌지 않았다.
 * 이 스케줄러가 상시 가동을 보장한다. 불일치 판정·ERROR 로그·메트릭은 {@link ReconcileDailyTotalsUseCase}
 * 가 담당하고, 여기서는 <b>불일치 시 관제 신호</b>를 추가로 쏜다.
 *
 * <p><b>Fail-soft:</b> 대사는 order 내부 API 를 호출하므로(daily-totals·daily-counts), order 가 내려가 있으면
 * {@code OrderReconUnavailableException}(또는 임의 예외)이 올라온다. 이는 <b>배치/관리 작업</b>이라 해당 run 만
 * ERROR 로 남기고 스킵하며, 예외를 삼켜 스케줄러 스레드가 죽지 않게 한다(다음 날 다시 시도). 관제 신호도
 * best-effort 라 절대 throw 하지 않는다.
 */
@Component
public class DailyReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReconciliationScheduler.class);

    private final ReconcileDailyTotalsUseCase reconcileUseCase;
    private final OpsSignalPort opsSignalPort;
    /** KST 기준 시각 소스 — 전일(targetDate) 계산이 JVM 기본 타임존에 흔들리지 않게 한다. */
    private final Clock clock;

    public DailyReconciliationScheduler(ReconcileDailyTotalsUseCase reconcileUseCase,
                                        OpsSignalPort opsSignalPort,
                                        Clock clock) {
        this.reconcileUseCase = reconcileUseCase;
        this.opsSignalPort = opsSignalPort;
        this.clock = clock;
    }

    /**
     * 매일 05:00 (KST) 전일 대사 실행. 정산 확정(03:00)·홀드백 해제(03:00)·출금(04:00) 이후라 그날치
     * 정산이 자리를 잡은 뒤 대사한다. cron zone·본문 now(clock) 모두 KST 로 고정.
     */
    @Scheduled(cron = "${app.reconciliation.daily-cron:0 0 5 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-daily-reconciliation", lockAtMostFor = "PT15M")
    public void reconcileYesterday() {
        LocalDate targetDate = LocalDate.now(clock).minusDays(1);
        log.info("[DailyRecon] 시작: targetDate={}", targetDate);
        try {
            ReconciliationReport report = reconcileUseCase.reconcile(targetDate);
            if (!report.matched()) {
                // 관제 신호 — best-effort. 불일치 총량·축별 diff 를 비식별 메타로만 싣는다.
                opsSignalPort.emit(OpsSignalCategory.SETTLEMENT_FAILED, "reconciliation", targetDate.toString(),
                        Map.of("captureDiscrepancy", report.captureDiscrepancy().toPlainString(),
                                "refundDiscrepancy", report.refundDiscrepancy().toPlainString(),
                                "countDiscrepancy", String.valueOf(report.countDiscrepancy()),
                                "reason", "DAILY_RECON_MISMATCH"));
            }
            log.info("[DailyRecon] 완료: targetDate={}, matched={}", targetDate, report.matched());
        } catch (RuntimeException e) {
            // order 무응답 등 — 해당 run 만 실패 처리(스킵), 스케줄러는 계속 살아 다음 날 재시도.
            log.error("[DailyRecon] 실패(스킵): targetDate={} — {}", targetDate, e.toString());
        }
    }
}
