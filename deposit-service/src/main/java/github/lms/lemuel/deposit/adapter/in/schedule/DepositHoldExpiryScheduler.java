package github.lms.lemuel.deposit.adapter.in.schedule;

import github.lms.lemuel.deposit.application.port.in.ExpireDueHoldsUseCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 만료 hold 회수 배치 — 시간마다.
 *
 * <p>일 1회가 아니라 시간마다인 이유: hold 의 기본 TTL 이 72시간이고 만료가 하루 중 아무 때나
 * 흩어져 있어서, 일 1회면 만료와 회수 사이에 최대 24시간의 공백이 생긴다. 그동안 셀러의
 * 가용액은 이유 없이 줄어 있다. 회수는 계좌 하나씩 락을 잡는 가벼운 작업이라 자주 도는 비용이
 * 그 공백보다 싸다.
 *
 * <p>{@link SchedulerLock} 이 없으면 replica 수만큼 같은 hold 집합을 동시에 집어 계좌 락을
 * 서로 기다린다. {@code lockAtMostFor=PT10M} 은 인스턴스가 죽어 락이 영영 남는 것을 막는
 * 상한이며, 회수가 이보다 오래 걸리면 다른 인스턴스가 끼어들 수 있다는 뜻이기도 하다.
 *
 * <p>fail-open — 예외를 삼키지 않고 로그로 남기되 던지지는 않는다. 이 배치는 <b>다음 회차가
 * 곧 복구 수단</b>이고(회수하지 못한 hold 는 ACTIVE 로 남는다), 스케줄러가 예외로 죽으면
 * 그 복구 경로마저 사라진다.
 */
@Component
public class DepositHoldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DepositHoldExpiryScheduler.class);

    private final ExpireDueHoldsUseCase expireDueHoldsUseCase;
    private final Clock clock;
    private final Counter reclaimedCounter;
    private final Counter failedRunCounter;

    public DepositHoldExpiryScheduler(ExpireDueHoldsUseCase expireDueHoldsUseCase,
                                       Clock clock,
                                       MeterRegistry meterRegistry) {
        this.expireDueHoldsUseCase = expireDueHoldsUseCase;
        this.clock = clock;
        this.reclaimedCounter = Counter.builder("deposit.hold.expiry.reclaimed")
                .description("만료 회수로 선점이 풀린 hold 건수")
                .register(meterRegistry);
        // 회차 실패는 따로 센다 — 회수 0건은 "만료 대상이 없었다"와 "배치가 터졌다"가 같은 값이라
        // reclaimed 만 보면 구분되지 않는다.
        this.failedRunCounter = Counter.builder("deposit.hold.expiry.failed_runs")
                .description("만료 회수 배치가 예외로 끝난 회차 수")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${app.deposit.hold.expiry-cron:0 5 * * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "deposit-hold-expiry", lockAtMostFor = "PT10M")
    public void reclaimExpiredHolds() {
        LocalDateTime cutoff = LocalDateTime.now(clock);
        try {
            int reclaimed = expireDueHoldsUseCase.expireDueHolds(cutoff);
            reclaimedCounter.increment(reclaimed);
            log.info("[DepositHoldExpiry] 완료: 회수 {}건 (cutoff={})", reclaimed, cutoff);
        } catch (RuntimeException e) {
            failedRunCounter.increment();
            log.error("[DepositHoldExpiry] 배치 실패(fail-open) — 다음 회차에서 재시도", e);
        }
    }
}
