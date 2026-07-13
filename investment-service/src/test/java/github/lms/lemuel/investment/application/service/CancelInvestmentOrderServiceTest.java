package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import github.lms.lemuel.investment.domain.exception.InvalidInvestmentOrderStateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelInvestmentOrderServiceTest {

    @Mock LoadInvestmentOrderPort loadInvestmentOrderPort;
    @Mock SaveInvestmentOrderPort saveInvestmentOrderPort;

    private CancelInvestmentOrderService service() {
        return new CancelInvestmentOrderService(loadInvestmentOrderPort, saveInvestmentOrderPort);
    }

    private static InvestmentOrder order(InvestmentOrderStatus status) {
        return InvestmentOrder.reconstitute(5L, 7L, "005930", new BigDecimal("1000"),
                70, "A", status, LocalDateTime.now());
    }

    @Test
    void REQUESTED_주문을_취소한다() {
        when(loadInvestmentOrderPort.load(5L)).thenReturn(order(InvestmentOrderStatus.REQUESTED));
        when(saveInvestmentOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InvestmentOrder result = service().cancel(5L, 7L);

        assertThat(result.getStatus()).isEqualTo(InvestmentOrderStatus.CANCELED);
        verify(saveInvestmentOrderPort).save(any());
    }

    @Test
    void 타_셀러의_주문을_취소하려_하면_AccessDenied이고_저장하지_않는다() {
        when(loadInvestmentOrderPort.load(5L)).thenReturn(order(InvestmentOrderStatus.REQUESTED)); // 소유자 = 7

        assertThatThrownBy(() -> service().cancel(5L, 999L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(saveInvestmentOrderPort, never()).save(any());
    }

    @Test
    void 이미_집행된_주문_취소는_InvalidInvestmentOrderState이고_저장하지_않는다() {
        when(loadInvestmentOrderPort.load(5L)).thenReturn(order(InvestmentOrderStatus.EXECUTED));

        assertThatThrownBy(() -> service().cancel(5L, 7L))
                .isInstanceOf(InvalidInvestmentOrderStateException.class);
        verify(saveInvestmentOrderPort, never()).save(any());
    }
}
