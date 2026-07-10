package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.exception.NotInvestableException;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase.PlaceInvestmentOrderCommand;
import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentGrade;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import github.lms.lemuel.investment.domain.InvestmentScore;
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
class PlaceInvestmentOrderServiceTest {

    @Mock GetInvestmentScoreUseCase getInvestmentScoreUseCase;
    @Mock LoadFundingViewPort loadFundingViewPort;
    @Mock LoadInvestmentOrderPort loadInvestmentOrderPort;
    @Mock SaveInvestmentOrderPort saveInvestmentOrderPort;

    private PlaceInvestmentOrderService service() {
        return new PlaceInvestmentOrderService(getInvestmentScoreUseCase, loadFundingViewPort,
                loadInvestmentOrderPort, saveInvestmentOrderPort);
    }

    private static InvestmentScore score(boolean investable, int total, InvestmentGrade grade) {
        return new InvestmentScore("005930", "삼성전자", "KOSPI", 2024, total, grade, investable,
                new InvestmentScore.Profitability(35, null, null),
                new InvestmentScore.Stability(35, null, null),
                new InvestmentScore.Growth(30, null, null));
    }

    @Test
    void 적격이고_재원충분하면_REQUESTED로_저장한다() {
        when(getInvestmentScoreUseCase.getScore("005930")).thenReturn(score(true, 82, InvestmentGrade.AA));
        when(loadFundingViewPort.sumConfirmedBySeller(7L)).thenReturn(new BigDecimal("2000000"));
        when(loadInvestmentOrderPort.sumExecutedAmountBySeller(7L)).thenReturn(new BigDecimal("500000"));
        when(saveInvestmentOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // available = 200만 - 50만 = 150만, 신청 100만 → 통과
        service().place(new PlaceInvestmentOrderCommand(7L, "005930", new BigDecimal("1000000")));

        ArgumentCaptor<InvestmentOrder> captor = ArgumentCaptor.forClass(InvestmentOrder.class);
        verify(saveInvestmentOrderPort).save(captor.capture());
        InvestmentOrder saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(InvestmentOrderStatus.REQUESTED);
        assertThat(saved.getScoreAtOrder()).isEqualTo(82);
        assertThat(saved.getGradeAtOrder()).isEqualTo("AA");
        assertThat(saved.getStockCode()).isEqualTo("005930");
    }

    @Test
    void 부적격_종목은_NotInvestable이고_저장하지_않는다() {
        when(getInvestmentScoreUseCase.getScore("005930")).thenReturn(score(false, 45, InvestmentGrade.B));

        assertThatThrownBy(() -> service().place(
                new PlaceInvestmentOrderCommand(7L, "005930", new BigDecimal("1000000"))))
                .isInstanceOf(NotInvestableException.class);

        verify(saveInvestmentOrderPort, never()).save(any());
    }

    @Test
    void 재원부족이면_InsufficientFunding이고_저장하지_않는다() {
        when(getInvestmentScoreUseCase.getScore("005930")).thenReturn(score(true, 82, InvestmentGrade.AA));
        when(loadFundingViewPort.sumConfirmedBySeller(7L)).thenReturn(new BigDecimal("1000000"));
        when(loadInvestmentOrderPort.sumExecutedAmountBySeller(7L)).thenReturn(new BigDecimal("500000"));

        // available = 100만 - 50만 = 50만, 신청 100만 → 부족
        assertThatThrownBy(() -> service().place(
                new PlaceInvestmentOrderCommand(7L, "005930", new BigDecimal("1000000"))))
                .isInstanceOf(InsufficientFundingException.class);

        verify(saveInvestmentOrderPort, never()).save(any());
    }
}
