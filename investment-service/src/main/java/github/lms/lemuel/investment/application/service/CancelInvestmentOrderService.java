package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.investment.application.port.in.CancelInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 투자 주문 취소: REQUESTED/APPROVED 주문을 CANCELED 로 확정한다.
 * 이미 집행(EXECUTED)/거절(REJECTED)/취소된 주문 취소는 도메인 불변식 위반(InvalidInvestmentOrderStateException → 400).
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
    @Auditable(
            action = AuditAction.INVESTMENT_ORDER_CANCELED,
            resourceType = "InvestmentOrder",
            resourceId = "#p0 == null ? null : #p0.toString()",
            detail = "{'orderId': #p0, 'callerSellerId': #p1, 'status': #result == null ? null : #result.getStatus().name()}"
    )
    public InvestmentOrder cancel(long orderId, long callerSellerId) {
        InvestmentOrder order = loadInvestmentOrderPort.load(orderId);
        if (!Objects.equals(order.getSellerId(), callerSellerId)) {
            throw new AccessDeniedException("본인 소유가 아닌 투자 주문입니다. orderId=" + orderId);
        }
        order.cancel();
        return saveInvestmentOrderPort.save(order);
    }
}
