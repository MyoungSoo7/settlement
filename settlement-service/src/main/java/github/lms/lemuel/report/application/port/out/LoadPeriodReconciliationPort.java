package github.lms.lemuel.report.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 리포트 기간용 cross-domain 금액 집계 포트.
 *
 * <p>report 도메인의 대사 로직 전용. settlement 의 {@code LoadDailyTotalsPort} 와 목적은 같지만
 * - 단일 날짜가 아닌 <b>기간 범위</b>를 받는다 (from-to)
 * - settlement 도메인 경계를 침범하지 않도록 report 쪽에 별도 정의
 *
 * <p>어댑터는 payments / refunds / settlements 테이블을 JDBC aggregation 으로 직접 조회.
 */
public interface LoadPeriodReconciliationPort {

    /** 기간 내 CAPTURED 결제의 원 amount 합계 (환불 반영 전) */
    BigDecimal sumCapturedPayments(LocalDate from, LocalDate to);

    /** 기간 내 COMPLETED 환불 amount 합계 */
    BigDecimal sumCompletedRefunds(LocalDate from, LocalDate to);

    /** 기간 내 정산 net_amount 합계 (CANCELED 제외) */
    BigDecimal sumSettlementNet(LocalDate from, LocalDate to);

    /** 기간 내 정산 commission 합계 (CANCELED 제외) */
    BigDecimal sumSettlementCommission(LocalDate from, LocalDate to);

    // ---------- Invariant #2 ----------

    /**
     * 기간 내 정산 조정(adjustments.amount) 절대값 합계.
     * 스키마 상 amount 는 항상 음수이므로 {@code -SUM(amount)} 로 양수 변환해서 반환.
     */
    BigDecimal sumAdjustmentsAbsolute(LocalDate from, LocalDate to);

    /**
     * 기간 내 조정에 연결된 refunds 의 amount 합계 (status='COMPLETED' 만).
     * adjustments JOIN refunds 로 1:1 연결을 집계. 정상이면 {@link #sumAdjustmentsAbsolute} 와 같아야 한다.
     */
    BigDecimal sumRefundsLinkedToAdjustments(LocalDate from, LocalDate to);

    // ---------- Invariant #3 ----------

    /**
     * 기간 내 {@code PaymentCaptured} outbox 이벤트가 PUBLISHED 로 전이된 건수.
     * {@code published_at::date} 기준.
     */
    long countPaymentCapturedPublished(LocalDate from, LocalDate to);

    /**
     * 기간 내 생성된 settlements 건수.
     * {@code created_at::date} 기준 (settlement_date 가 아님 — 생성 시점 기준 이벤트-세트먼트 1:1 대사).
     */
    long countSettlementsCreated(LocalDate from, LocalDate to);
}
