package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 일일 정산 대사(Reconciliation) 리포트 — <b>캡처일 기준 양축 대사</b>.
 *
 * <p>등급별 정산주기(ADR 0014, T+N 영업일) 도입 후 {@code settlement_date} 는 지급 예정일이 되어
 * 캡처일과 어긋난다. 또 {@code net_amount} 는 환불로 실시간 감소한다. 그래서 대사는 캡처일 하나로
 * 키를 맞추고, 환불에 소급 변동하지 않는 안정 컬럼(gross {@code payment_amount}, {@code refunded_amount})
 * 으로 두 축을 대조한다:
 *
 * <ol>
 *   <li><b>캡처 축</b>: order 캡처 gross(CAPTURED+REFUNDED) == 그날 생성된 정산 gross(payment_amount) 합계.
 *       모든 캡처가 같은 날 정산 1건을 만들었는지 검증(3단 멱등 방어의 대사 측).</li>
 *   <li><b>환불 축</b>: order 캡처분에 반영된 환불 == 그날 생성된 정산의 refunded_amount 합계.
 *       환불이 정산에 실반영됐는지 검증(역정산 컨슈머 결선 여부를 정면 감지).</li>
 * </ol>
 *
 * 어느 축이든 어긋나면 금액이 새고 있다는 의미이므로 즉시 알림을 보내야 한다.
 */
public record ReconciliationReport(
        LocalDate targetDate,
        BigDecimal capturedPayments,      // order: 그날 캡처 gross (CAPTURED+REFUNDED)
        BigDecimal settlementGross,       // settlement: 그날 생성 정산 payment_amount 합계
        BigDecimal refundedAgainstCaptures, // order: 그날 캡처분에 반영된 환불액
        BigDecimal settlementRefunded,    // settlement: 그날 생성 정산 refunded_amount 합계
        BigDecimal captureDiscrepancy,    // capturedPayments - settlementGross = 0 이어야 함
        BigDecimal refundDiscrepancy,     // refundedAgainstCaptures - settlementRefunded = 0 이어야 함
        BigDecimal discrepancy,           // |captureDiscrepancy| + |refundDiscrepancy| (경보 총량)
        boolean matched
) {

    public static ReconciliationReport of(LocalDate date,
                                          BigDecimal capturedPayments,
                                          BigDecimal settlementGross,
                                          BigDecimal refundedAgainstCaptures,
                                          BigDecimal settlementRefunded) {
        BigDecimal caps = nz(capturedPayments);
        BigDecimal sGross = nz(settlementGross);
        BigDecimal oRefunded = nz(refundedAgainstCaptures);
        BigDecimal sRefunded = nz(settlementRefunded);

        BigDecimal capDiff = caps.subtract(sGross).setScale(2, RoundingMode.HALF_UP);
        BigDecimal refDiff = oRefunded.subtract(sRefunded).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = capDiff.abs().add(refDiff.abs());

        boolean ok = capDiff.compareTo(BigDecimal.ZERO) == 0
                && refDiff.compareTo(BigDecimal.ZERO) == 0;
        return new ReconciliationReport(date, caps, sGross, oRefunded, sRefunded, capDiff, refDiff, total, ok);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
