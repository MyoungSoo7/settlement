package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.port.out.InvestmentMetricsPort;
import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.PublishInvestmentEventPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecuteInvestmentOrderServiceTest {

    @Mock LoadInvestmentOrderPort loadInvestmentOrderPort;
    @Mock LoadFundingViewPort loadFundingViewPort;
    @Mock SaveInvestmentOrderPort saveInvestmentOrderPort;
    @Mock PublishInvestmentEventPort publishInvestmentEventPort;
    @Mock InvestmentMetricsPort investmentMetricsPort;

    private ExecuteInvestmentOrderService service() {
        return new ExecuteInvestmentOrderService(loadInvestmentOrderPort, loadFundingViewPort,
                saveInvestmentOrderPort, publishInvestmentEventPort, investmentMetricsPort);
    }

    private static InvestmentOrder requested() {
        return InvestmentOrder.reconstitute(5L, 7L, "005930", new BigDecimal("1000000"),
                82, "AA", InvestmentOrderStatus.REQUESTED, java.time.LocalDateTime.now());
    }

    @Test
    void 재원충분하면_승인집행하고_이벤트를_발행한다() {
        when(loadInvestmentOrderPort.load(5L)).thenReturn(requested());
        when(loadFundingViewPort.sumConfirmedBySellerForUpdate(7L)).thenReturn(new BigDecimal("2000000"));
        when(loadInvestmentOrderPort.sumExecutedAmountBySeller(7L)).thenReturn(new BigDecimal("500000"));
        when(saveInvestmentOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InvestmentOrder result = service().execute(5L, 7L);

        assertThat(result.getStatus()).isEqualTo(InvestmentOrderStatus.EXECUTED);
        verify(publishInvestmentEventPort).publishExecuted(any());
        verify(investmentMetricsPort).orderExecuted(new BigDecimal("1000000"));
    }

    @Test
    void 타_셀러의_주문을_집행하려_하면_AccessDenied이고_상태변경도_없다() {
        when(loadInvestmentOrderPort.load(5L)).thenReturn(requested()); // 주문 소유자 = 7

        assertThatThrownBy(() -> service().execute(5L, 999L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(saveInvestmentOrderPort, never()).save(any());
        verify(publishInvestmentEventPort, never()).publishExecuted(any());
        verify(investmentMetricsPort, never()).orderExecuted(any());
    }

    @Test
    void 집행시점_재원부족이면_주문을_REJECTED로_저장하고_예외이며_이벤트를_발행하지_않는다() {
        when(loadInvestmentOrderPort.load(5L)).thenReturn(requested());
        when(loadFundingViewPort.sumConfirmedBySellerForUpdate(7L)).thenReturn(new BigDecimal("1000000"));
        when(loadInvestmentOrderPort.sumExecutedAmountBySeller(7L)).thenReturn(new BigDecimal("500000"));
        when(saveInvestmentOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // available = 100만 - 50만 = 50만, 주문 100만 → 부족
        assertThatThrownBy(() -> service().execute(5L, 7L))
                .isInstanceOf(InsufficientFundingException.class);

        ArgumentCaptor<InvestmentOrder> captor = ArgumentCaptor.forClass(InvestmentOrder.class);
        verify(saveInvestmentOrderPort).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(InvestmentOrderStatus.REJECTED);
        verify(publishInvestmentEventPort, never()).publishExecuted(any());
        verify(investmentMetricsPort).orderExecutionRejectedInsufficientFunding();
        verify(investmentMetricsPort, never()).orderExecuted(any());
    }
}
