package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.SecuredLoanRejectedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보/개인신용 대출 심사 정책 — 결정적 순수 계산.
 *
 * <p>모든 매핑이 구간(band) 기반이라 <b>경계값을 전수 검증</b>한다({@link CorporateCreditPolicy} 선례와
 * 동형). 구간 경계는 정책 변경이 가장 자주 일어나는 지점이고, 한 칸 어긋나면 실제 돈이 잘못 나가므로
 * 각 밴드의 경계와 경계-1 을 모두 못 박는다.
 */
class SecuredLoanPolicyTest {

    /** 기준금리 3.5%, 주택담보 LTV 70%. */
    private final SecuredLoanPolicy policy =
            new SecuredLoanPolicy(new BigDecimal("3.5"), new BigDecimal("0.70"));

    // ─── CB 점수 → 등급 (경계값 전수) ──────────────────────────────────────────

    @Test
    void CB점수_등급_경계값() {
        assertThat(policy.creditGrade(1000)).isEqualTo("A");
        assertThat(policy.creditGrade(850)).isEqualTo("A");
        assertThat(policy.creditGrade(849)).isEqualTo("B");
        assertThat(policy.creditGrade(750)).isEqualTo("B");
        assertThat(policy.creditGrade(749)).isEqualTo("C");
        assertThat(policy.creditGrade(650)).isEqualTo("C");
        assertThat(policy.creditGrade(649)).isEqualTo("D");
        assertThat(policy.creditGrade(550)).isEqualTo("D");
        assertThat(policy.creditGrade(549)).isEqualTo("E");
        assertThat(policy.creditGrade(0)).isEqualTo("E");
    }

    @Test
    void E등급은_대출_불가() {
        assertThat(policy.isLoanBlocked("E")).isTrue();
        assertThat(policy.isLoanBlocked("D")).isFalse();
        assertThat(policy.isLoanBlocked("A")).isFalse();
    }

    @Test
    void 미상등급은_보수적으로_차단한다() {
        assertThat(policy.isLoanBlocked(null)).isTrue();
    }

    // ─── 주택담보 한도 ────────────────────────────────────────────────────────

    @Test
    void 주택담보한도는_유효담보가치_곱하기_LTV() {
        assertThat(policy.mortgageLimit(new BigDecimal("500000000"), CollateralType.REAL_ESTATE))
                .isEqualByComparingTo("350000000");
    }

    @Test
    void 주택담보한도는_스케일2로_반올림된다() {
        assertThat(policy.mortgageLimit(new BigDecimal("1000001"), CollateralType.REAL_ESTATE).scale())
                .isEqualTo(2);
    }

    @Test
    void 유효담보가치가_0이하면_한도는_0() {
        assertThat(policy.mortgageLimit(BigDecimal.ZERO, CollateralType.REAL_ESTATE))
                .isEqualByComparingTo("0");
        assertThat(policy.mortgageLimit(new BigDecimal("-1"), CollateralType.REAL_ESTATE))
                .isEqualByComparingTo("0");
    }

    @Test
    void 유효담보가치가_null_이면_한도는_0() {
        assertThat(policy.mortgageLimit(null, CollateralType.REAL_ESTATE)).isEqualByComparingTo("0");
    }

    // ─── 개인신용 한도 (등급별 정액) ──────────────────────────────────────────

    @Test
    void 개인신용한도는_등급별_정액이다() {
        assertThat(policy.personalCreditLimit("A")).isEqualByComparingTo("100000000");
        assertThat(policy.personalCreditLimit("B")).isEqualByComparingTo("50000000");
        assertThat(policy.personalCreditLimit("C")).isEqualByComparingTo("30000000");
        assertThat(policy.personalCreditLimit("D")).isEqualByComparingTo("10000000");
        assertThat(policy.personalCreditLimit("E")).isEqualByComparingTo("0");
    }

    @Test
    void 미상등급의_개인신용한도는_0() {
        assertThat(policy.personalCreditLimit(null)).isEqualByComparingTo("0");
        assertThat(policy.personalCreditLimit("Z")).isEqualByComparingTo("0");
    }

    // ─── 금리 = 기준금리 + 가산금리 ───────────────────────────────────────────

    @Test
    void 담보형_금리는_기준금리에_고정가산() {
        assertThat(policy.annualRatePercent(LoanProductType.MORTGAGE, null))
                .isEqualByComparingTo("4.30");
    }

    @Test
    void 신용형_금리는_기준금리에_등급별_가산() {
        assertThat(policy.annualRatePercent(LoanProductType.PERSONAL_CREDIT, "A"))
                .isEqualByComparingTo("5.00");
        assertThat(policy.annualRatePercent(LoanProductType.PERSONAL_CREDIT, "B"))
                .isEqualByComparingTo("6.00");
        assertThat(policy.annualRatePercent(LoanProductType.PERSONAL_CREDIT, "C"))
                .isEqualByComparingTo("7.50");
        assertThat(policy.annualRatePercent(LoanProductType.PERSONAL_CREDIT, "D"))
                .isEqualByComparingTo("9.50");
    }

    @Test
    void 기준금리가_바뀌면_전_상품_금리가_따라간다() {
        SecuredLoanPolicy raised = new SecuredLoanPolicy(new BigDecimal("5.0"), new BigDecimal("0.70"));
        assertThat(raised.annualRatePercent(LoanProductType.MORTGAGE, null)).isEqualByComparingTo("5.80");
        assertThat(raised.annualRatePercent(LoanProductType.PERSONAL_CREDIT, "B"))
                .isEqualByComparingTo("7.50");
    }

    // ─── 한도 검증 ────────────────────────────────────────────────────────────

    @Test
    void 신청액이_한도_이내면_통과한다() {
        assertThatCode(() -> policy.validateWithinLimit(new BigDecimal("350000000"),
                new BigDecimal("350000000"))).doesNotThrowAnyException();
    }

    @Test
    void 신청액이_한도를_넘으면_거절한다() {
        assertThatThrownBy(() -> policy.validateWithinLimit(new BigDecimal("350000001"),
                new BigDecimal("350000000")))
                .isInstanceOf(SecuredLoanRejectedException.class);
    }

    @Test
    void 한도초과_거절은_신청액과_한도를_구조적으로_보존한다() {
        assertThatThrownBy(() -> policy.validateWithinLimit(new BigDecimal("500"), new BigDecimal("100")))
                .isInstanceOfSatisfying(SecuredLoanRejectedException.class, ex -> {
                    assertThat(ex.getRequested()).isEqualByComparingTo("500");
                    assertThat(ex.getLimit()).isEqualByComparingTo("100");
                });
    }

    @Test
    void 한도가_0이면_어떤_신청도_거절된다() {
        assertThatThrownBy(() -> policy.validateWithinLimit(new BigDecimal("1"), BigDecimal.ZERO))
                .isInstanceOf(SecuredLoanRejectedException.class);
    }
}
