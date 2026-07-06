package github.lms.lemuel.financial.application.service;

import github.lms.lemuel.financial.application.port.out.LoadCompanyPort;
import github.lms.lemuel.financial.application.port.out.LoadFinancialStatementPort;
import github.lms.lemuel.financial.domain.Company;
import github.lms.lemuel.financial.domain.FinancialStatement;
import github.lms.lemuel.financial.domain.FsDivision;
import github.lms.lemuel.financial.domain.StatementSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialStatementQueryServiceTest {

    @Mock
    private LoadCompanyPort loadCompanyPort;
    @Mock
    private LoadFinancialStatementPort loadFinancialStatementPort;

    @InjectMocks
    private FinancialStatementQueryService service;

    private static final Company SAMSUNG = new Company("005930", null, "삼성전자", "KOSPI");

    @Test
    @DisplayName("기업이 존재하면 연도 구간 재무제표를 반환")
    void byCompany() {
        when(loadCompanyPort.findByStockCode("005930")).thenReturn(Optional.of(SAMSUNG));
        when(loadFinancialStatementPort.findByCompany("005930", 2022, 2024)).thenReturn(List.of(
                new FinancialStatement(1L, "005930", 2024, FsDivision.CFS, "KRW",
                        null, null, null, null, null, null, StatementSource.SEED, null)));

        List<FinancialStatement> result = service.byCompany("005930", 2022, 2024);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("없는 기업이면 NoSuchElementException → 404")
    void unknownCompany() {
        when(loadCompanyPort.findByStockCode("999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.byCompany("999999", null, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("fromYear > toYear 면 IllegalArgumentException → 400")
    void invalidYearRange() {
        when(loadCompanyPort.findByStockCode("005930")).thenReturn(Optional.of(SAMSUNG));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.byCompany("005930", 2025, 2022));
    }
}
