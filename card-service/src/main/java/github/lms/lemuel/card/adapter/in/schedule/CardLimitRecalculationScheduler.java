package github.lms.lemuel.card.adapter.in.schedule;

import github.lms.lemuel.card.application.port.in.RecalculateCardLimitsUseCase;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일 1회 마스터 한도 재산정 트리거 — 새벽 3:30 KST.
 *
 * <p>3시가 아니라 3시 30분인 이유는 정산 확정 배치(settlement-service, 3:00 KST)가 먼저 끝나야
 * 하기 때문이다. 겹치면 확정 도중의 재원으로 한도를 산정해 매일 조금씩 다른 답이 나온다.
 *
 * <p>{@code zone} 을 KST 로 고정한다 — UTC JVM 에서 zone 을 빼면 실행 시각이 9시간 어긋나
 * 정산 확정 전에 돌아버린다.
 *
 * <p>{@link SchedulerLock} 이 없으면 replica 수만큼 배치가 동시에 돌고, 각자 락을 잡으려 경합하다
 * 같은 계정에 이벤트를 중복 발행한다. {@code lockAtMostFor=PT30M} 은 인스턴스가 죽어 락이 영영
 * 남는 것을 막는 상한이며, 실행이 이보다 길어지면 다음 인스턴스가 끼어들 수 있다는 뜻이기도 하다 —
 * 계정 수가 늘면 함께 올려야 한다.
 */
@Component
public class CardLimitRecalculationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CardLimitRecalculationScheduler.class);

    private final RecalculateCardLimitsUseCase recalculateCardLimitsUseCase;
    private final AuditLogger auditLogger;

    public CardLimitRecalculationScheduler(RecalculateCardLimitsUseCase recalculateCardLimitsUseCase,
                                           AuditLogger auditLogger) {
        this.recalculateCardLimitsUseCase = recalculateCardLimitsUseCase;
        this.auditLogger = auditLogger;
    }

    /**
     * 실패해도 던지지 않는다(fail-open) — 스케줄러가 예외로 죽으면 다음 실행까지 조용하고, 무엇보다
     * 이 배치는 <b>매일 다시 도는 것</b>이 복구 수단이다. 단, 감사 기록은 남긴다: 재산정이
     * 돌지 않은 날이 로그에만 있고 audit 에 없으면 사후에 "그날 왜 한도가 그대로였나"를 답할 수 없다.
     */
    @Scheduled(cron = "${app.card.limit.recalculation-cron:0 30 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "card-limit-recalculation", lockAtMostFor = "PT30M")
    public void recalculate() {
        log.info("[CardLimitRecalc] 시작");
        try {
            int changed = recalculateCardLimitsUseCase.recalculateAll();
            log.info("[CardLimitRecalc] 완료: 변경 {}건", changed);
            auditLogger.record(AuditAction.CARD_LIMIT_CHANGED, "CardLimitRecalcJob", "ALL",
                    String.format("{\"changed\":%d}", changed));
        } catch (RuntimeException e) {
            log.error("[CardLimitRecalc] 배치 실패(fail-open) — 다음 실행에서 재시도", e);
            auditLogger.record(AuditAction.CARD_LIMIT_CHANGED, "CardLimitRecalcJob", "ALL",
                    "{\"changed\":0,\"failed\":true}");
        }
    }
}
