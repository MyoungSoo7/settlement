package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.recovery.application.port.in.EscalateStaleRecoveryUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * 매일 새벽 정체 채권(seed-p0-6 SellerRecovery) 이관 배치.
 *
 * <p>OPEN 채권은 후속 정산 확정 때만 자동 상계된다 — 셀러가 활동을 멈추면 그 기회 자체가 오지
 * 않아 영구히 OPEN 으로 남는다. 유예 기간({@code app.recovery.manual-escalation-days}) 동안
 * 활동(발생·상계) 이 없었던 채권을 MANUAL_REQUIRED 로 이관해 운영자 개입 대상으로 남긴다.
 * 홀드백 해제 배치({@link HoldbackReleaseScheduler}) 직후에 돌도록 기본 03:30 로 둔다.
 */
@Component
public class RecoveryEscalationScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryEscalationScheduler.class);

    private final EscalateStaleRecoveryUseCase useCase;
    private final Clock clock;
    private final AuditLogger auditLogger;

    public RecoveryEscalationScheduler(EscalateStaleRecoveryUseCase useCase, Clock clock,
                                       AuditLogger auditLogger) {
        this.useCase = useCase;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    @Scheduled(cron = "${app.recovery.manual-escalation-cron:0 30 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-recovery-escalation", lockAtMostFor = "PT30M")
    public void escalateStale() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        log.info("[RecoveryEscalation] 시작: now={}", now);
        int escalated = useCase.escalateStaleOpenRecoveries(now);
        log.info("[RecoveryEscalation] 완료: 이관 건수={}", escalated);

        // 정체 채권 이관은 운영자 개입 트리거 — 잡 단위 감사로 "언제 몇 건 정체됐는지" 추적.
        auditLogger.record(AuditAction.SELLER_RECOVERY_ESCALATED, "RecoveryEscalationJob", now.toString(),
                String.format("{\"now\":\"%s\",\"escalated\":%d}", now, escalated));
    }
}
