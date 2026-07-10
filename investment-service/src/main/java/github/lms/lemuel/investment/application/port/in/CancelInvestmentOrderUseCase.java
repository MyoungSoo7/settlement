package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.InvestmentOrder;

/** 투자 주문 취소 인바운드 포트. */
public interface CancelInvestmentOrderUseCase {

    InvestmentOrder cancel(long orderId);
}
