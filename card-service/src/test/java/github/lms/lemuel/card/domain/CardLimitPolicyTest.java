package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 한도 산식 고정: masterLimit = floor(F x R x H), F = sellerPayable + holdbackPayable.
 * 돈 경로라 대표값·라운딩 경계·0원·최소한도 경계를 모두 고정한다(money-safety 템플릿).
 */
class CardLimitPolicyTest {

    private final CardLimitPolicy policy =
            new CardLimitPolicy(new BigDecimal("0.70"), new BigDecimal("300000"));

    @Test
    @DisplayName("대표값 — F=1,000,000, R=0.70, H=1.00(B) → 700,000")
    void representativeCase() {
        ScreeningResult r = policy.screen(
                new BigDecimal("800000"), new BigDecimal("200000"), ReputationGrade.B);

        assertThat(r.approved()).isTrue();
        assertThat(r.masterLimit()).isEqualByComparingTo("700000");
        assertThat(r.snapshot().funding()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("C등급 haircut 0.85 적용 — F=1,000,000 → 595,000")
    void gradeCHaircut() {
        ScreeningResult r = policy.screen(
                new BigDecimal("1000000"), BigDecimal.ZERO, ReputationGrade.C);
        assertThat(r.masterLimit()).isEqualByComparingTo("595000");
    }

    @Test
    @DisplayName("floor — 소수는 버린다. 원 단위 아래로 한도를 주지 않는다")
    void floorsToWon() {
        // 1,000,001 x 0.70 = 700,000.7 → 700,000
        ScreeningResult r = policy.screen(
                new BigDecimal("1000001"), BigDecimal.ZERO, ReputationGrade.A);
        assertThat(r.masterLimit()).isEqualByComparingTo("700000");
    }

    @Test
    @DisplayName("E등급은 haircut 0 → 산식이 0 을 내고 탈락한다")
    void gradeERejected() {
        ScreeningResult r = policy.screen(
                new BigDecimal("100000000"), BigDecimal.ZERO, ReputationGrade.E);

        assertThat(r.approved()).isFalse();
        assertThat(r.masterLimit()).isEqualByComparingTo("0");
        assertThat(r.rejectReason()).contains("평판");
    }

    @Test
    @DisplayName("최소한도 미달은 탈락 — 경계 바로 아래")
    void belowMinimumRejected() {
        // F=428,570 x 0.7 = 299,999 → 300,000 미만
        ScreeningResult r = policy.screen(
                new BigDecimal("428570"), BigDecimal.ZERO, ReputationGrade.A);

        assertThat(r.approved()).isFalse();
        assertThat(r.rejectReason()).contains("최소");
    }

    @Test
    @DisplayName("최소한도와 정확히 같으면 승인 — 경계값")
    void exactMinimumApproved() {
        // F=428,572 x 0.7 = 300,000.4 → floor 300,000
        ScreeningResult r = policy.screen(
                new BigDecimal("428572"), BigDecimal.ZERO, ReputationGrade.A);

        assertThat(r.approved()).isTrue();
        assertThat(r.masterLimit()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("재원 0원은 탈락")
    void zeroFundingRejected() {
        ScreeningResult r = policy.screen(BigDecimal.ZERO, BigDecimal.ZERO, ReputationGrade.A);
        assertThat(r.approved()).isFalse();
    }

    @Test
    @DisplayName("홀드백만 있어도 재원으로 인정된다 — 유보금도 셀러 몫이다")
    void holdbackOnlyCountsAsFunding() {
        ScreeningResult r = policy.screen(BigDecimal.ZERO, new BigDecimal("1000000"), ReputationGrade.A);
        assertThat(r.approved()).isTrue();
        assertThat(r.masterLimit()).isEqualByComparingTo("700000");
    }

    @Test
    @DisplayName("승인·탈락 어느 쪽이든 근거 스냅샷은 남는다")
    void snapshotAlwaysPreserved() {
        ScreeningResult rejected = policy.screen(BigDecimal.ZERO, BigDecimal.ZERO, ReputationGrade.E);
        assertThat(rejected.snapshot()).isNotNull();
        assertThat(rejected.snapshot().reputationGrade()).isEqualTo(ReputationGrade.E);
        assertThat(rejected.snapshot().appliedRatio()).isEqualByComparingTo("0.70");
    }

    @Test
    @DisplayName("음수 재원 합계는 0 으로 본다 — 회계상 음수 잔액이 한도를 만들지 않는다 (홀드백이 0 이라 합계 자체가 음수)")
    void negativeFundingTreatedAsZero() {
        ScreeningResult r = policy.screen(
                new BigDecimal("-500000"), BigDecimal.ZERO, ReputationGrade.A);
        assertThat(r.approved()).isFalse();
        assertThat(r.masterLimit()).isEqualByComparingTo("0");
        // 스냅샷은 원본 부호값을 그대로 보존한다 — 근거 재현을 위해 클램프하지 않는다.
        assertThat(r.snapshot().sellerPayable()).isEqualByComparingTo("-500000");
    }

    @Test
    @DisplayName("음수 sellerPayable 이 있어도 클램프는 합계에만 적용한다 — 각 항을 개별로 0 바닥치지 않는다 (회귀, I1)")
    void negativeSellerPayableClampedOnSumNotPerField() {
        // sellerPayable=-500000(과지급), holdbackPayable=1,000,000 이면 진짜 재원은 500,000.
        // 각 항을 개별로 0 바닥치면(구 버전 버그) 0+1,000,000=1,000,000 으로 과대 산정된다.
        ScreeningResult r = policy.screen(
                new BigDecimal("-500000"), new BigDecimal("1000000"), ReputationGrade.A);

        assertThat(r.approved()).isTrue();
        // F = max(0, -500000 + 1000000) = 500000 → 500000 x 0.70 = 350000
        assertThat(r.masterLimit()).isEqualByComparingTo("350000");
        // 스냅샷은 원본 부호값을 그대로 보존한다 — 원장으로 한도를 재현할 수 있어야 한다.
        assertThat(r.snapshot().sellerPayable()).isEqualByComparingTo("-500000");
        assertThat(r.snapshot().holdbackPayable()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("인정비율이 0 이하이거나 1 초과면 생성 시점에 거부한다")
    void invalidRecognitionRatioRejected() {
        assertThat(catchThrowable(() -> new CardLimitPolicy(BigDecimal.ZERO, BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new CardLimitPolicy(new BigDecimal("1.01"), BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최소한도가 음수면 생성 시점에 거부한다")
    void invalidMinimumLimitRejected() {
        assertThat(catchThrowable(
                () -> new CardLimitPolicy(new BigDecimal("0.70"), new BigDecimal("-1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("평판 등급이 null 이면 NPE(Objects.requireNonNull, 명시적 계약 위반 메시지 포함)로 거부한다")
    void nullGradeRejected() {
        assertThat(catchThrowable(
                () -> policy.screen(new BigDecimal("1000000"), BigDecimal.ZERO, null)))
                .isInstanceOf(NullPointerException.class);
    }
}
