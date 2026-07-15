package github.lms.lemuel.report.application.port.out;

import java.time.LocalDate;

/**
 * 대사 불변식 #3(outbox PaymentCaptured PUBLISHED 건수 = settlements 생성 건수) 전용 건수 집계 역할.
 *
 * <p>{@link LoadPeriodReconciliationPort} 의 응집 축 중 하나 — 이벤트 파이프라인 원자성 건수만 보는
 * 소비처는 이 역할만 의존하면 된다(ISP).
 */
public interface LoadOutboxSettlementCountPort {

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
