package github.lms.lemuel.financial.adapter.in.web;

import github.lms.lemuel.financial.application.port.in.SyncCompaniesUseCase;
import github.lms.lemuel.financial.application.port.in.SyncResult;
import github.lms.lemuel.financial.application.port.in.SyncStatementsUseCase;
import github.lms.lemuel.financial.audit.application.port.out.RecordAuditPort;
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
 * DART 수집 트리거 (운영자 전용 — AdminApiKeyFilter 게이팅, gateway 미라우팅).
 *
 * <p>수집은 수 분 걸리는 배치라 202 + 백그라운드 실행으로 처리하고,
 * 진행/결과는 GET /admin/financial/sync/status 로 확인한다. 동시 실행은 409.
 */
@RestController
@RequestMapping("/admin/financial/sync")
public class FinancialSyncAdminController {

    private static final Logger log = LoggerFactory.getLogger(FinancialSyncAdminController.class);

    private final SyncCompaniesUseCase syncCompaniesUseCase;
    private final SyncStatementsUseCase syncStatementsUseCase;
    private final SyncStatusTracker tracker;
    private final TaskExecutor executor;
    private final RecordAuditPort recordAuditPort;

    public FinancialSyncAdminController(SyncCompaniesUseCase syncCompaniesUseCase,
                                        SyncStatementsUseCase syncStatementsUseCase,
                                        SyncStatusTracker tracker,
                                        @Qualifier("syncTaskExecutor") TaskExecutor executor,
                                        RecordAuditPort recordAuditPort) {
        this.syncCompaniesUseCase = syncCompaniesUseCase;
        this.syncStatementsUseCase = syncStatementsUseCase;
        this.tracker = tracker;
        this.executor = executor;
        this.recordAuditPort = recordAuditPort;
    }

    @PostMapping("/companies")
    public ResponseEntity<Map<String, String>> syncCompanies() {
        recordAuditPort.record("COLLECT_TRIGGERED", "DartSync", "companies",
                Map.of("job", "companies", "source", "DART"));
        return submit("companies", syncCompaniesUseCase::syncCompanies);
    }

    @PostMapping("/statements/{year}")
    public ResponseEntity<Map<String, String>> syncStatements(@PathVariable int year) {
        recordAuditPort.record("COLLECT_TRIGGERED", "DartSync", "statements-" + year,
                Map.of("job", "statements", "year", year, "source", "DART"));
        return submit("statements-" + year, () -> syncStatementsUseCase.syncStatements(year));
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
            } catch (RuntimeException e) {
                log.error("동기화 실패 job={}", job, e);
                tracker.fail(e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "message", "동기화 시작: " + job,
                "statusUrl", "/admin/financial/sync/status"));
    }
}
