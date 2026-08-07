package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.MonitorBancaConcentrationUseCase.BancaMonitorResult;
import github.lms.lemuel.insurance.application.port.out.LoadBancaSalesPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BancaRuleViolation;
import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BankInsurerPremium;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 방카 25%룰 모니터링 배치 서비스 테스트 — 연도 경계 집계 + 위반 이벤트 발행.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BancaConcentrationMonitorService — 방카 25%룰 배치")
class BancaConcentrationMonitorServiceTest {

    @Mock LoadBancaSalesPort loadBancaSalesPort;
    @Mock PublishInsuranceEventPort publishPort;

    private BancaConcentrationMonitorService service() {
        return new BancaConcentrationMonitorService(loadBancaSalesPort, publishPort);
    }

    @Test
    @DisplayName("당해연도 [1/1, 익년 1/1) 반개구간으로 집계를 요청한다")
    void aggregatesOverCalendarYear() {
        when(loadBancaSalesPort.aggregateBancaPremiums(any(), any())).thenReturn(List.of());

        service().checkYear(Year.of(2026));

        verify(loadBancaSalesPort).aggregateBancaPremiums(
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    }

    @Test
    @DisplayName("위반 1건당 banca_rule_violated 이벤트를 발행한다")
    void publishesEventPerViolation() {
        when(loadBancaSalesPort.aggregateBancaPremiums(any(), any())).thenReturn(List.of(
                new BankInsurerPremium("BANK-KB", "INS-A", new BigDecimal("900000.00")),
                new BankInsurerPremium("BANK-KB", "INS-B", new BigDecimal("100000.00"))));

        BancaMonitorResult result = service().checkYear(Year.of(2026));

        assertThat(result.aggregatedPairs()).isEqualTo(2);
        assertThat(result.violations()).hasSize(1);

        ArgumentCaptor<BancaRuleViolation> violation = ArgumentCaptor.forClass(BancaRuleViolation.class);
        verify(publishPort).publishBancaRuleViolated(eq(Year.of(2026)), violation.capture());
        assertThat(violation.getValue().insurerCode()).isEqualTo("INS-A");
        assertThat(violation.getValue().share()).isEqualByComparingTo(new BigDecimal("0.9000"));
    }

    @Test
    @DisplayName("위반이 없으면 이벤트도 없다")
    void publishesNothingWhenCompliant() {
        when(loadBancaSalesPort.aggregateBancaPremiums(any(), any())).thenReturn(List.of(
                new BankInsurerPremium("BANK-KB", "INS-A", new BigDecimal("250000.00")),
                new BankInsurerPremium("BANK-KB", "INS-B", new BigDecimal("250000.00")),
                new BankInsurerPremium("BANK-KB", "INS-C", new BigDecimal("250000.00")),
                new BankInsurerPremium("BANK-KB", "INS-D", new BigDecimal("250000.00"))));

        BancaMonitorResult result = service().checkYear(Year.of(2026));

        assertThat(result.violations()).isEmpty();
        verify(publishPort, never()).publishBancaRuleViolated(any(), any());
    }
}
