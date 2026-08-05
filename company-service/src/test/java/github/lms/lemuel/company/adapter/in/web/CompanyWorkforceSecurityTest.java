package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.GetCompanyWorkforceUseCase;
import github.lms.lemuel.company.application.port.in.GetWorkforceComparisonUseCase;
import github.lms.lemuel.company.application.port.in.GetWorkforceHistoryUseCase;
import github.lms.lemuel.company.config.AdminApiKeyFilter;
import github.lms.lemuel.company.config.SecurityConfig;
import github.lms.lemuel.company.domain.ComparisonAxis;
import github.lms.lemuel.company.domain.ComparisonUnavailableReason;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.GroupComparison;
import github.lms.lemuel.company.domain.WorkforceComparison;
import github.lms.lemuel.company.domain.WorkforceHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Workforce 행 단위 조회가 내부 ADMIN/MANAGER JWT 호출로만 가능한지 검증한다. */
@WebMvcTest(controllers = CompanyWorkforceController.class)
@Import({SecurityConfig.class, AdminApiKeyFilter.class})
class CompanyWorkforceSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetCompanyWorkforceUseCase getCompanyWorkforceUseCase;

    @MockitoBean
    private GetWorkforceComparisonUseCase getWorkforceComparisonUseCase;

    @MockitoBean
    private GetWorkforceHistoryUseCase getWorkforceHistoryUseCase;

    @BeforeEach
    void setUpSuccessfulResponses() {
        stubSuccessfulResponses();
    }

    private static Stream<Arguments> workforceRoutes() {
        return Stream.of(
                Arguments.of("목록", "/api/company/workforce"),
                Arguments.of("상세", "/api/company/workforce/detail"),
                Arguments.of("이력", "/api/company/workforce/history")
        );
    }

    private static Stream<Arguments> workforceRoutesAndInternalRoles() {
        return workforceRoutes().flatMap(route -> {
            Object[] values = route.get();
            return Stream.of(
                    Arguments.of(values[0], values[1], "ADMIN"),
                    Arguments.of(values[0], values[1], "MANAGER")
            );
        });
    }

    @ParameterizedTest(name = "{0} — 미인증이면 401")
    @MethodSource("workforceRoutes")
    void unauthenticatedRequestIsUnauthorized(String ignored, String path) throws Exception {
        mockMvc.perform(request(path))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedFutureWorkforceDescendantIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/company/workforce/export"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} — USER 권한이면 403")
    @MethodSource("workforceRoutes")
    void userRoleIsForbidden(String ignored, String path) throws Exception {
        mockMvc.perform(request(path).with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} — {2} 권한이면 200")
    @MethodSource("workforceRoutesAndInternalRoles")
    void internalRoleIsAllowed(String ignored, String path, String role) throws Exception {
        mockMvc.perform(request(path).with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder request(String path) {
        MockHttpServletRequestBuilder request = get(path);
        if (path.endsWith("/detail")) {
            request.param("snapshotMonth", "2026-06");
        }
        if (!path.equals("/api/company/workforce")) {
            request.param("name", "테스트사업장")
                    .param("bizRegNoPrefix", "123456");
        }
        return request;
    }

    private void stubSuccessfulResponses() {
        CompanyWorkforce workforce = new CompanyWorkforce(
                "테스트사업장", "123456", "620100", "소프트웨어 개발업",
                "서울특별시 중구 세종대로", YearMonth.of(2026, 6), 10, new BigDecimal("1000000"));
        when(getCompanyWorkforceUseCase.search(isNull(), anyInt(), anyInt()))
                .thenReturn(new GetCompanyWorkforceUseCase.WorkforcePage(List.of(workforce), 0, 20, 1));
        when(getWorkforceComparisonUseCase.get(any())).thenReturn(new WorkforceComparison(
                workforce,
                GroupComparison.noGroup(ComparisonAxis.INDUSTRY,
                        ComparisonUnavailableReason.INDUSTRY_CODE_MISSING),
                GroupComparison.noGroup(ComparisonAxis.REGION,
                        ComparisonUnavailableReason.REGION_UNPARSEABLE)));
        when(getWorkforceHistoryUseCase.get(any())).thenReturn(WorkforceHistory.of(List.of(workforce)));
    }
}
