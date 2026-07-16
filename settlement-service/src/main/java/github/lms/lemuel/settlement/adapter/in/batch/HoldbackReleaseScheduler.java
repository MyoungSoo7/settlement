package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 매일 새벽 보류 해제 배치.
 *
 * <p>release_date 도달한 정산의 holdback 을 일괄 해제하여 셀러가 출금 가능하게 한다.
 * 배치 시간은 정산 사이클 / 출금 처리 시간보다 앞서 둬야 같은 날 출금에 반영됨.
 */
@Component
public class HoldbackReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldbackReleaseScheduler.class);

    private final ReleaseHoldbackUseCase useCase;
    /** KST 기준 시각 소스 — cron 은 KST 인데 본문 now() 가 UTC 면 하루 늦게 해제되는 off-by-one 을 막는다. */
    private final Clock clock;
    /** 홀드백 해제 배치를 audit_logs 에 잡 요약 1건으로 남긴다 (건별이 아니라 잡 단위 — 해제는 대량 일괄). */
    private final AuditLogger auditLogger;

    public HoldbackReleaseScheduler(ReleaseHoldbackUseCase useCase, Clock clock, AuditLogger auditLogger) {
        this.useCase = useCase;
        this.clock = clock;
        this.auditLogger = auditLogger;
    }

    /**
     * 매일 03:00 (KST) 실행. cron zone 과 본문 {@code now(clock)} 모두 KST 로 고정한다 —
     * 둘이 어긋나면 release_date == 오늘인 홀드백이 하루 늦게 풀린다.
     */
    @Scheduled(cron = "${app.holdback.release-cron:0 0 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-holdback-release", lockAtMostFor = "PT30M")
    public void releaseDue() {
        LocalDate today = LocalDate.now(clock);
        log.info("[HoldbackRelease] 시작: today={}", today);
        int released = useCase.releaseAllDueOn(today);
        log.info("[HoldbackRelease] 완료: 해제 건수={}", released);

        // 홀드백 해제는 셀러 출금가능액을 늘리는 금전적 사건 — 잡 단위 감사로 "언제 몇 건 풀렸는지" 추적.
        // 배치 경로라 actor 는 system. AuditLogger 가 예외를 삼키므로 배치 흐름은 영향받지 않는다.
        auditLogger.record(AuditAction.HOLDBACK_RELEASED, "HoldbackReleaseJob", today.toString(),
                String.format("{\"date\":\"%s\",\"released\":%d}", today, released));
    }
}
