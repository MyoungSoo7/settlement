package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.port.in.ExecuteInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.out.InvestmentMetricsPort;
import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.PublishInvestmentEventPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.SellerFunding;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

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
    private final InvestmentMetricsPort investmentMetricsPort;

    public ExecuteInvestmentOrderService(LoadInvestmentOrderPort loadInvestmentOrderPort,
                                         LoadFundingViewPort loadFundingViewPort,
                                         SaveInvestmentOrderPort saveInvestmentOrderPort,
                                         PublishInvestmentEventPort publishInvestmentEventPort,
                                         InvestmentMetricsPort investmentMetricsPort) {
        this.loadInvestmentOrderPort = loadInvestmentOrderPort;
        this.loadFundingViewPort = loadFundingViewPort;
        this.saveInvestmentOrderPort = saveInvestmentOrderPort;
        this.publishInvestmentEventPort = publishInvestmentEventPort;
        this.investmentMetricsPort = investmentMetricsPort;
    }

    @Override
    // 재원부족 거절(REJECTED 저장)은 예외와 함께 커밋돼야 한다 — 기본 롤백이면 거절 확정이 증발해
    // 주문이 REQUESTED 로 남는다(동시성 IT 로 고정). 다른 예외는 기본 롤백 유지.
    @Transactional(noRollbackFor = InsufficientFundingException.class)
    @Auditable(
            action = AuditAction.INVESTMENT_ORDER_EXECUTED,
            failureAction = "INVESTMENT_ORDER_REJECTED",
            resourceType = "InvestmentOrder",
            resourceId = "#p0 == null ? null : #p0.toString()",
            detail = "{'orderId': #p0, 'callerSellerId': #p1, 'status': #result == null ? null : #result.getStatus().name()}"
    )
    public InvestmentOrder execute(long orderId, long callerSellerId) {
        InvestmentOrder order = loadInvestmentOrderPort.load(orderId);

        // 소유권 검증 — 타 셀러의 주문을 집행/거절시킬 수 없다(재원 검증보다 먼저).
        if (!Objects.equals(order.getSellerId(), callerSellerId)) {
            throw new AccessDeniedException("본인 소유가 아닌 투자 주문입니다. orderId=" + orderId);
        }

        // 집행 시점 재원 재검증 — 집행 완료(EXECUTED) 합만 재원에서 차감하므로 아직 미집행인 이 주문은 제외된다.
        // ★ 재원 행을 FOR UPDATE 로 잡아 같은 셀러 동시 집행 2건을 직렬화한다(write-skew 방지). 둘째 집행은
        //   첫 커밋까지 블로킹 후 EXECUTED 합을 최신값으로 재조회해 초과 집행을 정확히 거절한다.
        BigDecimal confirmed = loadFundingViewPort.sumConfirmedBySellerForUpdate(order.getSellerId());
        BigDecimal invested = loadInvestmentOrderPort.sumExecutedAmountBySeller(order.getSellerId());
        SellerFunding funding = SellerFunding.of(order.getSellerId(), confirmed, invested);
        if (!funding.covers(order.getAmount())) {
            order.reject();
            saveInvestmentOrderPort.save(order);
            investmentMetricsPort.orderExecutionRejectedInsufficientFunding();
            throw new InsufficientFundingException(
                    "집행 시점 가용 재원이 부족합니다. available=" + funding.available() + ", requested=" + order.getAmount(),
                    order.getAmount(), funding.available());
        }

        order.approve();
        order.execute();
        InvestmentOrder saved = saveInvestmentOrderPort.save(order);
        publishInvestmentEventPort.publishExecuted(saved);
        investmentMetricsPort.orderExecuted(saved.getAmount());
        return saved;
    }
}
