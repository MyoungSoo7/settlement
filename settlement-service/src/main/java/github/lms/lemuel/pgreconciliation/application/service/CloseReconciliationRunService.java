package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.pgreconciliation.application.port.in.CloseReconciliationRunUseCase;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.application.port.out.SaveReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대사 마감 유스케이스 — 확정된 기간을 잠근다.
 *
 * <p>마감 가능 여부(COMPLETED 상태, 미결 불일치 0)는 전부 {@link ReconciliationRun#close} 가
 * 판정한다. 이 서비스는 조회·저장·감사 기록만 담당한다 — 규칙이 도메인 밖으로 새면 다른 호출
 * 경로가 생겼을 때 그대로 우회된다.
 *
 * <p>거부되면 저장도 감사 기록도 하지 않는다. 실패한 마감을 감사로그에 남기면 "마감했다"는
 * 기록만 보고 실제로는 안 잠긴 기간을 안전하다고 오인하게 된다.
 */
@Service
@Transactional
public class CloseReconciliationRunService implements CloseReconciliationRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(CloseReconciliationRunService.class);

    private final LoadReconciliationRunPort loadPort;
    private final SaveReconciliationRunPort savePort;
    private final AuditLogger auditLogger;
    private final MeterRegistry meterRegistry;

    public CloseReconciliationRunService(LoadReconciliationRunPort loadPort,
                                         SaveReconciliationRunPort savePort,
                                         AuditLogger auditLogger,
                                         MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.auditLogger = auditLogger;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ReconciliationRun close(Long runId, String operatorId, String note) {
        ReconciliationRun run = loadPort.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("ReconciliationRun not found: " + runId));

        // 도메인이 사전조건(COMPLETED · 미결 0)을 판정한다. 위반이면 여기서 예외가 나가고
        // 아래 저장·감사·메트릭에는 도달하지 않는다.
        run.close(operatorId, note);

        ReconciliationRun saved = savePort.saveAll(run);
        meterRegistry.counter("pg.reconciliation.runs.closed", "provider", run.getPgProvider()).increment();

        log.warn("[PgRecon] CLOSED by operator. runId={}, provider={}, date={}, operator={}",
                runId, run.getPgProvider(), run.getTargetDate(), operatorId);

        auditLogger.record(AuditAction.PG_RECONCILIATION_CLOSED, "PgReconciliationRun",
                String.valueOf(runId),
                String.format("{\"provider\":\"%s\",\"targetDate\":\"%s\",\"operator\":\"%s\",\"matched\":%d,\"autoCorrected\":%d}",
                        run.getPgProvider(), run.getTargetDate(), operatorId,
                        run.getMatchedCount(), run.getAutoCorrectedCount()));

        return saved;
    }
}
