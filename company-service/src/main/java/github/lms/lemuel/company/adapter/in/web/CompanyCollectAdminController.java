package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.CollectArticlesUseCase;
import github.lms.lemuel.company.application.port.in.CollectResult;
import github.lms.lemuel.company.audit.application.port.out.RecordAuditPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 뉴스 수집 트리거 (운영자 전용 — AdminApiKeyFilter 게이팅, gateway 미라우팅).
 *
 * <p>전체 수집은 기업 수 × 호출 간격만큼 걸리는 배치라 202 + 백그라운드 실행으로 처리하고,
 * 진행/결과는 GET /admin/company/collect/status 로 확인한다. 동시 실행은 409.
 */
@RestController
@RequestMapping("/admin/company/collect")
public class CompanyCollectAdminController {

    private static final Logger log = LoggerFactory.getLogger(CompanyCollectAdminController.class);

    private final CollectArticlesUseCase collectArticlesUseCase;
    private final CollectStatusTracker tracker;
    private final TaskExecutor executor;
    private final RecordAuditPort recordAuditPort;

    public CompanyCollectAdminController(CollectArticlesUseCase collectArticlesUseCase,
                                         CollectStatusTracker tracker,
                                         @Qualifier("collectTaskExecutor") TaskExecutor executor,
                                         RecordAuditPort recordAuditPort) {
        this.collectArticlesUseCase = collectArticlesUseCase;
        this.tracker = tracker;
        this.executor = executor;
        this.recordAuditPort = recordAuditPort;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> collectAll() {
        recordAuditPort.record("COLLECT_TRIGGERED", "NewsArticle", "ALL", Map.of("scope", "all"));
        return submit("all", collectArticlesUseCase::collectAll);
    }

    @PostMapping("/{stockCode}")
    public ResponseEntity<Map<String, String>> collectOne(@PathVariable String stockCode) {
        recordAuditPort.record("COLLECT_TRIGGERED", "NewsArticle", stockCode, Map.of("scope", "one"));
        return submit(stockCode, () -> collectArticlesUseCase.collectFor(stockCode));
    }

    @GetMapping("/status")
    public ResponseEntity<CollectStatusTracker.Status> status() {
        return ResponseEntity.ok(tracker.current());
    }

    private ResponseEntity<Map<String, String>> submit(String job, Supplier<CollectResult> task) {
        if (!tracker.tryStart(job)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "이미 실행 중인 수집이 있습니다: " + tracker.current().job()));
        }
        executor.execute(() -> {
            try {
                tracker.complete(task.get());
            } catch (RuntimeException e) {
                log.error("수집 실패 job={}", job, e);
                tracker.fail(e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "message", "수집 시작: " + job,
                "statusUrl", "/admin/company/collect/status"));
    }
}
