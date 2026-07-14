package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase.CorporateCreditView;
import github.lms.lemuel.loan.application.port.out.LoadCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateFinancialPort;
import github.lms.lemuel.loan.domain.CompanyReputation;
import github.lms.lemuel.loan.domain.CorporateCreditPolicy;
import github.lms.lemuel.loan.domain.CorporateFinancials;
import github.lms.lemuel.loan.domain.exception.CorporateLoanNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluateCorporateCreditServiceTest {

    @Mock LoadCorporateFinancialPort loadCorporateFinancialPort;
    @Mock LoadCompanyReputationPort loadCompanyReputationPort;

    private final CorporateCreditPolicy policy =
            new CorporateCreditPolicy(new BigDecimal("0.0002"), new BigDecimal("0.10"));

    private EvaluateCorporateCreditService service() {
        return new EvaluateCorporateCreditService(loadCorporateFinancialPort, loadCompanyReputationPort, policy);
    }

    private CorporateFinancials samsung() {
        // 부채비율 90(안정성40) + opMargin 20(20) + roa 10(20) + 평판B(15) = 95 → A
        return new CorporateFinancials("005930", "삼성전자", "KOSPI", 2024,
                new BigDecimal("90"), new BigDecimal("20"), new BigDecimal("10"),
                new BigDecimal("1000000"), new BigDecimal("50000"));
    }

    @Test
    void 재무와_평판을_반영해_점수_등급_한도를_반환한다() {
        when(loadCorporateFinancialPort.loadLatest("005930")).thenReturn(Optional.of(samsung()));
        when(loadCompanyReputationPort.findByStockCode("005930")).thenReturn(
                Optional.of(CompanyReputation.of("005930", 78, "B", "A", LocalDate.of(2026, 7, 1))));

        CorporateCreditView view = service().evaluate("005930");

        assertThat(view.stockCode()).isEqualTo("005930");
        assertThat(view.corpName()).isEqualTo("삼성전자");
        assertThat(view.market()).isEqualTo("KOSPI");
        assertThat(view.fiscalYear()).isEqualTo(2024);
        assertThat(view.creditScore()).isEqualTo(95);
        assertThat(view.creditGrade()).isEqualTo("A");
        // 한도 = 1,000,000 × 0.10 × 1.0(A) = 100,000
        assertThat(view.limit()).isEqualByComparingTo("100000.00");
        assertThat(view.reputationGrade()).isEqualTo("B");
    }

    @Test
    void 평판미상이면_중립10점으로_평가한다() {
        when(loadCorporateFinancialPort.loadLatest("005930")).thenReturn(Optional.of(samsung()));
        when(loadCompanyReputationPort.findByStockCode("005930")).thenReturn(Optional.empty());

        CorporateCreditView view = service().evaluate("005930");

        // 안정성40 + 수익성40 + 평판중립10 = 90 → A
        assertThat(view.creditScore()).isEqualTo(90);
        assertThat(view.creditGrade()).isEqualTo("A");
        assertThat(view.reputationGrade()).isNull();
    }

    @Test
    void 재무자료가_없으면_NotFound_예외() {
        when(loadCorporateFinancialPort.loadLatest("000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().evaluate("000000"))
                .isInstanceOf(CorporateLoanNotFoundException.class);
    }
}
