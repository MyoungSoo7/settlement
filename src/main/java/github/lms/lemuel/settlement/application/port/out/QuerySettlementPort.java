package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 조회 전용 Outbound Port (Read Model)
 * Write model(LoadSettlementPort)과 분리하여 CQRS 원칙 적용
 */
public interface QuerySettlementPort {

    List<SettlementSummaryDto> findDailySummary(LocalDate startDate, LocalDate endDate);

    List<SettlementSummaryDto> findMonthlySummary(LocalDate startDate, LocalDate endDate);

    SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(SettlementSearchCondition condition);

    PaymentRefundAggregationDto getPaymentRefundAggregation(LocalDate startDate, LocalDate endDate);

    SettlementCursorPageResponse<ApprovalStatusDto> findByApprovalStatus(
            String status, int size, Long cursorId);

    List<SettlementReconciliationDto> findReconciliationMismatches(LocalDate startDate, LocalDate endDate);

    List<SettlementDetailDto> findAuditTrailByPaymentId(Long paymentId);
}
