package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.exception.InvestmentNotFoundException;
import github.lms.lemuel.investment.application.port.out.LoadFinancialStatementsPort;
import github.lms.lemuel.investment.domain.AnnualStatement;
import github.lms.lemuel.investment.domain.CompanyFinancials;
import github.lms.lemuel.investment.domain.InvestmentScore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetInvestmentScoreServiceTest {

    @Mock LoadFinancialStatementsPort loadFinancialStatementsPort;

    private GetInvestmentScoreService service() {
        return new GetInvestmentScoreService(loadFinancialStatementsPort);
    }

    private static AnnualStatement good(int year) {
        return new AnnualStatement(year,
                new BigDecimal("120"), new BigDecimal("30"), new BigDecimal("120"),
                new BigDecimal("1000"), new BigDecimal("300"), new BigDecimal("700"),
                new BigDecimal("25"), new BigDecimal("20"), new BigDecimal("40"),
                new BigDecimal("70"), new BigDecimal("18"));
    }

    @Test
    void 재무제표가_있으면_점수를_산정한다() {
        when(loadFinancialStatementsPort.load("005930")).thenReturn(Optional.of(
                new CompanyFinancials("005930", "삼성전자", "KOSPI", List.of(good(2024)))));

        InvestmentScore score = service().getScore("005930");

        assertThat(score.stockCode()).isEqualTo("005930");
        assertThat(score.companyName()).isEqualTo("삼성전자");
        assertThat(score.totalScore()).isGreaterThan(0);
        assertThat(score.grade()).isNotNull();
    }

    @Test
    void 조회결과가_없으면_NotFound() {
        when(loadFinancialStatementsPort.load("000000")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().getScore("000000"))
                .isInstanceOf(InvestmentNotFoundException.class);
    }

    @Test
    void 재무제표가_비어있으면_NotFound() {
        when(loadFinancialStatementsPort.load("000001")).thenReturn(Optional.of(
                new CompanyFinancials("000001", "빈회사", "KOSPI", List.of())));
        assertThatThrownBy(() -> service().getScore("000001"))
                .isInstanceOf(InvestmentNotFoundException.class);
    }
}
