package github.lms.lemuel.company.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import github.lms.lemuel.company.application.port.in.GetCompanyWorkforceUseCase;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyWorkforceControllerTest {

    @Mock
    private GetCompanyWorkforceUseCase getCompanyWorkforceUseCase;

    private MockMvc mockMvc;

    private CompanyWorkforce workforce() {
        // 추정연봉 = (16,406,250 × 12) / (50 × 0.09) = 43,750,000
        return new CompanyWorkforce("주식회사에고이즘", "866759", "전자상거래 소매업", "서울특별시 성동구",
                YearMonth.of(2026, 6), 50, new BigDecimal("16406250"));
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyWorkforceController(getCompanyWorkforceUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET /api/company/workforce — 검색 페이지 응답 + 추정연봉 계산")
    void search() throws Exception {
        when(getCompanyWorkforceUseCase.search(eq("에고이즘"), eq(0), eq(20)))
                .thenReturn(new GetCompanyWorkforceUseCase.WorkforcePage(List.of(workforce()), 0, 20, 1));

        mockMvc.perform(get("/api/company/workforce").param("name", "에고이즘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].workplaceName").value("주식회사에고이즘"))
                .andExpect(jsonPath("$.content[0].headcount").value(50))
                .andExpect(jsonPath("$.content[0].estimatedAnnualSalary").value(43750000))
                .andExpect(jsonPath("$.content[0].snapshotMonth").value("2026-06"))
                .andExpect(jsonPath("$.content[0].note").exists())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/company/workforce — name 미지정 시 전체 위임")
    void searchWithoutName() throws Exception {
        when(getCompanyWorkforceUseCase.search(isNull(), eq(0), eq(20)))
                .thenReturn(new GetCompanyWorkforceUseCase.WorkforcePage(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/company/workforce"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
