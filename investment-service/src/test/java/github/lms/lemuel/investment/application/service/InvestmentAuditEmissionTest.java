package github.lms.lemuel.investment.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditAspect;
import github.lms.lemuel.common.audit.application.AuditDetailSerializer;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.exception.NotInvestableException;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase.PlaceInvestmentOrderCommand;
import github.lms.lemuel.investment.application.port.out.InvestmentMetricsPort;
import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.PublishInvestmentEventPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentGrade;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import github.lms.lemuel.investment.domain.InvestmentScore;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * investment 주문 유스케이스의 {@code @Auditable} 이 AuditAspect 를 통해 audit_logs 기록을 유발하는지 검증한다.
 * Spring 컨텍스트 없이 {@link AspectJProxyFactory}(proxyTargetClass) 로 프로덕션 AOP 경로를 재현한다.
 */
class InvestmentAuditEmissionTest {

    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final InvestmentMetricsPort metrics = mock(InvestmentMetricsPort.class);

    private <T> T proxied(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new AuditAspect(auditLogger, new AuditDetailSerializer(new ObjectMapper())));
        return factory.getProxy();
    }

    private static InvestmentScore score(boolean investable) {
        return new InvestmentScore("005930", "삼성전자", "KOSPI", 2024, 82, InvestmentGrade.AA, investable,
                new InvestmentScore.Profitability(35, null, null),
                new InvestmentScore.Stability(35, null, null),
                new InvestmentScore.Growth(30, null, null));
    }

    private static InvestmentOrder order(InvestmentOrderStatus status) {
        return InvestmentOrder.reconstitute(9L, 7L, "005930", new BigDecimal("1000000"),
                82, "AA", status, LocalDateTime.now());
    }

    @Test
    void 주문_신청은_INVESTMENT_ORDER_PLACED_감사를_남긴다() {
        GetInvestmentScoreUseCase getScore = mock(GetInvestmentScoreUseCase.class);
        LoadFundingViewPort funding = mock(LoadFundingViewPort.class);
        LoadInvestmentOrderPort loadOrder = mock(LoadInvestmentOrderPort.class);
        SaveInvestmentOrderPort saveOrder = mock(SaveInvestmentOrderPort.class);
        when(getScore.getScore("005930")).thenReturn(score(true));
        when(funding.sumConfirmedBySellerForUpdate(7L)).thenReturn(new BigDecimal("2000000"));
        when(loadOrder.sumExecutedAmountBySeller(7L)).thenReturn(BigDecimal.ZERO);
        when(saveOrder.save(any())).thenReturn(order(InvestmentOrderStatus.REQUESTED));

        PlaceInvestmentOrderService service = proxied(
                new PlaceInvestmentOrderService(getScore, funding, loadOrder, saveOrder, metrics,
                        java.time.Clock.systemUTC()));
        service.place(new PlaceInvestmentOrderCommand(7L, "005930", new BigDecimal("1000000")));

        verify(auditLogger).record(eq(AuditAction.INVESTMENT_ORDER_PLACED), eq("InvestmentOrder"), eq("9"), any());
    }

    @Test
    void 부적격_신청은_INVESTMENT_ORDER_REJECTED_감사를_남긴다() {
        GetInvestmentScoreUseCase getScore = mock(GetInvestmentScoreUseCase.class);
        LoadFundingViewPort funding = mock(LoadFundingViewPort.class);
        LoadInvestmentOrderPort loadOrder = mock(LoadInvestmentOrderPort.class);
        SaveInvestmentOrderPort saveOrder = mock(SaveInvestmentOrderPort.class);
        when(getScore.getScore("005930")).thenReturn(score(false));

        PlaceInvestmentOrderService service = proxied(
                new PlaceInvestmentOrderService(getScore, funding, loadOrder, saveOrder, metrics,
                        java.time.Clock.systemUTC()));

        assertThatThrownBy(() -> service.place(
                new PlaceInvestmentOrderCommand(7L, "005930", new BigDecimal("1000000"))))
                .isInstanceOf(NotInvestableException.class);

        verify(auditLogger).record(eq(AuditAction.INVESTMENT_ORDER_REJECTED), eq("InvestmentOrder"), any(), any());
    }

    @Test
    void 주문_집행은_INVESTMENT_ORDER_EXECUTED_감사를_남긴다() {
        LoadInvestmentOrderPort loadOrder = mock(LoadInvestmentOrderPort.class);
        LoadFundingViewPort funding = mock(LoadFundingViewPort.class);
        SaveInvestmentOrderPort saveOrder = mock(SaveInvestmentOrderPort.class);
        PublishInvestmentEventPort publish = mock(PublishInvestmentEventPort.class);
        when(loadOrder.load(9L)).thenReturn(order(InvestmentOrderStatus.REQUESTED));
        when(funding.sumConfirmedBySellerForUpdate(7L)).thenReturn(new BigDecimal("2000000"));
        when(loadOrder.sumExecutedAmountBySeller(7L)).thenReturn(BigDecimal.ZERO);
        when(saveOrder.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExecuteInvestmentOrderService service = proxied(
                new ExecuteInvestmentOrderService(loadOrder, funding, saveOrder, publish, metrics));
        service.execute(9L, 7L);

        verify(auditLogger).record(eq(AuditAction.INVESTMENT_ORDER_EXECUTED), eq("InvestmentOrder"), eq("9"), any());
    }

    @Test
    void 집행_재원부족은_INVESTMENT_ORDER_REJECTED_감사를_남긴다() {
        LoadInvestmentOrderPort loadOrder = mock(LoadInvestmentOrderPort.class);
        LoadFundingViewPort funding = mock(LoadFundingViewPort.class);
        SaveInvestmentOrderPort saveOrder = mock(SaveInvestmentOrderPort.class);
        PublishInvestmentEventPort publish = mock(PublishInvestmentEventPort.class);
        when(loadOrder.load(9L)).thenReturn(order(InvestmentOrderStatus.REQUESTED));
        when(funding.sumConfirmedBySellerForUpdate(7L)).thenReturn(new BigDecimal("1000000"));
        when(loadOrder.sumExecutedAmountBySeller(7L)).thenReturn(new BigDecimal("500000"));
        when(saveOrder.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExecuteInvestmentOrderService service = proxied(
                new ExecuteInvestmentOrderService(loadOrder, funding, saveOrder, publish, metrics));

        assertThatThrownBy(() -> service.execute(9L, 7L)).isInstanceOf(InsufficientFundingException.class);

        verify(auditLogger).record(eq(AuditAction.INVESTMENT_ORDER_REJECTED), eq("InvestmentOrder"), eq("9"), any());
    }

    @Test
    void 주문_취소는_INVESTMENT_ORDER_CANCELED_감사를_남긴다() {
        LoadInvestmentOrderPort loadOrder = mock(LoadInvestmentOrderPort.class);
        SaveInvestmentOrderPort saveOrder = mock(SaveInvestmentOrderPort.class);
        when(loadOrder.load(9L)).thenReturn(order(InvestmentOrderStatus.REQUESTED));
        when(saveOrder.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelInvestmentOrderService service = proxied(
                new CancelInvestmentOrderService(loadOrder, saveOrder));
        service.cancel(9L, 7L);

        verify(auditLogger).record(eq(AuditAction.INVESTMENT_ORDER_CANCELED), eq("InvestmentOrder"), eq("9"), any());
    }
}
