package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 일일 정산 대사(Reconciliation) 리포트.
 *
 * 원장 불변식: {@code 결제합계 - 환불합계 = 정산 netAmount 합계 + 정산 commission 합계}
 * (commission 은 회사 수수료 수익으로 빠진 후 판매자에게 지급되는 금액이 netAmount)
 *
 * 이 불변식이 깨지면 금액이 새고 있다는 의미이므로 즉시 알림을 보내야 한다.
 */
public record ReconciliationReport(
        LocalDate targetDate,
        BigDecimal totalPayments,          // 해당 날짜 CAPTURED 결제 합계
        BigDecimal totalRefunds,           // 해당 날짜 COMPLETED 환불 합계
        BigDecimal totalSettlementNet,     // 해당 날짜 정산 netAmount 합계
        BigDecimal totalSettlementCommission, // 해당 날짜 정산 commission 합계
        BigDecimal discrepancy,            // (결제-환불) - (net+commission) = 0 이어야 함
        boolean matched
) {

    public static ReconciliationReport of(LocalDate date,
                                          BigDecimal payments,
                                          BigDecimal refunds,
                                          BigDecimal settlementNet,
                                          BigDecimal settlementCommission) {
        BigDecimal zero = BigDecimal.ZERO;
        BigDecimal pays = payments != null ? payments : zero;
        BigDecimal refs = refunds != null ? refunds : zero;
        BigDecimal net = settlementNet != null ? settlementNet : zero;
        BigDecimal comm = settlementCommission != null ? settlementCommission : zero;

        BigDecimal expected = pays.subtract(refs);
        BigDecimal actual = net.add(comm);
        BigDecimal diff = expected.subtract(actual).setScale(2, RoundingMode.HALF_UP);

        boolean ok = diff.compareTo(BigDecimal.ZERO) == 0;
        return new ReconciliationReport(date, pays, refs, net, comm, diff, ok);
    }
}
