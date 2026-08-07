package github.lms.lemuel.insurance.adapter.in.schedule;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.ExpirePoliciesUseCase;
import github.lms.lemuel.insurance.application.port.in.ExpirePoliciesUseCase.ExpiryBatchResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 매일 새벽 만기·실효소멸 판정 배치.
 *
 * <p>D7 전이 5(ACTIVE→EXPIRED: 만기 도래)·전이 3(LAPSED→EXPIRED: 부활 창구 도과)을 자동
 * 집행한다. 환수 스윕(03:30)·수수료 지급(04:00)보다 앞서 돌아야 같은 날 종결 계약의
 * 수수료 정리가 당일 반영된다.
 */
@Component
public class PolicyExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PolicyExpiryScheduler.class);

    private final ExpirePoliciesUseCase useCase;
    /** KST 기준 시각 소스 — cron 은 KST 인데 본문 now() 가 UTC 면 만기 판정이 하루 어긋난다. */
    private final Clock clock;
    /** 계약 종결은 보장 소멸 + 환수 트리거로 이어지는 금전적 사건 — 잡 단위 감사 1건. */
    private final AuditLogger auditLogger;

    public PolicyExpiryScheduler(ExpirePoliciesUseCase useCase, Clock clock, AuditLogger auditLogger) {
        this.useCase = useCase;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    /** 매일 02:00 (KST). cron zone 과 본문 {@code now(clock)} 모두 KST 로 고정한다. */
    @Scheduled(cron = "${app.insurance.batch.policy-expiry-cron:0 0 2 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "insurance-policy-expiry", lockAtMostFor = "PT30M")
    public void expireDue() {
        LocalDate today = LocalDate.now(clock);
        log.info("[PolicyExpiry] 시작: today={}", today);
        ExpiryBatchResult result = useCase.expireOn(today);
        log.info("[PolicyExpiry] 완료: 만기소멸={} 실효소멸={}",
                result.maturedExpired(), result.lapsedExpired());

        auditLogger.record(AuditAction.INSURANCE_POLICY_EXPIRY_BATCH, "PolicyExpiryJob", today.toString(),
                String.format("{\"date\":\"%s\",\"maturedExpired\":%d,\"lapsedExpired\":%d}",
                        today, result.maturedExpired(), result.lapsedExpired()));
    }
}
