package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.CancelInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 투자 주문 취소: REQUESTED/APPROVED 주문을 CANCELED 로 확정한다.
 * 이미 집행(EXECUTED)/거절(REJECTED)/취소된 주문 취소는 도메인 불변식 위반({@link IllegalStateException} → 400).
 */
@Service
public class CancelInvestmentOrderService implements CancelInvestmentOrderUseCase {

    private final LoadInvestmentOrderPort loadInvestmentOrderPort;
    private final SaveInvestmentOrderPort saveInvestmentOrderPort;

    public CancelInvestmentOrderService(LoadInvestmentOrderPort loadInvestmentOrderPort,
                                        SaveInvestmentOrderPort saveInvestmentOrderPort) {
        this.loadInvestmentOrderPort = loadInvestmentOrderPort;
        this.saveInvestmentOrderPort = saveInvestmentOrderPort;
    }

    @Override
    @Transactional
    public InvestmentOrder cancel(long orderId) {
        InvestmentOrder order = loadInvestmentOrderPort.load(orderId);
        order.cancel();
        return saveInvestmentOrderPort.save(order);
    }
}
