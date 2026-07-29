package github.lms.lemuel.company.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import github.lms.lemuel.company.application.port.in.GetCompanyWorkforceUseCase;
import github.lms.lemuel.company.application.port.in.GetWorkforceComparisonUseCase;
import github.lms.lemuel.company.domain.ComparisonAxis;
import github.lms.lemuel.company.domain.ComparisonLevel;
import github.lms.lemuel.company.domain.ComparisonUnavailableReason;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.GroupComparison;
import github.lms.lemuel.company.domain.MetricComparison;
import github.lms.lemuel.company.domain.WorkforceComparison;
import github.lms.lemuel.company.domain.WorkforceMetric;
import github.lms.lemuel.company.domain.WorkplaceKey;
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
import java.util.NoSuchElementException;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyWorkforceControllerTest {

    @Mock
    private GetCompanyWorkforceUseCase getCompanyWorkforceUseCase;

    @Mock
    private GetWorkforceComparisonUseCase getWorkforceComparisonUseCase;

    private MockMvc mockMvc;

    private CompanyWorkforce workforce() {
        // 추정연봉 = (16,406,250 × 12) / (50 × 0.09) = 43,750,000
        return new CompanyWorkforce("주식회사에고이즘", "866759", "525101", "전자상거래 소매업",
                "서울특별시 성동구 연무장19길", YearMonth.of(2026, 6), 50, new BigDecimal("16406250"));
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyWorkforceController(getCompanyWorkforceUseCase,
                        getWorkforceComparisonUseCase))
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

    @Test
    @DisplayName("GET /detail — 복합키로 업종·지역 비교를 반환하고 금액은 소수 문자열로 직렬화한다")
    void detail() throws Exception {
        MetricComparison headcount = MetricComparison.of(WorkforceMetric.HEADCOUNT,
                new BigDecimal("50"), new BigDecimal("12.50"), new BigDecimal("91.20"));
        MetricComparison salary = MetricComparison.of(WorkforceMetric.ESTIMATED_ANNUAL_SALARY,
                new BigDecimal("43750000"), new BigDecimal("35000000"), new BigDecimal("82.50"));
        when(getWorkforceComparisonUseCase.get(WorkplaceKey.of("주식회사에고이즘", "866759", "2026-06")))
                .thenReturn(new WorkforceComparison(workforce(),
                        GroupComparison.available(ComparisonAxis.INDUSTRY, ComparisonLevel.EXACT, "525101", 12,
                                headcount, salary),
                        GroupComparison.sampleTooSmall(ComparisonAxis.REGION, ComparisonLevel.BROADENED,
                                "서울특별시", 4)));

        mockMvc.perform(get("/api/company/workforce/detail")
                        .param("name", "주식회사에고이즘")
                        .param("bizRegNoPrefix", "866759")
                        .param("snapshotMonth", "2026-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workplaceName").value("주식회사에고이즘"))
                .andExpect(jsonPath("$.industryCode").value("525101"))
                .andExpect(jsonPath("$.sido").value("서울특별시"))
                .andExpect(jsonPath("$.sigungu").value("성동구"))
                // 금액은 소수 문자열 — 부동소수 수치로 나가지 않는다.
                // ★ jsonPath().value("43750000") 는 수치 43750000 에도 통과할 수 있어(타입 강제 없음)
                //   원시 JSON 에서 따옴표까지 확인한다. 애너테이션 기반 문자열화가 Jackson 3 런타임에서
                //   무시돼 수치로 나갔던 결함(2026-07-30)을 이 어서션이 잡는다.
                .andExpect(content().string(containsString("\"estimatedAnnualSalary\":\"43750000\"")))
                .andExpect(content().string(containsString("\"salaryCapMonthlyAmount\":\"6370000\"")))
                .andExpect(content().string(containsString("\"median\":\"35000000\"")))
                .andExpect(content().string(containsString("\"difference\":\"8750000\"")))
                .andExpect(jsonPath("$.estimatedAnnualSalary").value("43750000"))
                .andExpect(jsonPath("$.salaryCapMonthlyAmount").value("6370000"))
                .andExpect(jsonPath("$.salaryCapReached").value(false))
                .andExpect(jsonPath("$.industryComparison.comparisonLevel").value("EXACT"))
                .andExpect(jsonPath("$.industryComparison.groupKey").value("525101"))
                .andExpect(jsonPath("$.industryComparison.sampleSize").value(12))
                .andExpect(jsonPath("$.industryComparison.unavailableReason").doesNotExist())
                .andExpect(jsonPath("$.industryComparison.estimatedAnnualSalary.median").value("35000000"))
                .andExpect(jsonPath("$.industryComparison.estimatedAnnualSalary.difference").value("8750000"))
                // 비율·건수는 금액이 아니므로 수치로 나간다.
                .andExpect(jsonPath("$.industryComparison.estimatedAnnualSalary.differenceRate").value(25.00))
                .andExpect(jsonPath("$.industryComparison.headcount.median").value(12.5))
                .andExpect(jsonPath("$.industryComparison.headcount.percentile").value(91.20))
                .andExpect(jsonPath("$.regionComparison.unavailableReason").value("SAMPLE_TOO_SMALL"))
                .andExpect(jsonPath("$.regionComparison.headcount").doesNotExist())
                .andExpect(jsonPath("$.note").exists());
    }

    @Test
    @DisplayName("GET /detail — 복합키가 어느 레코드와도 매칭되지 않으면 404")
    void detailNotFound() throws Exception {
        when(getWorkforceComparisonUseCase.get(any()))
                .thenThrow(new NoSuchElementException("해당 사업장 스냅샷을 찾을 수 없습니다"));

        mockMvc.perform(get("/api/company/workforce/detail")
                        .param("name", "없는회사")
                        .param("bizRegNoPrefix", "999999")
                        .param("snapshotMonth", "2026-06"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /detail — 필수 누락·형식 위반은 400 + 오류 본문")
    void detailBadRequest() throws Exception {
        mockMvc.perform(get("/api/company/workforce/detail")
                        .param("bizRegNoPrefix", "866759")
                        .param("snapshotMonth", "2026-06"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사업장명(name)은 필수입니다"));

        mockMvc.perform(get("/api/company/workforce/detail")
                        .param("name", "주식회사에고이즘")
                        .param("bizRegNoPrefix", "86675")
                        .param("snapshotMonth", "2026-06"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/company/workforce/detail")
                        .param("name", "주식회사에고이즘")
                        .param("bizRegNoPrefix", "866759")
                        .param("snapshotMonth", "202606"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /detail — 따옴표·느낌표가 든 사업장명도 URL 인코딩으로 그대로 전달된다")
    void detailHandlesQuotedWorkplaceName() throws Exception {
        String quoted = "(유)케이비에프에스\"전주밥상 다잡수소!\"";
        CompanyWorkforce workforce = new CompanyWorkforce(quoted, "418851", null,
                "한식 일반 음식점업", "전북특별자치도 전주시 덕진구 백제대로", YearMonth.of(2026, 6), 4,
                new BigDecimal("1199160"));
        when(getWorkforceComparisonUseCase.get(WorkplaceKey.of(quoted, "418851", "2026-06")))
                .thenReturn(new WorkforceComparison(workforce,
                        GroupComparison.noGroup(ComparisonAxis.INDUSTRY,
                                ComparisonUnavailableReason.INDUSTRY_CODE_MISSING),
                        GroupComparison.noGroup(ComparisonAxis.REGION,
                                ComparisonUnavailableReason.REGION_UNPARSEABLE)));

        mockMvc.perform(get("/api/company/workforce/detail")
                        .param("name", quoted)
                        .param("bizRegNoPrefix", "418851")
                        .param("snapshotMonth", "2026-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workplaceName").value(quoted))
                .andExpect(jsonPath("$.industryCode").doesNotExist())
                .andExpect(jsonPath("$.industryComparison.unavailableReason").value("INDUSTRY_CODE_MISSING"))
                .andExpect(jsonPath("$.industryComparison.comparisonLevel").doesNotExist())
                .andExpect(jsonPath("$.regionComparison.unavailableReason").value("REGION_UNPARSEABLE"));
    }
}
