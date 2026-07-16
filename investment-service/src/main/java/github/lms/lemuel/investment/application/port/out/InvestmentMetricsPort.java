package github.lms.lemuel.investment.application.port.out;

import java.math.BigDecimal;

/**
 * 투자주문 핵심 액션의 관측 지표 아웃바운드 포트.
 *
 * <p>주문 신청·집행·거절과 재원부족 거절의 발생을 카운터로 노출해 집행/거절 비율·집행 금액 흐름을
 * 운영에서 감시한다. 구현은 {@code adapter.out.metrics} 의 Micrometer 어댑터가 담당한다.
 */
public interface InvestmentMetricsPort {

    /** 투자주문 신청 성공(REQUESTED 등록). */
    void orderPlaced();

    /**
     * 투자주문 신청 거절.
     *
     * @param reason 거절 사유 태그 — {@code NOT_INVESTABLE}(부적격 종목) 또는 {@code INSUFFICIENT_FUNDING}(재원 부족)
     */
    void orderPlacementRejected(String reason);

    /**
     * 투자주문 집행 성공(EXECUTED). 집행 건수와 집행 금액 합계를 함께 적재한다.
     *
     * @param amount 집행된 투자 금액
     */
    void orderExecuted(BigDecimal amount);

    /** 집행 시점 재원 재검증 실패로 인한 거절(REJECTED). */
    void orderExecutionRejectedInsufficientFunding();
}
