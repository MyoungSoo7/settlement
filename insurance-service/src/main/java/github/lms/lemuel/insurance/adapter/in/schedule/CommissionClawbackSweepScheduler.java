package github.lms.lemuel.insurance.adapter.in.schedule;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.SweepCommissionClawbacksUseCase;
import github.lms.lemuel.insurance.application.port.in.SweepCommissionClawbacksUseCase.ClawbackSweepResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 매일 새벽 환수(clawback) 스윕 배치 — D6.
 *
 * <p>만기 판정(02:00) <b>이후</b>, 수수료 지급(04:00) <b>이전</b>에 돈다 — 오늘 종결된 계약의
 * 미지급 회차를 지급 배치가 집기 전에 소멸시키고, 기지급 회차를 환수 대기로 전환한다.
 */
@Component
public class CommissionClawbackSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(CommissionClawbackSweepScheduler.class);

    private final SweepCommissionClawbacksUseCase useCase;
    /** KST 기준 시각 소스 — 환수액 공식의 경과 개월 m 이 UTC 로 하루 어긋나면 환수액이 달라진다. */
    private final Clock clock;
    /** 환수 전환은 FC 수령액을 되돌리는 금전적 사건 — 잡 단위 감사 1건. */
    private final AuditLogger auditLogger;

    public CommissionClawbackSweepScheduler(
            SweepCommissionClawbacksUseCase useCase, Clock clock, AuditLogger auditLogger) {
        this.useCase = useCase;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    /** 매일 03:30 (KST) — 만기 판정과 지급 사이. */
    @Scheduled(cron = "${app.insurance.batch.clawback-sweep-cron:0 30 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "insurance-clawback-sweep", lockAtMostFor = "PT30M")
    public void sweep() {
        LocalDate today = LocalDate.now(clock);
        log.info("[ClawbackSweep] 시작: today={}", today);
        ClawbackSweepResult result = useCase.sweepOn(today);
        log.info("[ClawbackSweep] 완료: 미지급소멸={} 환수계약={} 환수회차={}",
                result.cancelledInstallments(), result.flaggedPolicies(), result.flaggedInstallments());

        auditLogger.record(AuditAction.INSURANCE_COMMISSION_CLAWBACK_FLAGGED,
                "ClawbackSweepJob", today.toString(),
                String.format("{\"date\":\"%s\",\"cancelled\":%d,\"flaggedPolicies\":%d,\"flaggedInstallments\":%d}",
                        today, result.cancelledInstallments(),
                        result.flaggedPolicies(), result.flaggedInstallments()));
    }
}
