package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BancaRuleViolation;
import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BankInsurerPremium;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방카 25%룰 판정 도메인 테스트 — 경계값(정확히 25%)이 핵심.
 */
@DisplayName("BancaRuleEvaluator — 방카 25%룰")
class BancaRuleEvaluatorTest {

    private static BankInsurerPremium premium(String bank, String insurer, String amount) {
        return new BankInsurerPremium(bank, insurer, new BigDecimal(amount));
    }

    @Test
    @DisplayName("특정 원수사 비중이 25% 를 초과하면 위반이다")
    void detectsShareAboveLimit() {
        // BANK-KB 총 100만: INS-A 40만(40% → 위반) / INS-B·C·D 각 20만(20% → 정상)
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-KB", "INS-A", "400000.00"),
                premium("BANK-KB", "INS-B", "200000.00"),
                premium("BANK-KB", "INS-C", "200000.00"),
                premium("BANK-KB", "INS-D", "200000.00")));

        assertThat(violations).hasSize(1);
        BancaRuleViolation v = violations.get(0);
        assertThat(v.bankCode()).isEqualTo("BANK-KB");
        assertThat(v.insurerCode()).isEqualTo("INS-A");
        assertThat(v.share()).isEqualByComparingTo(new BigDecimal("0.4000"));
        assertThat(v.bankPremiumTotal()).isEqualByComparingTo(new BigDecimal("1000000.00"));
    }

    @Test
    @DisplayName("정확히 25% 는 허용이다 — 상한은 초과 기준")
    void allowsExactlyTwentyFivePercent() {
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-KB", "INS-A", "250000.00"),
                premium("BANK-KB", "INS-B", "250000.00"),
                premium("BANK-KB", "INS-C", "250000.00"),
                premium("BANK-KB", "INS-D", "250000.00")));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("은행별로 독립 판정한다 — 한 은행의 위반이 다른 은행에 전이되지 않는다")
    void evaluatesPerBank() {
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-KB", "INS-A", "900000.00"),   // KB: INS-A 90% → 위반
                premium("BANK-KB", "INS-B", "100000.00"),
                premium("BANK-SH", "INS-A", "200000.00"),   // SH: INS-A 20% → 정상
                premium("BANK-SH", "INS-B", "800000.00")));  // SH: INS-B 80% → 위반

        assertThat(violations).extracting(v -> v.bankCode() + "/" + v.insurerCode())
                .containsExactlyInAnyOrder("BANK-KB/INS-A", "BANK-SH/INS-B");
    }

    @Test
    @DisplayName("단일 원수사만 파는 은행은 100% 로 위반이다")
    void singleInsurerBankIsAlwaysViolation() {
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-MONO", "INS-A", "10000.00")));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).share()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("집계가 비면 위반도 없다")
    void emptyInputYieldsNoViolations() {
        assertThat(BancaRuleEvaluator.evaluate(List.of())).isEmpty();
    }

    @Test
    @DisplayName("비중은 소수 4자리 HALF_UP — 1/3 은 0.3333")
    void roundsShareToFourDecimals() {
        List<BancaRuleViolation> violations = BancaRuleEvaluator.evaluate(List.of(
                premium("BANK-KB", "INS-A", "100.00"),
                premium("BANK-KB", "INS-B", "100.00"),
                premium("BANK-KB", "INS-C", "100.00")));

        assertThat(violations).hasSize(3);
        assertThat(violations.get(0).share()).isEqualByComparingTo(new BigDecimal("0.3333"));
    }
}
