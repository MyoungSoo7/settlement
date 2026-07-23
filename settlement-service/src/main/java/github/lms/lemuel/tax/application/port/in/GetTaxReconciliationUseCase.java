package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.domain.TaxReconciliation;

/**
 * 세무 3자 대사 조회 유스케이스 — 세무 계산 ↔ 세금계산서 ↔ 세무 원장 전표 정합 검증(Seed B2 핵심).
 */
public interface GetTaxReconciliationUseCase {

    TaxReconciliation reconcile(Long settlementId, Long sellerId);
}
