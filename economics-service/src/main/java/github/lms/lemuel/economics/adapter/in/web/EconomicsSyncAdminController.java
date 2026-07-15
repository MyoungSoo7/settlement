package github.lms.lemuel.economics.adapter.in.web;

import github.lms.lemuel.economics.application.port.in.SyncIndicatorsUseCase;
import github.lms.lemuel.economics.application.port.in.SyncResult;
import github.lms.lemuel.economics.audit.application.port.out.RecordAuditPort;
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
 * ECOS 수집 트리거 (운영자 전용 — gateway 미라우팅).
 *
 * <p>TODO(Task 10 · Chunk 4): AdminApiKeyFilter 로 {@code /admin/economics/**} 게이팅 예정.
 * 현재는 미인증 상태이며, 게이트 필터가 아직 없다.
 *
 * <p>수집은 시간이 걸리는 배치라 202 + 백그라운드 실행으로 처리하고,
 * 진행/결과는 GET /admin/economics/sync/status 로 확인한다. 동시 실행은 409.
 */
@RestController
@RequestMapping("/admin/economics/sync")
public class EconomicsSyncAdminController {

    private static final Logger log = LoggerFactory.getLogger(EconomicsSyncAdminController.class);

    private final SyncIndicatorsUseCase syncIndicatorsUseCase;
    private final SyncStatusTracker tracker;
    private final TaskExecutor executor;
    private final RecordAuditPort recordAuditPort;

    public EconomicsSyncAdminController(SyncIndicatorsUseCase syncIndicatorsUseCase,
                                        SyncStatusTracker tracker,
                                        @Qualifier("syncTaskExecutor") TaskExecutor executor,
                                        RecordAuditPort recordAuditPort) {
        this.syncIndicatorsUseCase = syncIndicatorsUseCase;
        this.tracker = tracker;
        this.executor = executor;
        this.recordAuditPort = recordAuditPort;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> sync(
            @RequestParam(required = false) String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String job = (code == null ? "all" : code) + ":" + from + "~" + to;
        recordAuditPort.record("COLLECT_TRIGGERED", "EcosSync", job,
                Map.of("code", code == null ? "all" : code, "from", from.toString(), "to", to.toString()));
        return submit(job, () -> syncIndicatorsUseCase.syncIndicators(code, from, to));
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
                "statusUrl", "/admin/economics/sync/status"));
    }
}
