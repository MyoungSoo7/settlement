package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.InvestmentOrder;

/** 투자 도메인 이벤트 발행 아웃바운드 포트(Outbox 백엔드). */
public interface PublishInvestmentEventPort {

    /** 집행 완료 이벤트(InvestmentExecuted) 발행 → 토픽 lemuel.investment.executed. */
    void publishExecuted(InvestmentOrder order);
}
