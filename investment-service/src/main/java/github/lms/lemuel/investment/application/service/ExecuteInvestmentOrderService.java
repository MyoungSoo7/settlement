package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.port.in.ExecuteInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.PublishInvestmentEventPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 투자 주문 집행: 재원을 재검증(신청 이후 재원이 줄었을 수 있으므로)한 뒤 승인→집행하고
 * Outbox 로 {@code InvestmentExecuted} 이벤트를 발행한다. 재원이 부족하면 주문을 거절(REJECTED)로
 * 확정하고 {@link InsufficientFundingException}(→422)을 던진다.
 */
@Service
public class ExecuteInvestmentOrderService implements ExecuteInvestmentOrderUseCase {

    private final LoadInvestmentOrderPort loadInvestmentOrderPort;
    private final LoadFundingViewPort loadFundingViewPort;
    private final SaveInvestmentOrderPort saveInvestmentOrderPort;
    private final PublishInvestmentEventPort publishInvestmentEventPort;

    public ExecuteInvestmentOrderService(LoadInvestmentOrderPort loadInvestmentOrderPort,
                                         LoadFundingViewPort loadFundingViewPort,
                                         SaveInvestmentOrderPort saveInvestmentOrderPort,
                                         PublishInvestmentEventPort publishInvestmentEventPort) {
        this.loadInvestmentOrderPort = loadInvestmentOrderPort;
        this.loadFundingViewPort = loadFundingViewPort;
        this.saveInvestmentOrderPort = saveInvestmentOrderPort;
        this.publishInvestmentEventPort = publishInvestmentEventPort;
    }

    @Override
    @Transactional
    public InvestmentOrder execute(long orderId) {
        InvestmentOrder order = loadInvestmentOrderPort.load(orderId);

        // 집행 시점 재원 재검증 — 집행 완료(EXECUTED) 합만 재원에서 차감하므로 아직 미집행인 이 주문은 제외된다.
        BigDecimal confirmed = loadFundingViewPort.sumConfirmedBySeller(order.getSellerId());
        BigDecimal invested = loadInvestmentOrderPort.sumExecutedAmountBySeller(order.getSellerId());
        BigDecimal available = confirmed.subtract(invested);
        if (available.compareTo(order.getAmount()) < 0) {
            order.reject();
            saveInvestmentOrderPort.save(order);
            throw new InsufficientFundingException(
                    "집행 시점 가용 재원이 부족합니다. available=" + available + ", requested=" + order.getAmount());
        }

        order.approve();
        order.execute();
        InvestmentOrder saved = saveInvestmentOrderPort.save(order);
        publishInvestmentEventPort.publishExecuted(saved);
        return saved;
    }
}
