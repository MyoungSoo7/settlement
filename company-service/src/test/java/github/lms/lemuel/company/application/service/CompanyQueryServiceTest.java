package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetCompaniesUseCase;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.domain.Company;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyQueryServiceTest {

    private final LoadCompanyPort loadCompanyPort = mock(LoadCompanyPort.class);
    private final CompanyQueryService service = new CompanyQueryService(loadCompanyPort);

    @Test
    @DisplayName("음수 페이지·과대 사이즈는 안전 범위로 보정한다")
    void clampsPaging() {
        when(loadCompanyPort.search(isNull(), anyInt(), anyInt()))
                .thenReturn(new LoadCompanyPort.SearchResult(List.of(), 0));

        GetCompaniesUseCase.CompanyPage result = service.search(null, -5, 999);

        assertEquals(0, result.page());
        assertEquals(100, result.size());
        verify(loadCompanyPort).search(null, 0, 100);
    }

    @Test
    @DisplayName("공백 키워드는 null 로 정규화하고 totalPages 를 계산한다")
    void normalizesKeywordAndComputesTotalPages() {
        Company company = new Company("005930", null, "삼성전자", null);
        when(loadCompanyPort.search(eq("삼성"), anyInt(), anyInt()))
                .thenReturn(new LoadCompanyPort.SearchResult(List.of(company), 41));

        GetCompaniesUseCase.CompanyPage result = service.search("  삼성  ", 0, 20);

        assertEquals(41, result.totalElements());
        assertEquals(3, result.totalPages());
        assertEquals(List.of(company), result.content());
    }
}
