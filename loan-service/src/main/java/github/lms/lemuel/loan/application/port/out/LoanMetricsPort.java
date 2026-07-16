package github.lms.lemuel.loan.application.port.out;

import java.math.BigDecimal;

/**
 * 대출 도메인 핵심 액션의 관측 지표 아웃바운드 포트.
 *
 * <p>돈이 움직이는 유스케이스(신청·실행·상환)와 심사 거절의 발생을 카운터로 노출해
 * 승인/거절 비율·실행 금액 흐름을 운영에서 감시한다. 구현은 {@code adapter.out.metrics}
 * 의 Micrometer 어댑터가 담당한다(도메인/애플리케이션은 MeterRegistry 를 직접 알지 않는다).
 */
public interface LoanMetricsPort {

    /** 선정산 대출 신청 성공(REQUESTED 등록). */
    void advanceRequested();

    /** 선정산 대출 실행 성공(DISBURSED). */
    void advanceDisbursed();

    /** 실행 시점 담보 재검증 실패로 인한 승인 거절(REJECTED). */
    void advanceRejected();

    /** 기업 신용대출 신청 성공(REQUESTED 등록). */
    void corporateRequested();

    /** 기업 신용대출 신용평가 거절(E등급·한도초과·재무자료 없음). */
    void corporateRejected();

    /** 기업 신용대출 실행 성공(DISBURSED). */
    void corporateDisbursed();

    /**
     * 상환 차감 적용. 차감 건수와 차감 금액 합계를 함께 적재한다.
     *
     * @param deductedAmount 이번 정산 확정으로 실제 차감된 상환 금액(0 이상)
     */
    void repaymentApplied(BigDecimal deductedAmount);
}
