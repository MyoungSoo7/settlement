package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.ApprovalStatusDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.PaymentRefundAggregationDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementCursorPageResponse;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementDetailDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementReconciliationDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementSummaryDto;
import github.lms.lemuel.settlement.application.service.SettlementQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SettlementQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
class SettlementQueryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean SettlementQueryService queryService;

    @Test
    @DisplayName("GET /api/settlements/query/summary/daily — 일별 요약")
    void dailySummary() throws Exception {
        LocalDate start = LocalDate.of(2026, 4, 1);
        LocalDate end = LocalDate.of(2026, 4, 30);
        SettlementSummaryDto dto = new SettlementSummaryDto(
                start, 10L, new BigDecimal("100000"), new BigDecimal("10000"),
                new BigDecimal("3000"), new BigDecimal("87000"), 8L, 1L, 1L);
        when(queryService.getDailySummary(start, end)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/settlements/query/summary/daily")
                        .param("startDate", "2026-04-01")
                        .param("endDate", "2026-04-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].totalCount").value(10));
    }

    @Test
    @DisplayName("GET /api/settlements/query/summary/monthly — 월별 요약")
    void monthlySummary() throws Exception {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        SettlementSummaryDto dto = new SettlementSummaryDto(
                start, 100L, new BigDecimal("1000000"), new BigDecimal("100000"),
                new BigDecimal("30000"), new BigDecimal("870000"), 90L, 5L, 5L);
        when(queryService.getMonthlySummary(start, end)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/settlements/query/summary/monthly")
                        .param("startDate", "2026-01-01")
                        .param("endDate", "2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalCount").value(100));
    }

    @Test
    @DisplayName("GET /api/settlements/query/search — 상세 검색")
    void searchSettlements() throws Exception {
        SettlementDetailDto detail = new SettlementDetailDto(
                1L, new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("1500"),
                new BigDecimal("48500"), "DONE", LocalDate.of(2026, 4, 1),
                LocalDateTime.of(2026, 4, 1, 10, 0), LocalDateTime.of(2026, 4, 1, 9, 0),
                10L, 20L, 30L, "CARD", "CAPTURED", "buyer@test.com", "상품A", false);
        SettlementCursorPageResponse<SettlementDetailDto> page =
                new SettlementCursorPageResponse<>(List.of(detail), 1, false, null, null);
        when(queryService.searchSettlements(any())).thenReturn(page);

        mockMvc.perform(get("/api/settlements/query/search")
                        .param("status", "DONE")
                        .param("startDate", "2026-04-01")
                        .param("endDate", "2026-04-30")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].settlementId").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("GET /api/settlements/query/aggregation — 결제/환불 집계")
    void aggregation() throws Exception {
        LocalDate start = LocalDate.of(2026, 4, 1);
        LocalDate end = LocalDate.of(2026, 4, 30);
        PaymentRefundAggregationDto dto = new PaymentRefundAggregationDto(
                10L, new BigDecimal("100000"), 2L, new BigDecimal("10000"),
                new BigDecimal("3000"), new BigDecimal("87000"), new BigDecimal("0.20"));
        when(queryService.getPaymentRefundAggregation(start, end)).thenReturn(dto);

        mockMvc.perform(get("/api/settlements/query/aggregation")
                        .param("startDate", "2026-04-01")
                        .param("endDate", "2026-04-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPaymentCount").value(10))
                .andExpect(jsonPath("$.refundedPaymentCount").value(2));
    }

    @Test
    @DisplayName("GET /api/settlements/query/approvals — 승인 상태 추적")
    void approvals() throws Exception {
        ApprovalStatusDto dto = new ApprovalStatusDto(
                1L, 10L, 20L, new BigDecimal("48500"), "WAITING_APPROVAL",
                LocalDate.of(2026, 4, 1), "seller@test.com");
        SettlementCursorPageResponse<ApprovalStatusDto> page =
                new SettlementCursorPageResponse<>(List.of(dto), 1, false, null, null);
        when(queryService.getApprovalStatus(eq("WAITING_APPROVAL"), eq(20), isNull()))
                .thenReturn(page);

        mockMvc.perform(get("/api/settlements/query/approvals")
                        .param("status", "WAITING_APPROVAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("WAITING_APPROVAL"));
    }

    @Test
    @DisplayName("GET /api/settlements/query/reconciliation — 대사 불일치 탐지")
    void reconciliationMismatches() throws Exception {
        LocalDate start = LocalDate.of(2026, 4, 1);
        LocalDate end = LocalDate.of(2026, 4, 30);
        SettlementReconciliationDto dto = new SettlementReconciliationDto(
                1L, 10L, new BigDecimal("50000"), new BigDecimal("49000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000"),
                "CAPTURED", "DONE");
        when(queryService.getReconciliationMismatches(start, end)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/settlements/query/reconciliation")
                        .param("startDate", "2026-04-01")
                        .param("endDate", "2026-04-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amountDifference").value(1000));
    }

    @Test
    @DisplayName("GET /api/settlements/query/audit/payment/{paymentId} — 감사 추적")
    void auditTrail() throws Exception {
        SettlementDetailDto detail = new SettlementDetailDto(
                1L, new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("1500"),
                new BigDecimal("48500"), "DONE", LocalDate.of(2026, 4, 1),
                LocalDateTime.of(2026, 4, 1, 10, 0), LocalDateTime.of(2026, 4, 1, 9, 0),
                10L, 20L, 123L, "CARD", "CAPTURED", "buyer@test.com", "상품A", false);
        when(queryService.getAuditTrail(123L)).thenReturn(List.of(detail));

        mockMvc.perform(get("/api/settlements/query/audit/payment/123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(123));
    }
}
