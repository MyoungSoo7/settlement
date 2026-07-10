package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.InvestmentOrder;

import java.math.BigDecimal;
import java.util.List;

/** 투자 주문 조회 아웃바운드 포트. */
public interface LoadInvestmentOrderPort {

    InvestmentOrder load(long orderId);

    List<InvestmentOrder> findBySeller(long sellerId);

    /** 셀러의 집행 완료(EXECUTED) 투자 금액 합계(재원 차감 계산용). */
    BigDecimal sumExecutedAmountBySeller(long sellerId);
}
