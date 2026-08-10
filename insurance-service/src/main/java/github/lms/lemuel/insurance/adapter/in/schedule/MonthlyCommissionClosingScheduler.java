package github.lms.lemuel.insurance.adapter.in.schedule;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.CloseMonthlyCommissionUseCase;
import github.lms.lemuel.insurance.application.port.in.CloseMonthlyCommissionUseCase.MonthlyClosingResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 매월 1일 새벽 전월 수수료 마감 배치.
 *
 * <p>일 배치 3종(만기 판정 02:00 → 환수 스윕 03:30 → 회차 지급 04:00) 이후인 05:00 에 돌아
 * 전월의 마지막 지급분까지 마감에 포함된다.
 */
@Component
public class MonthlyCommissionClosingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyCommissionClosingScheduler.class);

    private final CloseMonthlyCommissionUseCase useCase;
    /** KST 기준 시각 소스 — "전월" 판정이 UTC 면 월 경계에서 한 달이 어긋난다. */
    private final Clock clock;
    /** 월 마감은 FC 수수료 지급 실적 확정 — 잡 단위 감사 1건. */
    private final AuditLogger auditLogger;

    public MonthlyCommissionClosingScheduler(
            CloseMonthlyCommissionUseCase useCase, Clock clock, AuditLogger auditLogger) {
        this.useCase = useCase;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    /** 매월 1일 05:00 (KST) — 전월을 마감한다. */
    @Scheduled(cron = "${app.insurance.batch.monthly-closing-cron:0 0 5 1 * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "insurance-monthly-closing", lockAtMostFor = "PT1H")
    public void closePreviousMonth() {
        YearMonth target = YearMonth.from(LocalDate.now(clock)).minusMonths(1);
        log.info("[MonthlyClosing] 시작: month={}", target);
        MonthlyClosingResult result = useCase.closeMonth(target);
        log.info("[MonthlyClosing] 완료: 마감 FC={} 스킵 FC={}",
                result.closedFcCount(), result.skippedFcCount());

        auditLogger.record(AuditAction.INSURANCE_COMMISSION_MONTHLY_CLOSED,
                "MonthlyCommissionClosingJob", target.toString(),
                String.format("{\"month\":\"%s\",\"closedFcCount\":%d,\"skippedFcCount\":%d}",
                        target, result.closedFcCount(), result.skippedFcCount()));
    }
}
