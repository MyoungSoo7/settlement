package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D7 — LAPSED → ACTIVE 부활(reinstatement) 전이 전용 테스트.
 *
 * <p>핵심 규칙:
 * <ul>
 *   <li>실효일로부터 24개월 이내 + 연체보험료 전액 납입 시 부활 가능</li>
 *   <li>24개월 경과 후 부활 시도 → {@link InvalidPolicyTransitionException}</li>
 *   <li>부활 성공 시 consecutivePremiumFailures 가 0으로 리셋된다</li>
 * </ul>
 */
@DisplayName("부활(Reinstatement) — LAPSED → ACTIVE")
class ReinstateTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2024, 1, 1);
    private static final LocalDate LAPSED_AT = LocalDate.of(2024, 6, 1);

    /** 실효 상태(LAPSED)의 Policy. */
    private Policy lapsedPolicy() {
        return Policy.builder()
                .policyNumber("POL-REINSTATE-001")
                .status(PolicyStatus.LAPSED)
                .effectiveDate(EFFECTIVE)
                .maturityDate(LocalDate.of(2044, 1, 1))
                .lapsedAt(LAPSED_AT)
                .consecutivePremiumFailures(2)
                .premiumAmount(new BigDecimal("100000"))
                .fcId("FC-001")
                .build();
    }

    @Test
    @DisplayName("실효일로부터 23개월 후 부활 → ACTIVE 전이, 카운터 0 리셋")
    void 실효_23개월후_부활_성공() {
        Policy policy = lapsedPolicy();
        LocalDate reinstateDate = LAPSED_AT.plusMonths(23);

        policy.reinstate(reinstateDate);

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(policy.getConsecutivePremiumFailures()).isEqualTo(0);
    }

    @Test
    @DisplayName("실효일과 동일 날짜 부활 → ACTIVE 전이 (경계값: 0개월)")
    void 실효_당일_부활_성공() {
        Policy policy = lapsedPolicy();

        policy.reinstate(LAPSED_AT);

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    @DisplayName("실효일로부터 정확히 24개월 후 부활 → 예외 (경계값: 창구 초과)")
    void 실효_24개월_경계_부활_실패() {
        Policy policy = lapsedPolicy();
        LocalDate reinstateDate = LAPSED_AT.plusMonths(24); // exactly 24 months → NOT allowed

        assertThatThrownBy(() -> policy.reinstate(reinstateDate))
                .isInstanceOf(InvalidPolicyTransitionException.class)
                .hasMessageContaining("24개월");
    }

    @Test
    @DisplayName("실효일로부터 25개월 후 부활 → 예외")
    void 실효_25개월후_부활_실패() {
        Policy policy = lapsedPolicy();
        LocalDate reinstateDate = LAPSED_AT.plusMonths(25);

        assertThatThrownBy(() -> policy.reinstate(reinstateDate))
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    @Test
    @DisplayName("ACTIVE 상태에서 부활 시도 → 예외")
    void ACTIVE_상태에서_부활_예외() {
        Policy policy = Policy.builder()
                .policyNumber("POL-REINSTATE-002")
                .status(PolicyStatus.ACTIVE)
                .effectiveDate(EFFECTIVE)
                .maturityDate(LocalDate.of(2044, 1, 1))
                .consecutivePremiumFailures(0)
                .premiumAmount(new BigDecimal("100000"))
                .fcId("FC-001")
                .build();

        assertThatThrownBy(() -> policy.reinstate(LAPSED_AT.plusMonths(1)))
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    @Test
    @DisplayName("부활 후 다시 실효 → 다시 부활 가능 (횟수 제한 없음, D7)")
    void 부활_후_재실효_재부활_가능() {
        Policy policy = lapsedPolicy();
        LocalDate firstReinstate = LAPSED_AT.plusMonths(1);

        // 첫 번째 부활
        policy.reinstate(firstReinstate);
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);

        // 다시 납입 실패 2회 → 재실효
        policy.recordPremiumFailure(firstReinstate.plusDays(30));
        LocalDate secondLapseDate = firstReinstate.plusDays(60);
        policy.recordPremiumFailure(secondLapseDate);
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.LAPSED);

        // 두 번째 부활 (실효일 갱신됨)
        policy.reinstate(secondLapseDate.plusMonths(1));
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
    }
}
