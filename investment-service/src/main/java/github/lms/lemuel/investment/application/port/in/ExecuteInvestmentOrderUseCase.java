package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.InvestmentOrder;

/** 투자 주문 집행(승인→집행 + 이벤트 발행) 인바운드 포트. */
public interface ExecuteInvestmentOrderUseCase {

    InvestmentOrder execute(long orderId);
}
