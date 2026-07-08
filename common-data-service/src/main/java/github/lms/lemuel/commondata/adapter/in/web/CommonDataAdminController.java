package github.lms.lemuel.commondata.adapter.in.web;

import github.lms.lemuel.commondata.adapter.in.web.DataSourceController.SourceResponse;
import github.lms.lemuel.commondata.application.port.in.RegisterDataSourceUseCase;
import github.lms.lemuel.commondata.application.port.in.RegisterDataSourceUseCase.RegisterCommand;
import github.lms.lemuel.commondata.application.port.in.SyncDataSourceUseCase;
import github.lms.lemuel.commondata.application.port.in.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 데이터소스 등록/수집 트리거 (운영자 전용 — gateway 미라우팅, AdminApiKeyFilter 게이팅).
 *
 * <p>수집은 전 페이지를 도는 배치라 202 + 백그라운드 실행으로 처리하고,
 * 진행/결과는 GET /admin/commondata/sync/status 로 확인한다. 동시 실행은 409.
 * 수집 트리거의 쿼리 파라미터는 소스 defaultParams 위에 override 로 전달된다
 * (예: {@code POST .../kasi-rest-days/sync?solYear=2027}).
 */
@RestController
@RequestMapping("/admin/commondata")
public class CommonDataAdminController {

    private static final Logger log = LoggerFactory.getLogger(CommonDataAdminController.class);

    private final RegisterDataSourceUseCase registerDataSourceUseCase;
    private final SyncDataSourceUseCase syncDataSourceUseCase;
    private final SyncStatusTracker tracker;
    private final TaskExecutor executor;

    public CommonDataAdminController(RegisterDataSourceUseCase registerDataSourceUseCase,
                                     SyncDataSourceUseCase syncDataSourceUseCase,
                                     SyncStatusTracker tracker,
                                     @Qualifier("syncTaskExecutor") TaskExecutor executor) {
        this.registerDataSourceUseCase = registerDataSourceUseCase;
        this.syncDataSourceUseCase = syncDataSourceUseCase;
        this.tracker = tracker;
        this.executor = executor;
    }

    @PostMapping("/sources")
    public ResponseEntity<SourceResponse> register(@RequestBody RegisterRequest request) {
        var saved = registerDataSourceUseCase.register(new RegisterCommand(
                request.code(), request.name(), request.endpoint(), request.defaultParams(),
                request.keyFields(), request.pageSize(), request.enabled(), request.description()));
        return ResponseEntity.ok(SourceResponse.from(saved));
    }

    @PostMapping("/sources/{code}/sync")
    public ResponseEntity<Map<String, String>> sync(@PathVariable String code,
                                                    @RequestParam Map<String, String> overrideParams) {
        return submit("sync:" + code, () -> syncDataSourceUseCase.sync(code, overrideParams));
    }

    @GetMapping("/sync/status")
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
                "statusUrl", "/admin/commondata/sync/status"));
    }

    record RegisterRequest(String code, String name, String endpoint,
                           Map<String, String> defaultParams, List<String> keyFields,
                           Integer pageSize, Boolean enabled, String description) { }
}
