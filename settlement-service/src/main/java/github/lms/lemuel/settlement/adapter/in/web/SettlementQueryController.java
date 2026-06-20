package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;
import github.lms.lemuel.settlement.application.service.SettlementQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/settlements/query")
@RequiredArgsConstructor
public class SettlementQueryController {

    private final SettlementQueryService queryService;

    /**
     * 일별 정산 요약
     * GET /api/settlements/query/summary/daily?startDate=2026-01-01&endDate=2026-01-31
     */
    @GetMapping("/summary/daily")
    public ResponseEntity<List<SettlementSummaryDto>> getDailySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(queryService.getDailySummary(startDate, endDate));
    }

    /**
     * 월별 정산 요약
     * GET /api/settlements/query/summary/monthly?startDate=2026-01-01&endDate=2026-12-31
     */
    @GetMapping("/summary/monthly")
    public ResponseEntity<List<SettlementSummaryDto>> getMonthlySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(queryService.getMonthlySummary(startDate, endDate));
    }

    /**
     * 정산 상세 검색 (Cursor 기반)
     * GET /api/settlements/query/search?status=DONE&startDate=2026-01-01&size=20&cursorId=100&cursorDate=2026-01-15
     */
    @GetMapping("/search")
    public ResponseEntity<SettlementCursorPageResponse<SettlementDetailDto>> searchSettlements(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String ordererName,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) Boolean isRefunded,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cursorDate,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "settlementDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {

        SettlementSearchCondition condition = SettlementSearchCondition.builder()
                .status(status)
                .startDate(startDate)
                .endDate(endDate)
                .userId(userId)
                .ordererName(ordererName)
                .productName(productName)
                .isRefunded(isRefunded)
                .cursorId(cursorId)
                .cursorDate(cursorDate)
                .size(size)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();

        return ResponseEntity.ok(queryService.searchSettlements(condition));
    }

    /**
     * 결제/환불 집계
     * GET /api/settlements/query/aggregation?startDate=2026-01-01&endDate=2026-01-31
     */
    @GetMapping("/aggregation")
    public ResponseEntity<PaymentRefundAggregationDto> getPaymentRefundAggregation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(queryService.getPaymentRefundAggregation(startDate, endDate));
    }

    /**
     * 승인 상태 추적
     * GET /api/settlements/query/approvals?status=WAITING_APPROVAL&size=20&cursorId=100
     */
    @GetMapping("/approvals")
    public ResponseEntity<SettlementCursorPageResponse<ApprovalStatusDto>> getApprovalStatus(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long cursorId) {
        return ResponseEntity.ok(queryService.getApprovalStatus(status, size, cursorId));
    }

    /**
     * 대사 불일치 탐지
     * GET /api/settlements/query/reconciliation?startDate=2026-01-01&endDate=2026-01-31
     */
    @GetMapping("/reconciliation")
    public ResponseEntity<List<SettlementReconciliationDto>> getReconciliationMismatches(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(queryService.getReconciliationMismatches(startDate, endDate));
    }

    /**
     * 감사 추적 (결제 ID 기준)
     * GET /api/settlements/query/audit/payment/123
     */
    @GetMapping("/audit/payment/{paymentId}")
    public ResponseEntity<List<SettlementDetailDto>> getAuditTrail(@PathVariable Long paymentId) {
        return ResponseEntity.ok(queryService.getAuditTrail(paymentId));
    }
}
