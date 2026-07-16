package github.lms.lemuel.investment.adapter.out.metrics;

import github.lms.lemuel.investment.application.port.out.InvestmentMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@link InvestmentMetricsPort} 의 Micrometer 구현 (settlement 의 관측 지표 스타일 재사용).
 *
 * <p>노출 지표(Prometheus 변환명):
 * <ul>
 *   <li>{@code investment_order_placed_total} — 주문 신청 성공</li>
 *   <li>{@code investment_order_rejected_total{reason}} — 신청 거절(reason=not_investable|insufficient_funding)</li>
 *   <li>{@code investment_order_executed_total} — 주문 집행 성공 건수</li>
 *   <li>{@code investment_order_executed_amount_total} — 주문 집행 금액 합계</li>
 *   <li>{@code investment_order_execution_insufficient_funding_total} — 집행 시점 재원부족 거절</li>
 * </ul>
 *
 * <p>reason 태그 카디널리티는 고정 2종(not_investable/insufficient_funding)이라 폭증 위험이 없다.
 * 사유별 카운터를 생성자에서 미리 등록해 첫 이벤트 이전에도 0 값이 스크레이프되게 한다.
 */
@Component
public class MicrometerInvestmentMetricsAdapter implements InvestmentMetricsPort {

    private static final String REASON_NOT_INVESTABLE = "NOT_INVESTABLE";
    private static final String REASON_INSUFFICIENT_FUNDING = "INSUFFICIENT_FUNDING";

    private final Counter placed;
    private final Counter rejectedNotInvestable;
    private final Counter rejectedInsufficientFunding;
    private final Counter executed;
    private final Counter executedAmount;
    private final Counter executionInsufficientFunding;

    public MicrometerInvestmentMetricsAdapter(MeterRegistry registry) {
        this.placed = Counter.builder("investment.order.placed")
                .description("투자주문 신청 성공 건수").register(registry);
        this.rejectedNotInvestable = Counter.builder("investment.order.rejected")
                .tag("reason", "not_investable")
                .description("투자주문 신청 거절 건수(부적격 종목)").register(registry);
        this.rejectedInsufficientFunding = Counter.builder("investment.order.rejected")
                .tag("reason", "insufficient_funding")
                .description("투자주문 신청 거절 건수(재원 부족)").register(registry);
        this.executed = Counter.builder("investment.order.executed")
                .description("투자주문 집행 성공 건수").register(registry);
        this.executedAmount = Counter.builder("investment.order.executed.amount")
                .baseUnit("KRW")
                .description("투자주문 집행 금액 합계").register(registry);
        this.executionInsufficientFunding = Counter.builder("investment.order.execution.insufficient_funding")
                .description("집행 시점 재원부족 거절 건수").register(registry);
    }

    @Override
    public void orderPlaced() {
        placed.increment();
    }

    @Override
    public void orderPlacementRejected(String reason) {
        if (REASON_NOT_INVESTABLE.equals(reason)) {
            rejectedNotInvestable.increment();
        } else if (REASON_INSUFFICIENT_FUNDING.equals(reason)) {
            rejectedInsufficientFunding.increment();
        }
    }

    @Override
    public void orderExecuted(BigDecimal amount) {
        executed.increment();
        if (amount != null && amount.signum() > 0) {
            executedAmount.increment(amount.doubleValue());
        }
    }

    @Override
    public void orderExecutionRejectedInsufficientFunding() {
        executionInsufficientFunding.increment();
    }
}
