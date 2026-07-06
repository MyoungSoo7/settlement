package github.lms.lemuel.settlement.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일일 대사용 금액 집계 Outbound Port — <b>생성 대사</b> 기준.
 *
 * <p>order 원천(캡처/환불)은 내부 대사 API 로, settlement 자기 합계(정산/조정)는 자기 DB 에서
 * <b>생성일(created_at) 기준</b>으로 집계한다. {@code settlement_date}(지급 예정일, T+N 영업일)로
 * 자르면 캡처일과 어긋나 대사가 구조적으로 깨진다 — 그 축은 지급 대사(payout)의 몫.
 */
public interface LoadDailyTotalsPort {

    /** 해당 날짜 캡처된 결제의 gross amount 합계 (이후 환불 여부 무관) */
    BigDecimal sumCapturedPayments(LocalDate date);

    /** 해당 날짜 COMPLETED 된 환불 amount 합계 */
    BigDecimal sumCompletedRefunds(LocalDate date);

    /** 해당 날짜 <b>생성된</b> 정산의 net_amount 합계 (CANCELED 제외) */
    BigDecimal sumSettlementNet(LocalDate date);

    /** 해당 날짜 <b>생성된</b> 정산의 commission 합계 (CANCELED 제외) */
    BigDecimal sumSettlementCommission(LocalDate date);

    /** 해당 날짜 생성된 환불 조정(역정산)의 합계 — 음수 기록을 양수로 환산해 반환 */
    BigDecimal sumRefundAdjustments(LocalDate date);
}
