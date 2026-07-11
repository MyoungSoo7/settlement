package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.InvestmentOrder;

/** 투자 주문 저장 아웃바운드 포트. */
public interface SaveInvestmentOrderPort {

    InvestmentOrder save(InvestmentOrder order);
}
