package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.exception.NotInvestableException;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase.PlaceInvestmentOrderCommand;
import github.lms.lemuel.investment.application.port.out.InvestmentMetricsPort;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

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
    @Mock InvestmentMetricsPort investmentMetricsPort;

    // 고정 Clock — 주문 시각(createdAt) 스냅샷을 결정적으로 검증한다. KST 로 자정~09시 경계 고정.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-07-16T00:30:00Z"), KST);

    private PlaceInvestmentOrderService service() {
        return new PlaceInvestmentOrderService(getInvestmentScoreUseCase, loadFundingViewPort,
                loadInvestmentOrderPort, saveInvestmentOrderPort, investmentMetricsPort, fixedClock);
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
        when(loadFundingViewPort.sumConfirmedBySellerForUpdate(7L)).thenReturn(new BigDecimal("2000000"));
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
        verify(investmentMetricsPort).orderPlaced();
    }

    @Test
    void 주문시각은_주입된_KST_Clock_기준으로_스냅샷된다() {
        when(getInvestmentScoreUseCase.getScore("005930")).thenReturn(score(true, 82, InvestmentGrade.AA));
        when(loadFundingViewPort.sumConfirmedBySellerForUpdate(7L)).thenReturn(new BigDecimal("2000000"));
        when(loadInvestmentOrderPort.sumExecutedAmountBySeller(7L)).thenReturn(BigDecimal.ZERO);
        when(saveInvestmentOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InvestmentOrder saved = service().place(
                new PlaceInvestmentOrderCommand(7L, "005930", new BigDecimal("1000000")));

        // Instant 2026-07-16T00:30Z → KST(+9) 09:30. 도메인 내부 now() 가 아니라 주입 Clock 이 출처여야 한다.
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 16, 9, 30, 0));
    }

    @Test
    void 소수3자리_신청액은_scale2_HALF_UP로_정규화되어_저장된다() {
        when(getInvestmentScoreUseCase.getScore("005930")).thenReturn(score(true, 82, InvestmentGrade.AA));
        when(loadFundingViewPort.sumConfirmedBySellerForUpdate(7L)).thenReturn(new BigDecimal("2000000"));
        when(loadInvestmentOrderPort.sumExecutedAmountBySeller(7L)).thenReturn(BigDecimal.ZERO);
        when(saveInvestmentOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 1000.555 → HALF_UP scale2 → 1000.56. funding 판정·저장이 동일 정규화 값을 쓴다.
        service().place(new PlaceInvestmentOrderCommand(7L, "005930", new BigDecimal("1000.555")));

        ArgumentCaptor<InvestmentOrder> captor = ArgumentCaptor.forClass(InvestmentOrder.class);
        verify(saveInvestmentOrderPort).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("1000.56");
        assertThat(captor.getValue().getAmount().scale()).isEqualTo(2);
    }

    @Test
    void 부적격_종목은_NotInvestable이고_저장하지_않는다() {
        when(getInvestmentScoreUseCase.getScore("005930")).thenReturn(score(false, 45, InvestmentGrade.B));

        assertThatThrownBy(() -> service().place(
                new PlaceInvestmentOrderCommand(7L, "005930", new BigDecimal("1000000"))))
                .isInstanceOf(NotInvestableException.class);

        verify(saveInvestmentOrderPort, never()).save(any());
        verify(investmentMetricsPort).orderPlacementRejected("NOT_INVESTABLE");
        verify(investmentMetricsPort, never()).orderPlaced();
    }

    @Test
    void 재원부족이면_InsufficientFunding이고_저장하지_않는다() {
        when(getInvestmentScoreUseCase.getScore("005930")).thenReturn(score(true, 82, InvestmentGrade.AA));
        when(loadFundingViewPort.sumConfirmedBySellerForUpdate(7L)).thenReturn(new BigDecimal("1000000"));
        when(loadInvestmentOrderPort.sumExecutedAmountBySeller(7L)).thenReturn(new BigDecimal("500000"));

        // available = 100만 - 50만 = 50만, 신청 100만 → 부족. 요청액/가용액을 구조화 필드로 보존한다.
        assertThatThrownBy(() -> service().place(
                new PlaceInvestmentOrderCommand(7L, "005930", new BigDecimal("1000000"))))
                .isInstanceOfSatisfying(InsufficientFundingException.class, ex -> {
                    assertThat(ex.getRequested()).isEqualByComparingTo("1000000");
                    assertThat(ex.getAvailable()).isEqualByComparingTo("500000");
                });

        verify(saveInvestmentOrderPort, never()).save(any());
        verify(investmentMetricsPort).orderPlacementRejected("INSUFFICIENT_FUNDING");
        verify(investmentMetricsPort, never()).orderPlaced();
    }
}
