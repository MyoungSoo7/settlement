package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.SellerFundingView;

/** 재원 프로젝션 저장 아웃바운드 포트. */
public interface SaveFundingViewPort {

    /** settlementId 가 식별자이므로 재수신 시 멱등 UPSERT. */
    void upsert(SellerFundingView view);
}
