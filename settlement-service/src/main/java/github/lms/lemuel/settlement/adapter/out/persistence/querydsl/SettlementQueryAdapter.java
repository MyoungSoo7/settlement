package github.lms.lemuel.settlement.adapter.out.persistence.querydsl;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;
import github.lms.lemuel.settlement.application.port.out.QuerySettlementPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementQueryAdapter implements QuerySettlementPort {

    private final SettlementQueryRepository queryRepository;

    @Override
    public List<SettlementSummaryDto> findDailySummary(LocalDate startDate, LocalDate endDate) {
        return queryRepository.findDailySummary(startDate, endDate);
    }

    @Override
    public List<SettlementSummaryDto> findMonthlySummary(LocalDate startDate, LocalDate endDate) {
        return queryRepository.findMonthlySummary(startDate, endDate);
    }

    @Override
    public SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(SettlementSearchCondition condition) {
        return queryRepository.searchSettlements(condition);
    }

    @Override
    public PaymentRefundAggregationDto getPaymentRefundAggregation(LocalDate startDate, LocalDate endDate) {
        return queryRepository.getPaymentRefundAggregation(startDate, endDate);
    }

    @Override
    public SettlementCursorPageResponse<ApprovalStatusDto> findByApprovalStatus(
            String status, int size, Long cursorId) {
        return queryRepository.findByApprovalStatus(status, size, cursorId);
    }

    @Override
    public List<SettlementReconciliationDto> findReconciliationMismatches(
            LocalDate startDate, LocalDate endDate) {
        return queryRepository.findReconciliationMismatches(startDate, endDate);
    }

    @Override
    public List<SettlementDetailDto> findAuditTrailByPaymentId(Long paymentId) {
        return queryRepository.findAuditTrailByPaymentId(paymentId);
    }
}
