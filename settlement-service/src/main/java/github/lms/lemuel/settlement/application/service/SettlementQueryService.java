package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;
import github.lms.lemuel.settlement.application.port.out.QuerySettlementPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 조회 Application Service
 *
 * readOnly=true: Hibernate flush mode를 MANUAL로 설정하여 dirty checking 비용 제거
 * PostgreSQL replica로 라우팅 가능 (Read/Write 분리 시)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementQueryService {

    private final QuerySettlementPort querySettlementPort;

    public List<SettlementSummaryDto> getDailySummary(LocalDate startDate, LocalDate endDate) {
        return querySettlementPort.findDailySummary(startDate, endDate);
    }

    public List<SettlementSummaryDto> getMonthlySummary(LocalDate startDate, LocalDate endDate) {
        return querySettlementPort.findMonthlySummary(startDate, endDate);
    }

    public SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(
            SettlementSearchCondition condition) {
        return querySettlementPort.searchSettlements(condition);
    }

    public PaymentRefundAggregationDto getPaymentRefundAggregation(
            LocalDate startDate, LocalDate endDate) {
        return querySettlementPort.getPaymentRefundAggregation(startDate, endDate);
    }

    public SettlementCursorPageResponse<ApprovalStatusDto> getApprovalStatus(
            String status, int size, Long cursorId) {
        return querySettlementPort.findByApprovalStatus(status, size, cursorId);
    }

    public List<SettlementReconciliationDto> getReconciliationMismatches(
            LocalDate startDate, LocalDate endDate) {
        return querySettlementPort.findReconciliationMismatches(startDate, endDate);
    }

    public List<SettlementDetailDto> getAuditTrail(Long paymentId) {
        return querySettlementPort.findAuditTrailByPaymentId(paymentId);
    }
}
