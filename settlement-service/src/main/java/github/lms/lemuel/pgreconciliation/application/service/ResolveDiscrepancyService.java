package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.pgreconciliation.application.port.in.ResolveDiscrepancyUseCase;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.application.port.out.PublishDiscrepancyResolvedEventPort;
import github.lms.lemuel.pgreconciliation.application.port.out.SaveReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ResolveDiscrepancyService implements ResolveDiscrepancyUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResolveDiscrepancyService.class);

    private final LoadReconciliationRunPort loadPort;
    private final SaveReconciliationRunPort savePort;
    private final PublishDiscrepancyResolvedEventPort publishPort;
    private final Counter approvedCounter;
    private final Counter rejectedCounter;

    public ResolveDiscrepancyService(LoadReconciliationRunPort loadPort,
                                     SaveReconciliationRunPort savePort,
                                     PublishDiscrepancyResolvedEventPort publishPort,
                                     MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.publishPort = publishPort;
        this.approvedCounter = Counter.builder("pg.reconciliation.discrepancies.approved")
                .description("운영자가 승인한 PG 대사 차이 누적 수")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("pg.reconciliation.discrepancies.rejected")
                .description("운영자가 거절한 PG 대사 차이 누적 수")
                .register(meterRegistry);
    }

    @Override
    public ReconciliationDiscrepancy approve(Long discrepancyId, String operatorId, String note) {
        ReconciliationDiscrepancy d = loadPort.findDiscrepancyById(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException("Discrepancy not found: " + discrepancyId));
        d.approve(operatorId, note);
        ReconciliationDiscrepancy saved = savePort.save(d);
        approvedCounter.increment();
        log.warn("[PgRecon] APPROVED by operator. discrepancyId={}, operator={}, type={}, diff={}",
                discrepancyId, operatorId, d.getType(), d.getDifference());
        // 승인 → 같은 트랜잭션 Outbox 에 APPROVED 이벤트 적재 → 커밋 후 폴러가 Kafka 발행.
        // 후속 보정 핸들러가 type 별(과/소 정산) 부호 규칙으로 정산을 조정한다.
        // (보정 적용 핸들러는 타입별 부호 규칙 확정 후 추가 — AdjustSettlementForRefundUseCase 와 유사 흐름)
        publishPort.publishDiscrepancyApproved(saved);
        return saved;
    }

    @Override
    public ReconciliationDiscrepancy reject(Long discrepancyId, String operatorId, String reason) {
        ReconciliationDiscrepancy d = loadPort.findDiscrepancyById(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException("Discrepancy not found: " + discrepancyId));
        d.reject(operatorId, reason);
        ReconciliationDiscrepancy saved = savePort.save(d);
        rejectedCounter.increment();
        log.warn("[PgRecon] REJECTED by operator. discrepancyId={}, operator={}, reason={}",
                discrepancyId, operatorId, reason);
        return saved;
    }
}
