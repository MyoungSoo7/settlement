package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TradePlanPolicy — 3분할 밴드(30/30/40 · 100%/95%/90%)·손절 −7%·익절 +20%·KRX 호가단위 내림의
 * 결정적 산출 검증 (kakaopay-invest-companion trade-plan.mjs 이식 정합성).
 */
class TradePlanPolicyTest {

    private final TradePlanPolicy policy = new TradePlanPolicy();

    @Nested
    @DisplayName("KRX 호가단위")
    class Tick {

        @Test
        @DisplayName("가격 구간별 호가단위 내림이 정확하다")
        void roundsDownPerBand() {
            assertThat(TradePlanPolicy.roundDownToTick(new BigDecimal("1999"))).isEqualByComparingTo("1999");
            assertThat(TradePlanPolicy.roundDownToTick(new BigDecimal("2001"))).isEqualByComparingTo("2000");
            assertThat(TradePlanPolicy.roundDownToTick(new BigDecimal("19994"))).isEqualByComparingTo("19990");
            assertThat(TradePlanPolicy.roundDownToTick(new BigDecimal("49999"))).isEqualByComparingTo("49950");
            assertThat(TradePlanPolicy.roundDownToTick(new BigDecimal("199999"))).isEqualByComparingTo("199900");
            assertThat(TradePlanPolicy.roundDownToTick(new BigDecimal("499999"))).isEqualByComparingTo("499500");
            assertThat(TradePlanPolicy.roundDownToTick(new BigDecimal("512345"))).isEqualByComparingTo("512000");
        }

        @Test
        @DisplayName("구간 경계값은 상위 호가단위를 쓴다")
        void boundaryUsesUpperTick() {
            assertThat(TradePlanPolicy.tickSize(new BigDecimal("2000"))).isEqualByComparingTo("5");
            assertThat(TradePlanPolicy.tickSize(new BigDecimal("500000"))).isEqualByComparingTo("1000");
        }
    }

    @Test
    @DisplayName("예산 모드 — 63,500원·300만원이면 3분할 수량·평균가·손절/익절 기준가가 결정적이다")
    void budgetModeIsDeterministic() {
        TradePlan plan = policy.plan(new BigDecimal("63500"), new BigDecimal("3000000"));

        assertThat(plan.feasible()).isTrue();
        assertThat(plan.entries()).hasSize(3);

        TradePlan.EntryBand first = plan.entries().get(0);
        assertThat(first.targetPrice()).isEqualByComparingTo("63500");
        assertThat(first.quantity()).isEqualTo(14);
        assertThat(first.amount()).isEqualByComparingTo("889000");

        TradePlan.EntryBand second = plan.entries().get(1);
        assertThat(second.targetPrice()).isEqualByComparingTo("60300"); // 60,325 → tick 100 내림
        assertThat(second.quantity()).isEqualTo(14);

        TradePlan.EntryBand third = plan.entries().get(2);
        assertThat(third.targetPrice()).isEqualByComparingTo("57100"); // 57,150 → tick 100 내림
        assertThat(third.quantity()).isEqualTo(21);

        assertThat(plan.totalQuantity()).isEqualTo(49);
        assertThat(plan.totalAmount()).isEqualByComparingTo("2932300");
        assertThat(plan.avgEntryPrice()).isEqualByComparingTo("59843");
        assertThat(plan.stopLossPrice()).isEqualByComparingTo("55600");   // 평균가 ×0.93 → 내림
        assertThat(plan.takeProfitPrice()).isEqualByComparingTo("71800"); // 평균가 ×1.20 → 내림
    }

    @Test
    @DisplayName("예산 미지정 — 가격 레벨 전용 모드(수량 null, 30/30/40 가중 평균가)")
    void priceLevelsOnlyMode() {
        TradePlan plan = policy.plan(new BigDecimal("63500"), null);

        assertThat(plan.feasible()).isTrue();
        assertThat(plan.totalQuantity()).isNull();
        assertThat(plan.totalAmount()).isNull();
        assertThat(plan.entries()).allSatisfy(e -> {
            assertThat(e.quantity()).isNull();
            assertThat(e.amount()).isNull();
        });
        assertThat(plan.avgEntryPrice()).isEqualByComparingTo("59980");
        assertThat(plan.stopLossPrice()).isEqualByComparingTo("55700");
        assertThat(plan.takeProfitPrice()).isEqualByComparingTo("71900");
    }

    @Test
    @DisplayName("예산이 1주에도 못 미치면 infeasible + 사유")
    void infeasibleWhenBudgetTooSmall() {
        TradePlan plan = policy.plan(new BigDecimal("100000"), new BigDecimal("50000"));

        assertThat(plan.feasible()).isFalse();
        assertThat(plan.infeasibleReason()).contains("1주");
        assertThat(plan.entries()).isEmpty();
        assertThat(plan.stopLossPrice()).isNull();
    }

    @Test
    @DisplayName("현재가·예산이 0 이하이면 InvestmentInvariantViolationException")
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> policy.plan(BigDecimal.ZERO, new BigDecimal("1000000")))
                .isInstanceOf(InvestmentInvariantViolationException.class);
        assertThatThrownBy(() -> policy.plan(null, new BigDecimal("1000000")))
                .isInstanceOf(InvestmentInvariantViolationException.class);
        assertThatThrownBy(() -> policy.plan(new BigDecimal("10000"), BigDecimal.ZERO))
                .isInstanceOf(InvestmentInvariantViolationException.class);
        assertThatThrownBy(() -> policy.plan(new BigDecimal("10000"), new BigDecimal("-1")))
                .isInstanceOf(InvestmentInvariantViolationException.class);
    }
}
