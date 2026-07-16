package github.lms.lemuel.market.adapter.in.schedule;

import github.lms.lemuel.market.adapter.in.web.SyncStatusTracker;
import github.lms.lemuel.market.application.port.in.SyncQuotesUseCase;
import github.lms.lemuel.market.application.port.in.SyncResult;
import github.lms.lemuel.market.application.port.out.KrxClientPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * KRX 시세 일일 자동 수집 스케줄러.
 *
 * <p>매일 {@code app.market.sync.schedule.cron}(기본 07:30 KST)에 최근 {@code lookback-days}(기본 3)
 * 거래일을 재조회해 시세를 upsert 한다. data.go.kr 금융위 API 는 T+1 지연이 있어 "오늘"은 대개
 * 미공시라, 어제부터 며칠을 되짚어 주말/공휴일/지연 갭을 흡수한다(거래일 아니면 0건 upsert).
 *
 * <ul>
 *   <li>{@code KRX_API_KEY} 미설정이면 조용히 skip(예외 아님).</li>
 *   <li>수동 트리거({@code POST /admin/market/sync})와 {@link SyncStatusTracker}를 공유해
 *       동시 실행을 방지한다 — 이미 실행 중이면 이번 회차 skip.</li>
 *   <li>개별 날짜 실패는 로그로만 남기고 다음 날짜를 계속 진행한다.</li>
 * </ul>
 */
@Component
public class MarketSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketSyncScheduler.class);

    private final SyncQuotesUseCase syncQuotesUseCase;
    private final KrxClientPort krxClient;
    private final SyncStatusTracker tracker;
    private final int lookbackDays;
    private final Clock clock;

    public MarketSyncScheduler(SyncQuotesUseCase syncQuotesUseCase,
                               KrxClientPort krxClient,
                               SyncStatusTracker tracker,
                               @Value("${app.market.sync.schedule.lookback-days:3}") int lookbackDays,
                               Clock clock) {
        this.syncQuotesUseCase = syncQuotesUseCase;
        this.krxClient = krxClient;
        this.tracker = tracker;
        this.lookbackDays = lookbackDays;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.market.sync.schedule.cron:0 30 7 * * *}",
            zone = "${app.market.sync.schedule.zone:Asia/Seoul}")
    public void runDailySync() {
        if (!krxClient.isConfigured()) {
            log.info("KRX_API_KEY 미설정 — 일일 자동 시세 수집 skip");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        for (int i = 1; i <= lookbackDays; i++) {
            if (!syncOne(today.minusDays(i))) {
                return;   // 동시 실행 중이면 이번 회차 전체 중단
            }
        }
    }

    /** @return false 면 다른 동기화가 실행 중이라 중단해야 함 */
    private boolean syncOne(LocalDate baseDate) {
        String job = "scheduled:quotes:" + baseDate;
        if (!tracker.tryStart(job)) {
            log.warn("동기화가 이미 실행 중이라 일일 자동 수집 skip (진행중={})", tracker.current().job());
            return false;
        }
        try {
            SyncResult result = syncQuotesUseCase.syncQuotes(baseDate);
            tracker.complete(result);
            log.info("일일 KRX 자동 수집 완료 job={} 결과={}", job, result);
        } catch (RuntimeException e) {
            tracker.fail(e.getMessage() != null ? e.getMessage() : e.toString());
            log.error("일일 KRX 자동 수집 실패 job={}", job, e);
        }
        return true;
    }
}
