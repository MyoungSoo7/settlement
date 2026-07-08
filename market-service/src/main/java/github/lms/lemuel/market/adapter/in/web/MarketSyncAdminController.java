package github.lms.lemuel.market.adapter.in.web;

import github.lms.lemuel.market.application.port.in.SyncQuotesUseCase;
import github.lms.lemuel.market.application.port.in.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;

/**
 * KRX 시세 수집 트리거 (운영자 전용 — gateway 미라우팅, AdminApiKeyFilter 게이팅).
 *
 * <p>수집은 하루치 전 종목을 도는 배치라 202 + 백그라운드 실행으로 처리하고,
 * 진행/결과는 GET /admin/market/sync/status 로 확인한다. 동시 실행은 409.
 */
@RestController
@RequestMapping("/admin/market/sync")
public class MarketSyncAdminController {

    private static final Logger log = LoggerFactory.getLogger(MarketSyncAdminController.class);

    private final SyncQuotesUseCase syncQuotesUseCase;
    private final SyncStatusTracker tracker;
    private final TaskExecutor executor;

    public MarketSyncAdminController(SyncQuotesUseCase syncQuotesUseCase,
                                     SyncStatusTracker tracker,
                                     @Qualifier("syncTaskExecutor") TaskExecutor executor) {
        this.syncQuotesUseCase = syncQuotesUseCase;
        this.tracker = tracker;
        this.executor = executor;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> sync(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate) {
        String job = "quotes:" + baseDate;
        return submit(job, () -> syncQuotesUseCase.syncQuotes(baseDate));
    }

    @GetMapping("/status")
    public ResponseEntity<SyncStatusTracker.Status> status() {
        return ResponseEntity.ok(tracker.current());
    }

    private ResponseEntity<Map<String, String>> submit(String job, Supplier<SyncResult> task) {
        if (!tracker.tryStart(job)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "이미 실행 중인 동기화가 있습니다: " + tracker.current().job()));
        }
        executor.execute(() -> {
            try {
                tracker.complete(task.get());
            } catch (Throwable t) {
                // 항상 트래커를 해소한다 — 안 그러면 상태가 영구 RUNNING 으로 막혀 이후 요청이 전부 409.
                log.error("동기화 실패 job={}", job, t);
                tracker.fail(t.getMessage() != null ? t.getMessage() : t.toString());
                if (t instanceof Error error) {
                    throw error;   // Error(OOM 등)는 기록·해소 후 rethrow — 삼키지 않는다.
                }
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "message", "동기화 시작: " + job,
                "statusUrl", "/admin/market/sync/status"));
    }
}
