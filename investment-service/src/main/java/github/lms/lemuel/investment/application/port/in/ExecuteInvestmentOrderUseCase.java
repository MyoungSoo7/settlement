package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.InvestmentOrder;

/** 투자 주문 집행(승인→집행 + 이벤트 발행) 인바운드 포트. */
public interface ExecuteInvestmentOrderUseCase {

    /**
     * @param orderId        집행할 주문 ID
     * @param callerSellerId 요청 주체(JWT)의 셀러 ID — 주문 소유자와 다르면 권한 없음(403)
     */
    InvestmentOrder execute(long orderId, long callerSellerId);
}
