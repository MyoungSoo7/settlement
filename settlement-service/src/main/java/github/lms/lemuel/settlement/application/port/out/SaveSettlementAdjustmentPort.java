package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.SettlementAdjustment;

/**
 * 역정산 감사 레코드 저장 Outbound Port
 */
public interface SaveSettlementAdjustmentPort {

    SettlementAdjustment save(SettlementAdjustment adjustment);

    /**
     * 해당 PG 대사 차이(discrepancy)로 이미 조정 레코드가 존재하는지 여부.
     * 멱등 2단 방어(processed_events 다음의 belt-and-suspenders) — 재전송 시 이중 clawback 차단.
     */
    boolean existsByReconciliationDiscrepancyId(Long discrepancyId);
}
