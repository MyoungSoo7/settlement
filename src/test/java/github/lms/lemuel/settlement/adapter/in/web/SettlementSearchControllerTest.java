package github.lms.lemuel.settlement.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementAggregationsResponse;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementPageResponse;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementSearchItemResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementSearchJdbcRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SettlementSearchController.class)
@DisplayName("SettlementSearchController")
class SettlementSearchControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean
    SettlementSearchJdbcRepository searchRepository;

    private SettlementPageResponse emptyPage() {
        return new SettlementPageResponse(
            List.of(), 0L, 0, 0, 20,
            new SettlementAggregationsResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of())
        );
    }

    private SettlementPageResponse pageWithItems(List<SettlementSearchItemResponse> items) {
        return new SettlementPageResponse(
            items, items.size(), 1, 0, 20,
            new SettlementAggregationsResponse(new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("97000"), Map.of("DONE", (long) items.size()))
        );
    }

    @Nested
    @DisplayName("GET /api/settlements/search")
    class Search {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("파라미터 없이 요청 시 200과 빈 결과를 반환한다")
        void search_noParams_returns200() throws Exception {
            given(searchRepository.search(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(0), eq(20), eq("createdAt"), eq("DESC")))
                .willReturn(emptyPage());

            mockMvc.perform(get("/api/settlements/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.currentPage").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("날짜 범위 파라미터로 검색한다")
        void search_withDateRange_passesParamsToRepository() throws Exception {
            given(searchRepository.search(isNull(), isNull(), isNull(), isNull(),
                eq("2026-01-01"), eq("2026-01-31"),
                eq(0), eq(20), eq("createdAt"), eq("DESC")))
                .willReturn(emptyPage());

            mockMvc.perform(get("/api/settlements/search")
                    .param("startDate", "2026-01-01")
                    .param("endDate", "2026-01-31"))
                .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("status 파라미터로 필터링한다")
        void search_withStatus_filtersCorrectly() throws Exception {
            SettlementSearchItemResponse item = new SettlementSearchItemResponse(
                1L, 10L, 100L, "user@test.com", "상품A",
                new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("97000"),
                "DONE", false, LocalDate.of(2026, 1, 15)
            );
            given(searchRepository.search(isNull(), isNull(), isNull(), eq("DONE"),
                isNull(), isNull(), eq(0), eq(20), eq("createdAt"), eq("DESC")))
                .willReturn(pageWithItems(List.of(item)));

            mockMvc.perform(get("/api/settlements/search").param("status", "DONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.settlements[0].status").value("DONE"))
                .andExpect(jsonPath("$.settlements[0].settlementId").value(1));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("페이지네이션 파라미터를 전달한다")
        void search_withPagination_passesPageParams() throws Exception {
            given(searchRepository.search(isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(2), eq(10), eq("amount"), eq("ASC")))
                .willReturn(emptyPage());

            mockMvc.perform(get("/api/settlements/search")
                    .param("page", "2")
                    .param("size", "10")
                    .param("sortBy", "amount")
                    .param("sortDirection", "ASC"))
                .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "MANAGER")
        @DisplayName("MANAGER 역할도 접근 가능하다")
        void search_asManager_returns200() throws Exception {
            given(searchRepository.search(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any()))
                .willReturn(emptyPage());

            mockMvc.perform(get("/api/settlements/search"))
                .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("USER 역할은 접근이 거부된다")
        void search_asUser_returns403() throws Exception {
            mockMvc.perform(get("/api/settlements/search"))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("비인증 요청은 401을 반환한다")
        void search_unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/settlements/search"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("aggregations 정보가 응답에 포함된다")
        void search_includesAggregations() throws Exception {
            SettlementSearchItemResponse item = new SettlementSearchItemResponse(
                1L, 10L, 100L, "user@test.com", "상품A",
                new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("97000"),
                "DONE", false, LocalDate.of(2026, 1, 15)
            );
            given(searchRepository.search(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any()))
                .willReturn(pageWithItems(List.of(item)));

            mockMvc.perform(get("/api/settlements/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregations").exists())
                .andExpect(jsonPath("$.aggregations.totalAmount").value(100000))
                .andExpect(jsonPath("$.aggregations.totalFinalAmount").value(97000));
        }
    }
}