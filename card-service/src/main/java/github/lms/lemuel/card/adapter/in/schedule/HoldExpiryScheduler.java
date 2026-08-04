package github.lms.lemuel.card.adapter.in.schedule;

import github.lms.lemuel.card.application.port.in.ExpireStaleHoldsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미매입 홀드 만료 배치 스케줄러 — 일 1회 새벽 4:00 KST.
 *
 * <p>VAN 네트워크가 매입 확정을 늦게 보내거나 누락한 ACTIVE 홀드가 가용한도를 영구 차감하는 것을
 * 방지한다. {@code app.card.hold.expiry-days}(기본 7일) 이상 ACTIVE 인 홀드를 EXPIRED 로 전환한다.
 *
 * <p>{@link SchedulerLock} 으로 다중 인스턴스 중복 실행을 방지한다.
 */
@Component
public class HoldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);

    private final ExpireStaleHoldsUseCase expireStaleHoldsUseCase;

    @Value("${app.card.hold.expiry-days:7}")
    private int expiryDays;

    public HoldExpiryScheduler(ExpireStaleHoldsUseCase expireStaleHoldsUseCase) {
        this.expireStaleHoldsUseCase = expireStaleHoldsUseCase;
    }

    /**
     * 실패해도 던지지 않는다(fail-open) — 배치가 죽으면 다음 실행이 보정한다.
     * {@code lockAtMostFor=PT30M}: 인스턴스가 죽어 락이 남는 것을 막는 상한.
     */
    @Scheduled(cron = "${app.card.hold.expiry-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "card-hold-expiry", lockAtMostFor = "PT30M")
    public void expireStaleHolds() {
        log.info("[HoldExpiry] 시작 (expiryDays={})", expiryDays);
        try {
            int expired = expireStaleHoldsUseCase.expireStaleHolds(expiryDays);
            log.info("[HoldExpiry] 완료: 만료 {}건", expired);
        } catch (RuntimeException e) {
            log.error("[HoldExpiry] 배치 실패(fail-open) — 다음 실행에서 재시도", e);
        }
    }
}
