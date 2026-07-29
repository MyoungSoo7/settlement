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

    // ─── 중도상환수수료 (잔존기간 비례) ────────────────────────────────────────

    @Test
    void 중도상환수수료는_잔존기간에_비례한다() {
        // 1억 × 1.2% × (잔존 500 / 약정 1000) = 60만
        assertThat(policy.earlyRepaymentFee(new BigDecimal("100000000"), 500, 1000))
                .isEqualByComparingTo("600000");
    }

    @Test
    void 약정_초기_중도상환은_요율_전액에_가깝다() {
        // 잔존 990/1000 → 1억 × 1.2% × 0.99 = 1,188,000
        assertThat(policy.earlyRepaymentFee(new BigDecimal("100000000"), 990, 1000))
                .isEqualByComparingTo("1188000");
    }

    @Test
    void 만기_직전_중도상환은_수수료가_0에_수렴한다() {
        assertThat(policy.earlyRepaymentFee(new BigDecimal("100000000"), 0, 1000))
                .isEqualByComparingTo("0");
    }

    @Test
    void 경과_3년이_지나면_중도상환수수료는_면제다() {
        // 약정 7300일(20년) 중 1095일(3년) 경과 → 잔존 6205일이지만 면제
        assertThat(policy.earlyRepaymentFee(new BigDecimal("100000000"), 6205, 7300))
                .isEqualByComparingTo("0");
        // 3년에서 1일 부족하면 면제되지 않는다
        assertThat(policy.earlyRepaymentFee(new BigDecimal("100000000"), 6206, 7300))
                .isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void 잔존일수가_약정일수를_넘으면_약정일수로_clamp() {
        assertThat(policy.earlyRepaymentFee(new BigDecimal("100000000"), 2000, 1000))
                .isEqualByComparingTo("1200000");
    }

    @Test
    void 중도상환액이_0이하면_수수료는_0() {
        assertThat(policy.earlyRepaymentFee(BigDecimal.ZERO, 500, 1000)).isEqualByComparingTo("0");
        assertThat(policy.earlyRepaymentFee(new BigDecimal("-1"), 500, 1000)).isEqualByComparingTo("0");
    }

    @Test
    void 약정일수가_0이면_수수료는_0() {
        assertThat(policy.earlyRepaymentFee(new BigDecimal("100000000"), 0, 0))
                .isEqualByComparingTo("0");
    }
}
