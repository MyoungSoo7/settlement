package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementAggregationsResponse;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementPageResponse;
import github.lms.lemuel.settlement.adapter.in.web.response.SettlementSearchItemResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementSearchJdbcRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SettlementSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
class SettlementSearchControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean SettlementSearchJdbcRepository searchRepository;

    @Test
    @DisplayName("GET /api/settlements/search — 검색 성공")
    void search() throws Exception {
        SettlementSearchItemResponse item = new SettlementSearchItemResponse(
                1L, 10L, 20L, "홍길동", "상품A",
                new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("48500"),
                "DONE", false, LocalDate.of(2026, 4, 1));
        SettlementAggregationsResponse agg = new SettlementAggregationsResponse(
                new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("48500"),
                Map.of("DONE", 1L));
        SettlementPageResponse response = new SettlementPageResponse(
                List.of(item), 1L, 1, 0, 20, agg);

        when(searchRepository.search(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyString(), anyString())).thenReturn(response);

        mockMvc.perform(get("/api/settlements/search")
                        .param("ordererName", "홍길동")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlements.length()").value(1))
                .andExpect(jsonPath("$.settlements[0].settlementId").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.aggregations.statusCounts.DONE").value(1));
    }

    @Test
    @DisplayName("size=0 → 400 (0 나눗셈·무의미 페이지 차단)")
    void rejectsZeroSize() throws Exception {
        mockMvc.perform(get("/api/settlements/search").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size=-1 → 400 (음수 LIMIT 차단)")
    void rejectsNegativeSize() throws Exception {
        mockMvc.perform(get("/api/settlements/search").param("size", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size=201 → 400 (상한 200 초과 무상한 스캔 차단)")
    void rejectsTooLargeSize() throws Exception {
        mockMvc.perform(get("/api/settlements/search").param("size", "201"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("page=-1 → 400 (음수 OFFSET 차단)")
    void rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/settlements/search").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("startDate > endDate → 400 (기간 역전)")
    void rejectsReversedDateRange() throws Exception {
        mockMvc.perform(get("/api/settlements/search")
                        .param("startDate", "2026-06-30")
                        .param("endDate", "2026-06-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("잘못된 날짜 형식 → 400")
    void rejectsMalformedDate() throws Exception {
        mockMvc.perform(get("/api/settlements/search").param("startDate", "notadate"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size=200 경계값 → 200 OK")
    void acceptsUpperBoundSize() throws Exception {
        SettlementAggregationsResponse agg = new SettlementAggregationsResponse(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of());
        when(searchRepository.search(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(new SettlementPageResponse(List.of(), 0L, 0, 0, 200, agg));

        mockMvc.perform(get("/api/settlements/search").param("size", "200"))
                .andExpect(status().isOk());
    }
}
