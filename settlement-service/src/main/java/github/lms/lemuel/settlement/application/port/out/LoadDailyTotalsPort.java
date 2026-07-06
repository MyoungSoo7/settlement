package github.lms.lemuel.settlement.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일일 대사용 금액 집계 Outbound Port — <b>캡처일 기준 양축 대사</b>.
 *
 * <p>모든 축을 캡처일(= 정산 생성일 {@code created_at}) 하나로 키를 맞춘다. 지급예정일
 * ({@code settlement_date}, T+N 영업일)이나 환불 완료일로 키를 잡으면 처리 지연·백필에 따라
 * 대사가 구조적으로 흔들리기 때문이다.
 *
 * <ul>
 *   <li><b>캡처 축</b>: order 캡처 gross == settlement 정산 gross({@code payment_amount})</li>
 *   <li><b>환불 축</b>: order 캡처분에 반영된 환불 == settlement 정산의 {@code refunded_amount}</li>
 * </ul>
 *
 * gross({@code payment_amount})·{@code refunded_amount} 는 환불로 소급 변동하지 않는 안정 컬럼이라
 * ({@code net_amount} 는 환불로 실시간 감소하므로 대사 기준에 부적합) 대사가 항상 수렴한다.
 */
public interface LoadDailyTotalsPort {

    /** order: 해당 날짜 캡처된 결제 gross 합계 (CAPTURED+REFUNDED, 이후 환불 무관) */
    BigDecimal sumCapturedPayments(LocalDate date);

    /** order: 해당 날짜 캡처분에 반영된 환불액 합계 (캡처일 기준) */
    BigDecimal sumRefundedAgainstCaptures(LocalDate date);

    /** settlement: 해당 날짜 생성된 정산의 gross(payment_amount) 합계 */
    BigDecimal sumSettlementGross(LocalDate date);

    /** settlement: 해당 날짜 생성된 정산의 반영 환불액(refunded_amount) 합계 */
    BigDecimal sumSettlementRefunded(LocalDate date);
}
