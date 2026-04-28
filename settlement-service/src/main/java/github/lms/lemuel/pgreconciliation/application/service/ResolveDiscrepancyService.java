package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.pgreconciliation.application.port.in.ResolveDiscrepancyUseCase;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
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
    private final Counter approvedCounter;
    private final Counter rejectedCounter;

    public ResolveDiscrepancyService(LoadReconciliationRunPort loadPort,
                                     SaveReconciliationRunPort savePort,
                                     MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
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
        // TODO: APPROVED 이벤트 발행 → AdjustSettlementForRefundUseCase 와 유사한 보정 흐름 트리거
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
