package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 정책 — 담보유형별 인정비율 · 보증료 · 담보유지비율(마진콜) · 중도상환수수료.
 *
 * <p>Phase 1 과 마찬가지로 전부 구간·비율 기반 결정적 계산이라 <b>경계값을 전수 검증</b>한다.
 * 특히 마진콜/반대매매 임계는 한 칸 어긋나면 정상 대출을 강제 청산하거나 부실을 방치하므로
 * 임계값과 임계값±1 을 모두 못 박는다.
 */
class SecuredLoanPolicyPhase2Test {

    private final SecuredLoanPolicy policy =
            new SecuredLoanPolicy(new BigDecimal("3.5"), new BigDecimal("0.70"));

    // ─── 담보유형별 인정비율 ───────────────────────────────────────────────────

    @Test
    void 담보유형별_인정비율() {
        assertThat(policy.ltvRatio(CollateralType.REAL_ESTATE)).isEqualByComparingTo("0.70");
        assertThat(policy.ltvRatio(CollateralType.GUARANTEE)).isEqualByComparingTo("1.00");
        assertThat(policy.ltvRatio(CollateralType.DEPOSIT)).isEqualByComparingTo("0.95");
        assertThat(policy.ltvRatio(CollateralType.BOND)).isEqualByComparingTo("0.80");
        assertThat(policy.ltvRatio(CollateralType.EQUITY)).isEqualByComparingTo("0.60");
    }

    @Test
    void 부동산_인정비율은_주입값을_따른다() {
        SecuredLoanPolicy tightened =
                new SecuredLoanPolicy(new BigDecimal("3.5"), new BigDecimal("0.50"));
        assertThat(tightened.ltvRatio(CollateralType.REAL_ESTATE)).isEqualByComparingTo("0.50");
        // 나머지 유형은 정책 상수라 주입값에 영향받지 않는다.
        assertThat(tightened.ltvRatio(CollateralType.EQUITY)).isEqualByComparingTo("0.60");
    }

    @Test
    void 보증서는_보증금액_전액이_한도다() {
        assertThat(policy.collateralLimit(new BigDecimal("100000000"), CollateralType.GUARANTEE))
                .isEqualByComparingTo("100000000");
    }

    @Test
    void 주식담보는_시가의_60퍼센트만_한도다() {
        assertThat(policy.collateralLimit(new BigDecimal("100000000"), CollateralType.EQUITY))
                .isEqualByComparingTo("60000000");
    }

    // ─── 보증료 ──────────────────────────────────────────────────────────────

    @Test
    void 보증료는_원금_곱하기_요율_곱하기_연수() {
        // 1억 × 1.2% × (12/12) = 120만
        assertThat(policy.guaranteeFee(new BigDecimal("100000000"), 12))
                .isEqualByComparingTo("1200000");
    }

    @Test
    void 보증료는_기간에_비례한다() {
        // 1억 × 1.2% × (36/12) = 360만
        assertThat(policy.guaranteeFee(new BigDecimal("100000000"), 36))
                .isEqualByComparingTo("3600000");
        // 6개월 = 60만
        assertThat(policy.guaranteeFee(new BigDecimal("100000000"), 6))
                .isEqualByComparingTo("600000");
    }

    @Test
    void 보증비율은_85퍼센트다() {
        assertThat(policy.guaranteeRatio()).isEqualByComparingTo("0.85");
    }

    @Test
    void 대위변제_회수액은_잔액의_보증비율만큼이다() {
        // 잔액 1억 × 85% = 8500만 회수, 미보증 1500만은 손실로 남는다
        assertThat(policy.subrogationRecovery(new BigDecimal("100000000")))
                .isEqualByComparingTo("85000000");
    }

    // ─── 담보유지비율 · 마진콜 (경계값) ────────────────────────────────────────

    @Test
    void 담보유지비율은_유효담보가치_나누기_잔액이다() {
        assertThat(policy.coverageRatio(new BigDecimal("150000000"), new BigDecimal("100000000")))
                .isEqualByComparingTo("1.50");
    }

    @Test
    void 잔액이_0이면_담보유지비율은_무한대로_보고_조치하지_않는다() {
        assertThat(policy.requiresMarginCall(BigDecimal.ZERO, BigDecimal.ZERO)).isFalse();
        assertThat(policy.requiresLiquidation(BigDecimal.ZERO, BigDecimal.ZERO)).isFalse();
    }

    @Test
    void 마진콜_임계_경계값_140퍼센트() {
        BigDecimal outstanding = new BigDecimal("100000000");
        // 정확히 140% → 아직 마진콜 아님
        assertThat(policy.requiresMarginCall(new BigDecimal("140000000"), outstanding)).isFalse();
        // 140% 미달 → 마진콜
        assertThat(policy.requiresMarginCall(new BigDecimal("139999999"), outstanding)).isTrue();
    }

    @Test
    void 반대매매_임계_경계값_120퍼센트() {
        BigDecimal outstanding = new BigDecimal("100000000");
        assertThat(policy.requiresLiquidation(new BigDecimal("120000000"), outstanding)).isFalse();
        assertThat(policy.requiresLiquidation(new BigDecimal("119999999"), outstanding)).isTrue();
    }

    @Test
    void 반대매매_구간은_마진콜_구간에도_포함된다() {
        // 110% 는 두 임계를 모두 밑돌므로 마진콜이면서 반대매매 대상이다.
        BigDecimal outstanding = new BigDecimal("100000000");
        assertThat(policy.requiresMarginCall(new BigDecimal("110000000"), outstanding)).isTrue();
        assertThat(policy.requiresLiquidation(new BigDecimal("110000000"), outstanding)).isTrue();
    }

    @Test
    void 추가담보_요구액은_마진콜_임계를_회복하는_금액이다() {
        // 잔액 1억, 유효담보 1.2억 → 임계 1.4억까지 2천만 부족
        assertThat(policy.marginCallShortfall(new BigDecimal("120000000"), new BigDecimal("100000000")))
                .isEqualByComparingTo("20000000");
    }

    @Test
    void 임계를_충족하면_추가담보_요구액은_0() {
        assertThat(policy.marginCallShortfall(new BigDecimal("150000000"), new BigDecimal("100000000")))
                .isEqualByComparingTo("0");
    }

    // ─── 중도상환수수료 (부과기간 1095일 잔여 비례) ─────────────────────────────
    // 분모가 약정 전체가 아니라 부과기간(면제 도달까지의 3년)이다 — 약정 전체를 분모로 쓰면
    // 장기(360개월) 상품에서 taper 가 사실상 작동하지 않고, 면제 직전 1일 사이에
    // 수백만원의 계단이 생긴다(2026-07-30 원장 감사 R-1로 확정된 정본).

    @Test
    void 실행_당일_중도상환은_요율_전액이다() {
        // 경과 0 → 1억 × 1.2% × 1095/1095 = 1,200,000
        assertThat(SecuredLoanPolicy.earlyRepaymentFee(new BigDecimal("100000000"), 0))
                .isEqualByComparingTo("1200000");
    }

    @Test
    void 수수료는_부과기간_잔여일수에_비례한다() {
        // 경과 365 → 잔여 730/1095(=2/3) → 1억 × 1.2% × 2/3 = 800,000
        assertThat(SecuredLoanPolicy.earlyRepaymentFee(new BigDecimal("100000000"), 365))
                .isEqualByComparingTo("800000");
    }

    @Test
    void 면제_직전에는_수수료가_0에_수렴한다() {
        // 경과 1094 → 잔여 1/1095 → 1,200,000/1095 = 1,095.89 — 면제 직전 1일 계단이 없다.
        // (구 산식은 여기서 약 108만원이 나와 하루 차이로 수백만원 계단이 생겼다 — 회귀 가드)
        assertThat(SecuredLoanPolicy.earlyRepaymentFee(new BigDecimal("100000000"), 1094))
                .isEqualByComparingTo("1095.89");
    }

    @Test
    void 경과_3년이면_면제다() {
        assertThat(SecuredLoanPolicy.earlyRepaymentFee(new BigDecimal("100000000"), 1095))
                .isEqualByComparingTo("0");
        assertThat(SecuredLoanPolicy.earlyRepaymentFee(new BigDecimal("100000000"), 1096))
                .isEqualByComparingTo("0");
    }

    @Test
    void 경과일수가_음수면_0으로_clamp_한다() {
        // 시계 역행 등 비정상 입력 — 부과기간 전체가 남은 것으로 보고 요율 전액.
        assertThat(SecuredLoanPolicy.earlyRepaymentFee(new BigDecimal("100000000"), -1))
                .isEqualByComparingTo("1200000");
    }

    @Test
    void 중도상환액이_0이하면_수수료는_0() {
        assertThat(SecuredLoanPolicy.earlyRepaymentFee(BigDecimal.ZERO, 500)).isEqualByComparingTo("0");
        assertThat(SecuredLoanPolicy.earlyRepaymentFee(new BigDecimal("-1"), 500)).isEqualByComparingTo("0");
    }
}
