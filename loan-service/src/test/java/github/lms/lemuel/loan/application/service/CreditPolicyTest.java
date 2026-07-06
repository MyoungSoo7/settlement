package github.lms.lemuel.loan.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditPolicyTest {

    // 기본 haircut: A·B=1.0, C=0.85, D=0.70, E=0.0
    private static final Map<String, BigDecimal> HAIRCUT = Map.of(
            "A", BigDecimal.ONE, "B", BigDecimal.ONE,
            "C", new BigDecimal("0.85"), "D", new BigDecimal("0.70"), "E", BigDecimal.ZERO);

    private final CreditPolicy policy = new CreditPolicy(
            new BigDecimal("0.80"),     // LTV 80%
            new BigDecimal("0.0002"),   // 일할이율 0.02%/day
            HAIRCUT);

    @Test
    void 한도는_미지급정산예정금_합계의_LTV() {
        BigDecimal limit = policy.creditLimit(new BigDecimal("1000000"));
        assertThat(limit).isEqualByComparingTo("800000"); // 100만 × 80%
    }

    @Test
    void 수수료는_선지급액_일할이율_일수() {
        BigDecimal fee = policy.fee(new BigDecimal("800000"), 5); // 5일
        assertThat(fee).isEqualByComparingTo("800"); // 80만 × 0.0002 × 5
    }

    @Test
    void 등급_모르면_haircut_무변동이라_한도이내면_통과() {
        policy.validateWithinLimit(new BigDecimal("800000"), new BigDecimal("1000000"), null);
        policy.validateWithinLimit(new BigDecimal("800000"), new BigDecimal("1000000"), "A");
        // 예외 없이 통과 (A·null 은 haircut 1.0)
    }

    @Test
    void 신청액이_한도초과면_예외() {
        assertThatThrownBy(() ->
                policy.validateWithinLimit(new BigDecimal("900000"), new BigDecimal("1000000"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 평판_C등급은_한도를_15퍼_깎는다() {
        // 기본 한도 80만 × 0.85 = 68만. 70만 신청 → 초과
        assertThat(policy.creditLimit(new BigDecimal("1000000"), "C")).isEqualByComparingTo("680000");
        assertThatThrownBy(() ->
                policy.validateWithinLimit(new BigDecimal("700000"), new BigDecimal("1000000"), "C"))
                .isInstanceOf(IllegalArgumentException.class);
        // 68만 이내는 통과
        policy.validateWithinLimit(new BigDecimal("680000"), new BigDecimal("1000000"), "C");
    }

    @Test
    void 평판_E등급은_한도0_이라_어떤_신청도_차단() {
        assertThat(policy.creditLimit(new BigDecimal("1000000"), "E")).isEqualByComparingTo("0");
        assertThatThrownBy(() ->
                policy.validateWithinLimit(new BigDecimal("1"), new BigDecimal("1000000"), "E"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("등급 E");
    }

    @Test
    void 선지급일수가_음수면_예외() {
        assertThatThrownBy(() -> policy.fee(new BigDecimal("800000"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
