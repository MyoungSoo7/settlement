package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.InvestmentOrder;

/** 투자 주문 취소 인바운드 포트. */
public interface CancelInvestmentOrderUseCase {

    /**
     * @param orderId        취소할 주문 ID
     * @param callerSellerId 요청 주체(JWT)의 셀러 ID — 주문 소유자와 다르면 권한 없음(403)
     */
    InvestmentOrder cancel(long orderId, long callerSellerId);
}
