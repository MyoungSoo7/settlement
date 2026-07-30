package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보권 순위 — 선순위 채권을 차감한 유효담보가치 (Phase 2).
 *
 * <p>Phase 1 은 선순위가 없다고 전제해 유효담보가치 = 평가액이었다. 실제 부동산에는 앞선 근저당이
 * 붙어 있는 경우가 흔하고, 그 금액을 빼지 않으면 <b>이미 남이 가진 담보력을 우리 한도로 계산</b>하게 된다.
 *
 * <p>중요한 설계 확인: 이 변경으로 {@code effectiveValue()} 의 계산은 바뀌지만 <b>호출 측(정책)은
 * 수정되지 않는다</b> — Phase 1 에서 LTV 계산을 담보가 아니라 정책에 두고, 담보는 "얼마짜리인가"만
 * 노출하게 나눠 둔 경계가 실제로 작동하는지 여기서 검증된다.
 */
class CollateralSeniorClaimTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 7, 30, 10, 0);

    private static Collateral withSenior(String appraised, String senior) {
        return Collateral.pledge(CollateralType.REAL_ESTATE, "서울시 강남구",
                new BigDecimal(appraised), new BigDecimal(senior), AT);
    }

    @Test
    void 유효담보가치는_평가액에서_선순위를_뺀다() {
        assertThat(withSenior("500000000", "200000000").effectiveValue())
                .isEqualByComparingTo("300000000");
    }

    @Test
    void 선순위가_0이면_평가액_전액이_유효하다() {
        assertThat(withSenior("500000000", "0").effectiveValue())
                .isEqualByComparingTo("500000000");
    }

    @Test
    void 선순위가_평가액과_같으면_유효담보가치는_0() {
        assertThat(withSenior("500000000", "500000000").effectiveValue())
                .isEqualByComparingTo("0");
    }

    @Test
    void 선순위가_평가액을_넘으면_유효담보가치는_음수가_아니라_0() {
        // 담보가치가 마이너스가 될 수는 없다 — 초과분은 우리 채권과 무관하다.
        assertThat(withSenior("300000000", "500000000").effectiveValue())
                .isEqualByComparingTo("0");
    }

    @Test
    void 선순위는_음수일_수_없다() {
        assertThatThrownBy(() -> withSenior("500000000", "-1"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 선순위를_생략한_기존_생성경로는_0으로_취급한다() {
        Collateral collateral = Collateral.pledge(CollateralType.REAL_ESTATE, "서울시 강남구",
                new BigDecimal("500000000"), AT);

        assertThat(collateral.getSeniorClaimAmount()).isEqualByComparingTo("0");
        assertThat(collateral.effectiveValue()).isEqualByComparingTo("500000000");
    }

    @Test
    void 선순위도_통화정책_스케일로_정규화된다() {
        assertThat(withSenior("500000000", "200000000").getSeniorClaimAmount().scale()).isEqualTo(2);
    }

    // ─── 정책과의 경계: 담보가 계산을 바꿔도 정책 코드는 그대로다 ─────────────────

    @Test
    void 선순위가_있으면_LTV한도가_그만큼_줄어든다() {
        SecuredLoanPolicy policy = new SecuredLoanPolicy(new BigDecimal("3.5"), new BigDecimal("0.70"));

        // 선순위 2억 → 유효담보가치 3억 → 한도 3억 × 0.70 = 2.1억
        assertThat(policy.mortgageLimit(withSenior("500000000", "200000000").effectiveValue(),
                CollateralType.REAL_ESTATE)).isEqualByComparingTo("210000000");
    }
}
