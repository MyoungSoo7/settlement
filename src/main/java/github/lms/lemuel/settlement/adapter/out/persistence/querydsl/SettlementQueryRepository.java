package github.lms.lemuel.settlement.adapter.out.persistence.querydsl;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;

import java.time.LocalDate;
import java.util.List;

/**
 * QueryDSL 기반 정산 조회 Repository
 */
public interface SettlementQueryRepository {

    List<SettlementSummaryDto> findDailySummary(LocalDate startDate, LocalDate endDate);

    List<SettlementSummaryDto> findMonthlySummary(LocalDate startDate, LocalDate endDate);

    SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(SettlementSearchCondition condition);

    PaymentRefundAggregationDto getPaymentRefundAggregation(LocalDate startDate, LocalDate endDate);

    SettlementCursorPageResponse<ApprovalStatusDto> findByApprovalStatus(
            String status, int size, Long cursorId);

    List<SettlementReconciliationDto> findReconciliationMismatches(
            LocalDate startDate, LocalDate endDate);

    List<SettlementDetailDto> findAuditTrailByPaymentId(Long paymentId);
}
