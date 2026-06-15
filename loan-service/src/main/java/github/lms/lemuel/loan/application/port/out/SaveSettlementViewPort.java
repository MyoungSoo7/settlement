package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.SellerSettlementView;

/**
 * 로컬 정산 뷰 영속화 아웃바운드 포트. settlementId 기준 멱등 UPSERT.
 */
public interface SaveSettlementViewPort {
    void upsert(SellerSettlementView view);
}
