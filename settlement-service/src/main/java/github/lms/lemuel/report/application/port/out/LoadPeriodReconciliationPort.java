package github.lms.lemuel.report.application.port.out;

/**
 * 리포트 기간용 cross-domain 금액 집계 포트 — 대사 3종 불변식의 집계 소스.
 *
 * <p>report 도메인의 대사 로직 전용. settlement 의 {@code LoadDailyTotalsPort} 와 목적은 같지만
 * - 단일 날짜가 아닌 <b>기간 범위</b>를 받는다 (from-to)
 * - settlement 도메인 경계를 침범하지 않도록 report 쪽에 별도 정의
 *
 * <p>어댑터는 payments / refunds / settlements 테이블을 JDBC aggregation 으로 직접 조회.
 *
 * <p><b>ISP</b>: 세 불변식 축을 응집 역할 인터페이스로 분리했다 —
 * {@link LoadPaymentSettlementTotalsPort}(#1 결제-정산 금액), {@link LoadAdjustmentReconciliationPort}(#2
 * 조정-환불 정합), {@link LoadOutboxSettlementCountPort}(#3 이벤트 파이프라인 건수). 이 포트는 셋을
 * 합성한 편의 집합이며, 한 축만 필요한 소비처는 해당 역할 인터페이스만 의존하면 된다. 어댑터는 이 합성
 * 포트를 구현해 세 역할을 한 번에 만족시킨다.
 */
public interface LoadPeriodReconciliationPort
        extends LoadPaymentSettlementTotalsPort,
                LoadAdjustmentReconciliationPort,
                LoadOutboxSettlementCountPort {
}
