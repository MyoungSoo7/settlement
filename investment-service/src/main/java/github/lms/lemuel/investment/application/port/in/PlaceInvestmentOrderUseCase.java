package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.InvestmentOrder;

import java.math.BigDecimal;

/** 투자 주문 신청 인바운드 포트. */
public interface PlaceInvestmentOrderUseCase {

    InvestmentOrder place(PlaceInvestmentOrderCommand command);

    /**
     * @param sellerId  투자 주체(CEO 계정의 셀러 ID)
     * @param stockCode 대상 종목(6자리)
     * @param amount    투자 신청 금액(> 0)
     */
    record PlaceInvestmentOrderCommand(Long sellerId, String stockCode, BigDecimal amount) {
    }
}
