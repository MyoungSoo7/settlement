package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D7 — ACTIVE → LAPSED 전이 (납입 실패) 전용 테스트.
 *
 * <p>핵심 규칙: 납입 실패 1회로는 전이되지 않고, 2회 연속 실패에서만 LAPSED 로 전이된다.
 */
@DisplayName("납입 실패 → 실효(LAPSED) 전이")
class LapseTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2024, 1, 1);
    private static final LocalDate MATURITY   = LocalDate.of(2044, 1, 1);

    private Policy activePolicy() {
        return Policy.builder()
                .policyNumber("POL-LAPSE-001")
                .status(PolicyStatus.ACTIVE)
                .effectiveDate(EFFECTIVE)
                .maturityDate(MATURITY)
                .consecutivePremiumFailures(0)
                .premiumAmount(new BigDecimal("100000"))
                .fcId("FC-001")
                .build();
    }

    @Test
    @DisplayName("납입 실패 1회 → 상태 ACTIVE 유지, 미전이")
    void 납입_실패_1회_ACTIVE_유지() {
        Policy policy = activePolicy();
        LocalDate today = EFFECTIVE.plusDays(30);

        boolean transitioned = policy.recordPremiumFailure(today);

        assertThat(transitioned).isFalse();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(policy.getConsecutivePremiumFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("납입 실패 2회 연속 → LAPSED 전이, lapsedAt 설정")
    void 납입_실패_2회_연속_LAPSED_전이() {
        Policy policy = activePolicy();
        LocalDate failDate1 = EFFECTIVE.plusDays(30);
        LocalDate failDate2 = EFFECTIVE.plusDays(60);

        policy.recordPremiumFailure(failDate1);
        boolean transitioned = policy.recordPremiumFailure(failDate2);

        assertThat(transitioned).isTrue();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.LAPSED);
        assertThat(policy.getLapsedAt()).isEqualTo(failDate2);
        assertThat(policy.getConsecutivePremiumFailures()).isEqualTo(2);
    }

    @Test
    @DisplayName("납입 성공 후 실패 1회 → 카운터 리셋, ACTIVE 유지")
    void 납입_성공후_실패1회_ACTIVE_유지() {
        Policy policy = activePolicy();
        LocalDate failDate = EFFECTIVE.plusDays(30);

        // 1회 실패
        policy.recordPremiumFailure(failDate);
        // 납입 성공 → 카운터 리셋
        policy.recordPremiumSuccess();
        // 다시 1회 실패
        boolean transitioned = policy.recordPremiumFailure(failDate.plusDays(30));

        assertThat(transitioned).isFalse();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(policy.getConsecutivePremiumFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("납입 성공이 연속 실패 카운터를 0으로 리셋한다")
    void 납입_성공_카운터_리셋() {
        Policy policy = activePolicy();

        policy.recordPremiumFailure(EFFECTIVE.plusDays(30));
        assertThat(policy.getConsecutivePremiumFailures()).isEqualTo(1);

        policy.recordPremiumSuccess();
        assertThat(policy.getConsecutivePremiumFailures()).isEqualTo(0);
    }

    @Test
    @DisplayName("LAPSED 상태에서 납입 실패 기록 시도 → 예외")
    void LAPSED_상태에서_납입_실패_기록_예외() {
        Policy policy = activePolicy();
        LocalDate failDate = EFFECTIVE.plusDays(30);
        policy.recordPremiumFailure(failDate);
        policy.recordPremiumFailure(failDate.plusDays(30)); // → LAPSED

        assertThatThrownBy(() -> policy.recordPremiumFailure(failDate.plusDays(60)))
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }
}
