package github.lms.lemuel.closing.adapter.in.batch;

import github.lms.lemuel.closing.application.port.in.RunMonthlyClosingUseCase;
import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.exception.MonthlyClosingFailedException;
import github.lms.lemuel.closing.domain.exception.MonthlyClosingLockedException;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;

/**
 * 매월 1일 새벽 정보계 월마감 배치 — 직전 월의 셀러 정산 마트를 적재한다.
 *
 * <p>일 배치(홀드백 해제 03:00 등)가 끝난 뒤(04:30) 돌아 당일 확정분까지 반영한다.
 * 실패해도 FAILED run 이 이미 감사 기록으로 남아 있으므로 배치는 삼키고 로그만 남긴다 —
 * 재실행은 관리자 콘솔({@code POST /admin/monthly-closing/{ym}/run})이 정식 경로.
 */
@Component
public class MonthlyClosingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyClosingScheduler.class);

    private final RunMonthlyClosingUseCase useCase;
    /** KST 기준 시각 소스 — cron 은 KST 인데 본문 now() 가 UTC 면 월초 새벽에 대상 월이 어긋난다. */
    private final Clock clock;
    /** 월마감은 보고 수치를 확정하는 사건 — 잡 단위 감사 1건(성공/실패 공통). */
    private final AuditLogger auditLogger;

    public MonthlyClosingScheduler(RunMonthlyClosingUseCase useCase, Clock clock, AuditLogger auditLogger) {
        this.useCase = useCase;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    /** 매월 1일 04:30 (KST) — 직전 월 마감. */
    @Scheduled(cron = "${app.closing.monthly-cron:0 30 4 1 * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-monthly-closing", lockAtMostFor = "PT30M")
    public void closePreviousMonth() {
        YearMonth target = YearMonth.now(clock).minusMonths(1);
        log.info("[MonthlyClosing] 배치 시작: target={}", target);
        try {
            MonthlyClosingRun run = useCase.run(target, "scheduler");
            auditLogger.record(AuditAction.MONTHLY_CLOSING_EXECUTED, "MonthlyClosingJob", target.toString(),
                    String.format("{\"period\":\"%s\",\"status\":\"%s\",\"sellers\":%d,\"settlements\":%d,\"unmapped\":%d}",
                            target, run.getStatus(), run.getSellerCount(), run.getSettlementCount(),
                            run.getUnmappedCount()));
        } catch (MonthlyClosingLockedException e) {
            // 원장 마감된 기간에 마트가 이미 확정된 경우 — 재적재 불필요, 정상 종료로 간주.
            log.info("[MonthlyClosing] 이미 확정된 기간, 스킵: {}", e.getMessage());
        } catch (MonthlyClosingFailedException e) {
            // FAILED run 이 감사 기록으로 남아 있다 — 배치는 다음 달 재시도 대신 운영자 콘솔 재실행에 맡긴다.
            log.error("[MonthlyClosing] 배치 실패: target={}", target, e);
            auditLogger.record(AuditAction.MONTHLY_CLOSING_EXECUTED, "MonthlyClosingJob", target.toString(),
                    String.format("{\"period\":\"%s\",\"status\":\"FAILED\"}", target));
        }
    }
}
