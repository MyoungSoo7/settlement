package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.PaymentRefundAggregationDto;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementReconciliationDto;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 대사 조회 역할 — 결제-환불 집계·정산 불일치 목록 (Read Model).
 *
 * <p>{@link QuerySettlementPort} 의 응집 축 중 하나(대사/정합 점검). 대사만 쓰는 소비처는 이 역할만
 * 의존하면 된다(ISP).
 */
public interface SettlementReconciliationQueryPort {

    PaymentRefundAggregationDto getPaymentRefundAggregation(LocalDate startDate, LocalDate endDate);

    List<SettlementReconciliationDto> findReconciliationMismatches(LocalDate startDate, LocalDate endDate);
}
