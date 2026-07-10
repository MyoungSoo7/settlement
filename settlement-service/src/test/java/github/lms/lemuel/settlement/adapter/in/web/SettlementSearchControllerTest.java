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
}
