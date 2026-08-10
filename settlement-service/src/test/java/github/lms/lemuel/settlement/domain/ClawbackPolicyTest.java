package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 대사 차이 → 셀러 회수액 산정 규칙.
 *
 * <p>회수는 셀러에게서 돈을 도로 가져오는 방향이라, "회수하지 않는다"가 기본이고 회수 대상은
 * 명시적으로 열거된 경우뿐이다. 잘못 회수하면 셀러 손실이고 되돌리기도 번거롭다.
 */
class ClawbackPolicyTest {

    private static BigDecimal amount(String v) {
        return new BigDecimal(v);
    }

    @Test @DisplayName("금액 불일치: PG 가 내부보다 적으면 그 차액을 회수한다(셀러 과다 정산)")
    void amountMismatch_pgLess_clawsBackDifference() {
        // difference = pgAmount - internalAmount = -300
        assertThat(ClawbackPolicy.computeFor("AMOUNT_MISMATCH", amount("10000"), amount("-300")))
                .isEqualByComparingTo("300");
    }

    @Test @DisplayName("금액 불일치: PG 가 더 많거나 같으면 회수하지 않는다 — 과소 정산은 회수 대상이 아니다")
    void amountMismatch_pgMoreOrEqual_noClawback() {
        assertThat(ClawbackPolicy.computeFor("AMOUNT_MISMATCH", amount("10000"), amount("300"))).isNull();
        assertThat(ClawbackPolicy.computeFor("AMOUNT_MISMATCH", amount("10000"), BigDecimal.ZERO)).isNull();
    }

    @Test @DisplayName("PG 미송금: 내부 금액 전액을 회수한다")
    void missingPg_clawsBackInternalAmount() {
        assertThat(ClawbackPolicy.computeFor("MISSING_PG", amount("10000"), null))
                .isEqualByComparingTo("10000");
    }

    @Test @DisplayName("PG 미송금인데 내부 금액이 0 이하면 회수할 것이 없다")
    void missingPg_nonPositiveInternal_noClawback() {
        assertThat(ClawbackPolicy.computeFor("MISSING_PG", BigDecimal.ZERO, null)).isNull();
        assertThat(ClawbackPolicy.computeFor("MISSING_PG", null, null)).isNull();
    }

    @Test @DisplayName("수수료 불일치는 회수하지 않는다 — PG 측 오차를 셀러에게 전가하지 않는다")
    void feeMismatch_noClawback() {
        assertThat(ClawbackPolicy.computeFor("FEE_MISMATCH", amount("10000"), amount("-300"))).isNull();
    }

    @Test @DisplayName("나머지 유형과 미상 유형은 회수 대상이 아니다(기본은 회수하지 않음)")
    void otherTypes_noClawback() {
        for (String type : new String[]{"MISSING_INTERNAL", "DUPLICATE", "ROUNDING_DIFF", "WHAT_IS_THIS", null}) {
            assertThat(ClawbackPolicy.computeFor(type, amount("10000"), amount("-300")))
                    .as("type=%s", type).isNull();
        }
    }

    @Test @DisplayName("회수액은 항상 양수다 — 음수 회수는 지급이 되어버린다")
    void clawbackIsAlwaysPositive() {
        assertThat(ClawbackPolicy.computeFor("AMOUNT_MISMATCH", amount("10000"), amount("-300")).signum())
                .isPositive();
        assertThat(ClawbackPolicy.computeFor("MISSING_PG", amount("10000"), null).signum())
                .isPositive();
    }
}
