package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.SellerFunding;

/** 셀러의 투자 가용 재원 조회 인바운드 포트. */
public interface GetFundingUseCase {

    SellerFunding getFunding(long sellerId);
}
