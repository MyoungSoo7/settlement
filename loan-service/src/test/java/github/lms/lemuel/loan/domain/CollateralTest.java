package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보 도메인 규칙 — 평가액 스냅샷 보존과 설정/해지 생명주기.
 *
 * <p>평가액은 <b>설정 시점 값을 영구 보존</b>한다(정산의 {@code commission_rate} 스냅샷과 같은 이력
 * 재현성 철학). 재평가·마진콜은 Phase 2 이월이므로 이 단계에서 평가액은 변하지 않는다.
 */
class CollateralTest {

    private static final LocalDateTime APPRAISED_AT = LocalDateTime.of(2026, 7, 30, 10, 0);

    private Collateral pledged() {
        return Collateral.pledge(CollateralType.REAL_ESTATE, "서울시 강남구 테헤란로 1",
                new BigDecimal("500000000"), APPRAISED_AT);
    }

    // ─── 생성 ────────────────────────────────────────────────────────────────

    @Test
    void 신규담보는_설정중_상태다() {
        Collateral collateral = pledged();

        assertThat(collateral.getStatus()).isEqualTo(CollateralStatus.PLEDGED);
        assertThat(collateral.getType()).isEqualTo(CollateralType.REAL_ESTATE);
        assertThat(collateral.getDescription()).isEqualTo("서울시 강남구 테헤란로 1");
        assertThat(collateral.getAppraisedValue()).isEqualByComparingTo("500000000");
        assertThat(collateral.getAppraisedAt()).isEqualTo(APPRAISED_AT);
    }

    @Test
    void 평가액은_통화정책_스케일로_정규화된다() {
        assertThat(pledged().getAppraisedValue().scale()).isEqualTo(2);
    }

    @Test
    void 평가액이_0이하면_예외() {
        assertThatThrownBy(() -> Collateral.pledge(CollateralType.REAL_ESTATE, "주소",
                BigDecimal.ZERO, APPRAISED_AT))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 평가액이_음수면_예외() {
        assertThatThrownBy(() -> Collateral.pledge(CollateralType.REAL_ESTATE, "주소",
                new BigDecimal("-1"), APPRAISED_AT))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 평가액이_null_이면_예외() {
        assertThatThrownBy(() -> Collateral.pledge(CollateralType.REAL_ESTATE, "주소", null, APPRAISED_AT))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 담보유형이_null_이면_예외() {
        assertThatThrownBy(() -> Collateral.pledge(null, "주소", new BigDecimal("1000"), APPRAISED_AT))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 담보물_표시가_비어있으면_예외() {
        assertThatThrownBy(() -> Collateral.pledge(CollateralType.REAL_ESTATE, "  ",
                new BigDecimal("1000"), APPRAISED_AT))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 평가시각이_null_이면_예외() {
        assertThatThrownBy(() -> Collateral.pledge(CollateralType.REAL_ESTATE, "주소",
                new BigDecimal("1000"), null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    // ─── 유효담보가치 ─────────────────────────────────────────────────────────

    @Test
    void 유효담보가치는_평가액과_같다_선순위차감은_Phase2() {
        assertThat(pledged().effectiveValue()).isEqualByComparingTo("500000000");
    }

    // ─── 생명주기 ────────────────────────────────────────────────────────────

    @Test
    void 설정중에서_유효로_전이한다() {
        Collateral collateral = pledged();
        collateral.activate();
        assertThat(collateral.getStatus()).isEqualTo(CollateralStatus.ACTIVE);
    }

    @Test
    void 유효에서_말소로_전이한다() {
        Collateral collateral = pledged();
        collateral.activate();
        collateral.release();
        assertThat(collateral.getStatus()).isEqualTo(CollateralStatus.RELEASED);
    }

    @Test
    void 설정중에도_말소할_수_있다_대출거절시() {
        Collateral collateral = pledged();
        collateral.release();
        assertThat(collateral.getStatus()).isEqualTo(CollateralStatus.RELEASED);
    }

    @Test
    void 말소된_담보는_다시_유효화할_수_없다() {
        Collateral collateral = pledged();
        collateral.release();
        assertThatThrownBy(collateral::activate)
                .isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 말소된_담보는_다시_말소할_수_없다() {
        Collateral collateral = pledged();
        collateral.release();
        assertThatThrownBy(collateral::release)
                .isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 이미_유효한_담보는_다시_유효화할_수_없다() {
        Collateral collateral = pledged();
        collateral.activate();
        assertThatThrownBy(collateral::activate)
                .isInstanceOf(InvalidLoanStateException.class);
    }

    // ─── 재구성 ──────────────────────────────────────────────────────────────

    @Test
    void 영속상태를_재구성한다() {
        Collateral collateral = Collateral.reconstitute(9L, CollateralType.REAL_ESTATE, "주소",
                new BigDecimal("300000000.00"), APPRAISED_AT, CollateralStatus.ACTIVE);

        assertThat(collateral.getId()).isEqualTo(9L);
        assertThat(collateral.getStatus()).isEqualTo(CollateralStatus.ACTIVE);
        assertThat(collateral.getAppraisedValue()).isEqualByComparingTo("300000000");
    }
}
