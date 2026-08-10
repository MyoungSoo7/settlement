package github.lms.lemuel.insurance.adapter.in.schedule;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.PayDueCommissionsUseCase;
import github.lms.lemuel.insurance.application.port.in.PayDueCommissionsUseCase.PayoutBatchResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 매일 새벽 수수료 회차 지급 배치 — D4.
 *
 * <p>환수 스윕(03:30) <b>이후</b>에 돈다 — terminal 계약의 미지급 회차는 스윕이 먼저
 * CANCELLED 로 소멸시켰으므로, 여기서는 ACTIVE 계약의 due 회차만 지급된다
 * (LAPSED 는 부활 여지가 있어 보류).
 */
@Component
public class CommissionPayoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(CommissionPayoutScheduler.class);

    private final PayDueCommissionsUseCase useCase;
    /** KST 기준 시각 소스 — due_date 판정이 UTC 면 지급이 하루 늦는다. */
    private final Clock clock;
    /** FC 수수료 실지급 — 잡 단위 감사 1건. */
    private final AuditLogger auditLogger;

    public CommissionPayoutScheduler(
            PayDueCommissionsUseCase useCase, Clock clock, AuditLogger auditLogger) {
        this.useCase = useCase;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    /** 매일 04:00 (KST) — 환수 스윕 이후. */
    @Scheduled(cron = "${app.insurance.batch.commission-payout-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "insurance-commission-payout", lockAtMostFor = "PT30M")
    public void payDue() {
        LocalDate today = LocalDate.now(clock);
        log.info("[CommissionPayout] 시작: today={}", today);
        PayoutBatchResult result = useCase.payDueOn(today);
        log.info("[CommissionPayout] 완료: 지급={} 보류={}", result.paid(), result.held());

        auditLogger.record(AuditAction.INSURANCE_COMMISSION_PAID, "CommissionPayoutJob", today.toString(),
                String.format("{\"date\":\"%s\",\"paid\":%d,\"held\":%d}",
                        today, result.paid(), result.held()));
    }
}
