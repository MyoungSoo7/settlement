package github.lms.lemuel.financial.application.service;

import github.lms.lemuel.financial.application.port.in.GetCompaniesUseCase;
import github.lms.lemuel.financial.application.port.out.LoadCompanyPort;
import github.lms.lemuel.financial.domain.Company;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyQueryServiceTest {

    @Mock
    private LoadCompanyPort loadCompanyPort;

    @InjectMocks
    private CompanyQueryService service;

    @Test
    @DisplayName("검색 — 페이지/사이즈 방어(음수→0, 상한 100), 빈 키워드는 null 정규화")
    void searchNormalizesArguments() {
        when(loadCompanyPort.search(isNull(), eq(0), eq(100)))
                .thenReturn(new LoadCompanyPort.SearchResult(
                        List.of(new Company("005930", null, "삼성전자", "KOSPI")), 801));

        GetCompaniesUseCase.CompanyPage result = service.search("  ", -1, 5000);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(100);
        assertThat(result.totalElements()).isEqualTo(801);
        assertThat(result.totalPages()).isEqualTo(9);
        assertThat(result.content()).hasSize(1);
    }

    @Test
    @DisplayName("검색 — 키워드는 strip 후 그대로 전달")
    void searchPassesKeyword() {
        when(loadCompanyPort.search(eq("삼성"), eq(0), eq(20)))
                .thenReturn(new LoadCompanyPort.SearchResult(List.of(), 0));

        GetCompaniesUseCase.CompanyPage result = service.search(" 삼성 ", 0, 20);

        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }
}
