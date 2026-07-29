package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetCompanyWorkforceUseCase;
import github.lms.lemuel.company.application.port.out.LoadCompanyWorkforcePort;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyWorkforceQueryServiceTest {

    private final LoadCompanyWorkforcePort loadCompanyWorkforcePort = mock(LoadCompanyWorkforcePort.class);
    private final CompanyWorkforceQueryService service = new CompanyWorkforceQueryService(loadCompanyWorkforcePort);

    @Test
    @DisplayName("음수 페이지·과대 사이즈는 안전 범위로 보정한다")
    void clampsPaging() {
        when(loadCompanyWorkforcePort.search(eq("에고이즘"), anyInt(), anyInt()))
                .thenReturn(new LoadCompanyWorkforcePort.SearchResult(List.of(), 0));

        GetCompanyWorkforceUseCase.WorkforcePage result = service.search("에고이즘", -5, 999);

        assertEquals(0, result.page());
        assertEquals(100, result.size());
        verify(loadCompanyWorkforcePort).search("에고이즘", 0, 100);
    }

    @Test
    @DisplayName("공백 검색어는 null 로 정규화하고 totalPages 를 계산한다")
    void normalizesKeywordAndComputesTotalPages() {
        CompanyWorkforce workforce = new CompanyWorkforce("주식회사에고이즘", "866759", "525101",
                "전자상거래 소매업", "서울특별시 성동구", YearMonth.of(2026, 6), 50, new BigDecimal("21875000"));
        when(loadCompanyWorkforcePort.search(eq(null), anyInt(), anyInt()))
                .thenReturn(new LoadCompanyWorkforcePort.SearchResult(List.of(workforce), 41));

        GetCompanyWorkforceUseCase.WorkforcePage result = service.search("  ", 0, 20);

        assertEquals(41, result.totalElements());
        assertEquals(3, result.totalPages());
        assertEquals(List.of(workforce), result.content());
    }
}
