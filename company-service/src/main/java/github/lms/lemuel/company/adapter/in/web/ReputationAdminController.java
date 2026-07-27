package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.adapter.in.web.dto.ReputationResponse;
import github.lms.lemuel.company.application.port.in.RecalcReputationUseCase;
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

/**
 * 평판 재계산 트리거 (운영자 전용 — AdminApiKeyFilter 게이팅, gateway 미라우팅).
 *
 * <p>전체 재계산은 기업 수 × 기사 수만큼 감성분석(provider=gemini 면 Gemini 외부 호출, 실패·지연 시
 * 키워드 폴백)을 순차 수행하는 배치라 202 + 백그라운드 실행으로 처리하고, 진행/결과는
 * GET /admin/company/reputation/recalc/status 로 확인한다 — 수집(/admin/company/collect)과 같은 규약이다.
 * 이로써 호출자의 HTTP 타임아웃이 배치 소요시간을 옭아매지 않는다(2026-07-19/20 배치 타임아웃 재발 방지).
 * 동시 실행은 409. 각 LLM 호출은 {@code HttpClientConfig} 의 read 타임아웃으로 개별 유계다.
 *
 * <p>단건({@code /recalc/{stockCode}})은 결과 스냅샷을 그대로 돌려주는 운영자 조회 성격이라 동기로 둔다.
 * INSERT-only 이므로 오늘자 스냅샷이 이미 있으면 건너뛴다.
 */
@RestController
@RequestMapping("/admin/company/reputation")
public class ReputationAdminController {

    private static final Logger log = LoggerFactory.getLogger(ReputationAdminController.class);

    private final RecalcReputationUseCase recalcReputationUseCase;
    private final RecalcStatusTracker tracker;
    private final TaskExecutor executor;
    private final RecordAuditPort recordAuditPort;

    public ReputationAdminController(RecalcReputationUseCase recalcReputationUseCase,
                                     RecalcStatusTracker tracker,
                                     @Qualifier("recalcTaskExecutor") TaskExecutor executor,
                                     RecordAuditPort recordAuditPort) {
        this.recalcReputationUseCase = recalcReputationUseCase;
        this.tracker = tracker;
        this.executor = executor;
        this.recordAuditPort = recordAuditPort;
    }

    @PostMapping("/recalc")
    public ResponseEntity<Map<String, String>> recalcAll() {
        recordAuditPort.record("REPUTATION_RECALC_TRIGGERED", "Reputation", "ALL", Map.of());
        if (!tracker.tryStart("all")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "이미 실행 중인 재계산이 있습니다: " + tracker.current().job()));
        }
        executor.execute(() -> {
            try {
                tracker.complete(recalcReputationUseCase.recalcAll());
            } catch (RuntimeException e) {
                log.error("평판 재계산 실패 job=all", e);
                tracker.fail(e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "message", "재계산 시작: all",
                "statusUrl", "/admin/company/reputation/recalc/status"));
    }

    @GetMapping("/recalc/status")
    public ResponseEntity<RecalcStatusTracker.Status> status() {
        return ResponseEntity.ok(tracker.current());
    }

    @PostMapping("/recalc/{stockCode}")
    public ResponseEntity<?> recalcOne(@PathVariable String stockCode) {
        recordAuditPort.record("REPUTATION_RECALC_TRIGGERED", "Reputation", stockCode, Map.of());
        return recalcReputationUseCase.recalcFor(stockCode)
                .<ResponseEntity<?>>map(score -> ResponseEntity.ok(ReputationResponse.from(score)))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "message", "스냅샷 미생성 — 기사가 없거나 오늘자 스냅샷이 이미 있습니다: " + stockCode)));
    }
}
