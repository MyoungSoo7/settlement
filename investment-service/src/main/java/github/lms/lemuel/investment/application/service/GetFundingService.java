package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.GetFundingUseCase;
import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.domain.SellerFunding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 셀러의 투자 가용 재원 조회: 확정 정산금 합계 − 집행 완료 투자 합 = 가용액.
 */
@Service
public class GetFundingService implements GetFundingUseCase {

    private final LoadFundingViewPort loadFundingViewPort;
    private final LoadInvestmentOrderPort loadInvestmentOrderPort;

    public GetFundingService(LoadFundingViewPort loadFundingViewPort,
                             LoadInvestmentOrderPort loadInvestmentOrderPort) {
        this.loadFundingViewPort = loadFundingViewPort;
        this.loadInvestmentOrderPort = loadInvestmentOrderPort;
    }

    @Override
    @Transactional(readOnly = true)
    public SellerFunding getFunding(long sellerId) {
        BigDecimal confirmed = loadFundingViewPort.sumConfirmedBySeller(sellerId);
        BigDecimal invested = loadInvestmentOrderPort.sumExecutedAmountBySeller(sellerId);
        return SellerFunding.of(sellerId, confirmed, invested);
    }
}
