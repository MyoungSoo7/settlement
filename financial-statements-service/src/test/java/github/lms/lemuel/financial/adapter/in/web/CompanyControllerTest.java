package github.lms.lemuel.financial.adapter.in.web;

import github.lms.lemuel.financial.application.port.in.GetCompaniesUseCase;
import github.lms.lemuel.financial.application.port.in.GetFinancialStatementsUseCase;
import github.lms.lemuel.financial.domain.Company;
import github.lms.lemuel.financial.domain.FinancialStatement;
import github.lms.lemuel.financial.domain.FsDivision;
import github.lms.lemuel.financial.domain.StatementSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyControllerTest {

    @Mock
    private GetCompaniesUseCase getCompaniesUseCase;
    @Mock
    private GetFinancialStatementsUseCase getFinancialStatementsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyController(getCompaniesUseCase, getFinancialStatementsUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/financial/companies — 페이지 응답")
    void search() throws Exception {
        when(getCompaniesUseCase.search(isNull(), anyInt(), anyInt()))
                .thenReturn(new GetCompaniesUseCase.CompanyPage(
                        List.of(new Company("005930", "00126380", "삼성전자", "KOSPI")), 0, 20, 1));

        mockMvc.perform(get("/api/financial/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].stockCode").value("005930"))
                .andExpect(jsonPath("$.content[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/financial/companies/{stockCode} — 미존재 시 404 + message")
    void notFound() throws Exception {
        when(getCompaniesUseCase.byStockCode("999999")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/financial/companies/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /statements — 파생지표 포함, 계산 불가 지표는 null")
    void statements() throws Exception {
        when(getFinancialStatementsUseCase.byCompany(any(), any(), any())).thenReturn(List.of(
                new FinancialStatement(1L, "005930", 2024, FsDivision.CFS, "KRW",
                        new BigDecimal("1000"), new BigDecimal("150"), new BigDecimal("100"),
                        new BigDecimal("2000"), new BigDecimal("800"), new BigDecimal("1200"),
                        StatementSource.SEED, null)));

        mockMvc.perform(get("/api/financial/companies/005930/statements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fiscalYear").value(2024))
                .andExpect(jsonPath("$[0].operatingMargin").value(15.00))
                .andExpect(jsonPath("$[0].debtRatio").value(66.67))
                .andExpect(jsonPath("$[0].source").value("SEED"));
    }
}
