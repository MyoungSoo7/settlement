package github.lms.lemuel.pgreconciliation.application.port.in;

import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;

public interface ResolveDiscrepancyUseCase {

    /**
     * 운영자가 PENDING 차이를 승인 — 후속 SettlementAdjustment(역정산) 생성 트리거가 됨.
     */
    ReconciliationDiscrepancy approve(Long discrepancyId, String operatorId, String note);

    /**
     * 운영자가 PENDING 차이를 무시 결정 (설명 필수, 감사 추적용).
     */
    ReconciliationDiscrepancy reject(Long discrepancyId, String operatorId, String reason);
}
