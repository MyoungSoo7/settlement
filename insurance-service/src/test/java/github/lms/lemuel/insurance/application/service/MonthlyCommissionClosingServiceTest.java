package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.CloseMonthlyCommissionUseCase.MonthlyClosingResult;
import github.lms.lemuel.insurance.application.port.out.CommissionClosingPort;
import github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort;
import github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort.FcPaidSummary;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.domain.CommissionClosing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 월 수수료 마감 배치 서비스 테스트 — 멱등 스킵 + 스냅샷·이벤트 발행이 핵심.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonthlyCommissionClosingService — 월 수수료 마감 배치")
class MonthlyCommissionClosingServiceTest {

    private static final YearMonth JULY = YearMonth.of(2026, 7);

    @Mock LoadCommissionSchedulePort loadSchedulePort;
    @Mock CommissionClosingPort closingPort;
    @Mock PublishInsuranceEventPort publishPort;

    private MonthlyCommissionClosingService service() {
        return new MonthlyCommissionClosingService(loadSchedulePort, closingPort, publishPort);
    }

    @Test
    @DisplayName("FC별 당월 지급 합계를 마감 스냅샷으로 저장하고 FC 단위 이벤트를 발행한다")
    void closesEachFcAndPublishes() {
        when(loadSchedulePort.summarizePaidByFcInMonth(JULY)).thenReturn(List.of(
                new FcPaidSummary("fc-100", new BigDecimal("99999.96"), 12),
                new FcPaidSummary("fc-200", new BigDecimal("8333.33"), 1)));
        when(closingPort.existsByFcAndMonth(any(), any())).thenReturn(false);

        MonthlyClosingResult result = service().closeMonth(JULY);

        assertThat(result.closedFcCount()).isEqualTo(2);
        assertThat(result.skippedFcCount()).isZero();

        ArgumentCaptor<CommissionClosing> saved = ArgumentCaptor.forClass(CommissionClosing.class);
        verify(closingPort, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(CommissionClosing::getFcId)
                .containsExactly("fc-100", "fc-200");
        assertThat(saved.getAllValues().get(0).getTotalPaidAmount())
                .isEqualByComparingTo(new BigDecimal("99999.96"));
        assertThat(saved.getAllValues().get(0).getClosingMonth()).isEqualTo(JULY);

        verify(publishPort, org.mockito.Mockito.times(2))
                .publishCommissionMonthlyClosed(any(CommissionClosing.class));
    }

    @Test
    @DisplayName("이미 마감된 FC 는 스킵한다 — 배치 재실행 멱등")
    void skipsAlreadyClosedFc() {
        when(loadSchedulePort.summarizePaidByFcInMonth(JULY)).thenReturn(List.of(
                new FcPaidSummary("fc-100", new BigDecimal("100.00"), 1),
                new FcPaidSummary("fc-200", new BigDecimal("200.00"), 2)));
        when(closingPort.existsByFcAndMonth("fc-100", JULY)).thenReturn(true);
        when(closingPort.existsByFcAndMonth("fc-200", JULY)).thenReturn(false);

        MonthlyClosingResult result = service().closeMonth(JULY);

        assertThat(result.closedFcCount()).isEqualTo(1);
        assertThat(result.skippedFcCount()).isEqualTo(1);

        ArgumentCaptor<CommissionClosing> saved = ArgumentCaptor.forClass(CommissionClosing.class);
        verify(closingPort).save(saved.capture());
        assertThat(saved.getValue().getFcId()).isEqualTo("fc-200");
    }

    @Test
    @DisplayName("지급 실적이 없는 달은 저장·발행 없이 0 건 결과")
    void doesNothingWhenNoPayments() {
        when(loadSchedulePort.summarizePaidByFcInMonth(JULY)).thenReturn(List.of());

        MonthlyClosingResult result = service().closeMonth(JULY);

        assertThat(result.closedFcCount()).isZero();
        verify(closingPort, never()).save(any());
        verify(publishPort, never()).publishCommissionMonthlyClosed(any());
    }
}
