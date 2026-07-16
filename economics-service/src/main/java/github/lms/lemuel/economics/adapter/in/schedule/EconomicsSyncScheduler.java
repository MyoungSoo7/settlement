package github.lms.lemuel.economics.adapter.in.schedule;

import github.lms.lemuel.economics.adapter.in.web.SyncStatusTracker;
import github.lms.lemuel.economics.application.port.in.SyncIndicatorsUseCase;
import github.lms.lemuel.economics.application.port.in.SyncResult;
import github.lms.lemuel.economics.application.port.out.EcosClientPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * ECOS 지표 일일 자동 수집 스케줄러.
 *
 * <p>매일 {@code app.economics.sync.schedule.cron}(기본 08:00 KST) 에 전체 카탈로그를 대상으로
 * 최근 {@code lookback-days}(기본 30일) 구간을 재조회해 관측치를 upsert 한다 —
 * {@code (indicator_code, observed_date)} UNIQUE 라 SEED 값이 실 ECOS 값으로 덮어써진다.
 *
 * <ul>
 *   <li>{@code ECOS_API_KEY} 미설정이면 조용히 skip(예외 아님) — 키 없이도 서비스는 SEED 로 계속 동작.</li>
 *   <li>수동 트리거({@code POST /admin/economics/sync}) 와 {@link SyncStatusTracker} 를 공유해
 *       동시 실행을 방지한다 — 이미 실행 중이면 이번 회차는 skip.</li>
 *   <li>실패는 로그 + 트래커 FAILED 로만 남기고 삼킨다 — 한 번 실패해도 다음 스케줄에 재시도.</li>
 * </ul>
 */
@Component
public class EconomicsSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EconomicsSyncScheduler.class);

    private final SyncIndicatorsUseCase syncIndicatorsUseCase;
    private final EcosClientPort ecosClient;
    private final SyncStatusTracker tracker;
    private final int lookbackDays;
    private final Clock clock;

    public EconomicsSyncScheduler(SyncIndicatorsUseCase syncIndicatorsUseCase,
                                  EcosClientPort ecosClient,
                                  SyncStatusTracker tracker,
                                  @Value("${app.economics.sync.schedule.lookback-days:30}") int lookbackDays,
                                  Clock clock) {
        this.syncIndicatorsUseCase = syncIndicatorsUseCase;
        this.ecosClient = ecosClient;
        this.tracker = tracker;
        this.lookbackDays = lookbackDays;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.economics.sync.schedule.cron:0 0 8 * * *}",
            zone = "${app.economics.sync.schedule.zone:Asia/Seoul}")
    public void runDailySync() {
        if (!ecosClient.isConfigured()) {
            log.info("ECOS_API_KEY 미설정 — 일일 자동 동기화 skip (SEED 데이터 유지)");
            return;
        }
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(lookbackDays);
        String job = "scheduled:all:" + from + "~" + to;

        if (!tracker.tryStart(job)) {
            log.warn("동기화가 이미 실행 중이라 일일 자동 동기화 skip (진행중={})", tracker.current().job());
            return;
        }
        try {
            SyncResult result = syncIndicatorsUseCase.syncIndicators(null, from, to);
            tracker.complete(result);
            log.info("일일 ECOS 자동 동기화 완료 job={} 결과={}", job, result);
        } catch (RuntimeException e) {
            tracker.fail(e.getMessage() != null ? e.getMessage() : e.toString());
            log.error("일일 ECOS 자동 동기화 실패 job={}", job, e);
        }
    }
}
