package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 일일 정산 대사(Reconciliation) 리포트 — <b>생성 대사</b>.
 *
 * <p>등급별 정산주기(ADR 0014, T+N 영업일) 도입 후 {@code settlement_date} 는 지급 예정일이 되어
 * 결제 캡처일과 어긋난다. 따라서 대사는 지급예정일이 아니라 <b>같은 날 일어난 사건끼리</b> 두 축으로 대조한다:
 *
 * <ol>
 *   <li><b>결제 축</b>: 그날 캡처된 결제 총액(gross, 이후 환불 여부 무관)
 *       = 그날 <b>생성된</b> 정산의 {@code netAmount + commission} 합계
 *       (모든 캡처는 같은 날 정산 1건을 만들어야 한다 — 3단 멱등 방어의 대사 측 검증)</li>
 *   <li><b>환불 축</b>: 그날 COMPLETED 된 환불 총액
 *       = 그날 생성된 환불 조정(역정산, ADR 0004)의 합계(양수 환산)</li>
 * </ol>
 *
 * 어느 축이든 어긋나면 금액이 새고 있다는 의미이므로 즉시 알림을 보내야 한다.
 */
public record ReconciliationReport(
        LocalDate targetDate,
        BigDecimal totalPayments,             // 해당 날짜 캡처된 결제 gross 합계 (이후 환불 무관)
        BigDecimal totalRefunds,              // 해당 날짜 COMPLETED 환불 합계
        BigDecimal totalSettlementNet,        // 해당 날짜 생성된 정산 netAmount 합계 (CANCELED 제외)
        BigDecimal totalSettlementCommission, // 해당 날짜 생성된 정산 commission 합계 (CANCELED 제외)
        BigDecimal totalRefundAdjustments,    // 해당 날짜 생성된 환불 조정 합계 (음수 기록의 양수 환산)
        BigDecimal paymentDiscrepancy,        // payments - (net + commission) = 0 이어야 함
        BigDecimal refundDiscrepancy,         // refunds - refundAdjustments = 0 이어야 함
        BigDecimal discrepancy,               // |paymentDiscrepancy| + |refundDiscrepancy| (경보 총량)
        boolean matched
) {

    public static ReconciliationReport of(LocalDate date,
                                          BigDecimal payments,
                                          BigDecimal refunds,
                                          BigDecimal settlementNet,
                                          BigDecimal settlementCommission,
                                          BigDecimal refundAdjustments) {
        BigDecimal pays = nz(payments);
        BigDecimal refs = nz(refunds);
        BigDecimal net = nz(settlementNet);
        BigDecimal comm = nz(settlementCommission);
        BigDecimal adjs = nz(refundAdjustments);

        BigDecimal payDiff = pays.subtract(net.add(comm)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal refDiff = refs.subtract(adjs).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = payDiff.abs().add(refDiff.abs());

        boolean ok = payDiff.compareTo(BigDecimal.ZERO) == 0
                && refDiff.compareTo(BigDecimal.ZERO) == 0;
        return new ReconciliationReport(date, pays, refs, net, comm, adjs, payDiff, refDiff, total, ok);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
