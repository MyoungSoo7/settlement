package github.lms.lemuel.report.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 대사 불변식 #1(결제−환불 = 정산 net + commission) 전용 금액 집계 역할.
 *
 * <p>{@link LoadPeriodReconciliationPort} 의 응집 축 중 하나 — 기간 범위의 결제/환불/정산 금액 합계만
 * 필요로 하는 소비처는 이 역할만 의존하면 된다(ISP).
 */
public interface LoadPaymentSettlementTotalsPort {

    /** 기간 내 CAPTURED 결제의 원 amount 합계 (환불 반영 전) */
    BigDecimal sumCapturedPayments(LocalDate from, LocalDate to);

    /** 기간 내 COMPLETED 환불 amount 합계 */
    BigDecimal sumCompletedRefunds(LocalDate from, LocalDate to);

    /** 기간 내 정산 net_amount 합계 (CANCELED 제외) */
    BigDecimal sumSettlementNet(LocalDate from, LocalDate to);

    /** 기간 내 정산 commission 합계 (CANCELED 제외) */
    BigDecimal sumSettlementCommission(LocalDate from, LocalDate to);
}
