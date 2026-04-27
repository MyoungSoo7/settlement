package github.lms.lemuel.settlement.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일일 대사용 금액 집계 Outbound Port.
 *
 * 읽기 전용 CQRS 조회로 payments / refunds / settlements 테이블을 가로지른다.
 * 어댑터 구현체는 JDBC aggregate query 로 구현 (JPA 로 도메인 재조립할 필요 없음).
 */
public interface LoadDailyTotalsPort {

    /** 해당 날짜 CAPTURED 된 결제의 원 amount 합계 (환불 반영 전) */
    BigDecimal sumCapturedPayments(LocalDate date);

    /** 해당 날짜 COMPLETED 된 환불 amount 합계 */
    BigDecimal sumCompletedRefunds(LocalDate date);

    /** 해당 날짜 정산의 net_amount 합계 (CANCELED 제외) */
    BigDecimal sumSettlementNet(LocalDate date);

    /** 해당 날짜 정산의 commission 합계 (CANCELED 제외) */
    BigDecimal sumSettlementCommission(LocalDate date);
}
