package github.lms.lemuel.loan.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditPolicyTest {

    private final CreditPolicy policy = new CreditPolicy(
            new BigDecimal("0.80"),     // LTV 80%
            new BigDecimal("0.0002"));  // 일할이율 0.02%/day

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
    void 신청액이_한도이내면_통과() {
        policy.validateWithinLimit(new BigDecimal("800000"), new BigDecimal("1000000"));
        // 예외 없이 통과
    }

    @Test
    void 신청액이_한도초과면_예외() {
        assertThatThrownBy(() ->
                policy.validateWithinLimit(new BigDecimal("900000"), new BigDecimal("1000000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 선지급일수가_음수면_예외() {
        assertThatThrownBy(() -> policy.fee(new BigDecimal("800000"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
