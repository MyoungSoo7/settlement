package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D7 — 상태머신 전이 규칙 검증 (허용·금지 양방향).
 *
 * <p>D7 허용 전이 7개를 각각 검증하고, terminal 상태(SURRENDERED·EXPIRED·CANCELLED)에서
 * 나가는 모든 전이 시도가 {@link InvalidPolicyTransitionException} 을 던지는지 확인한다.
 */
@DisplayName("Policy 상태머신 전이 규칙 (D7)")
class PolicyTransitionTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2024, 1, 1);
    private static final LocalDate MATURITY   = LocalDate.of(2044, 1, 1);
    private static final LocalDate LAPSED_AT  = LocalDate.of(2024, 6, 1);

    private Policy activePolicy(String policyNumber) {
        return Policy.builder()
                .policyNumber(policyNumber)
                .status(PolicyStatus.ACTIVE)
                .effectiveDate(EFFECTIVE)
                .maturityDate(MATURITY)
                .consecutivePremiumFailures(0)
                .premiumAmount(new BigDecimal("200000"))
                .fcId("FC-001")
                .build();
    }

    private Policy lapsedPolicy(String policyNumber) {
        return Policy.builder()
                .policyNumber(policyNumber)
                .status(PolicyStatus.LAPSED)
                .effectiveDate(EFFECTIVE)
                .maturityDate(MATURITY)
                .lapsedAt(LAPSED_AT)
                .consecutivePremiumFailures(2)
                .premiumAmount(new BigDecimal("200000"))
                .fcId("FC-001")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 허용 전이 7개 (D7)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[허용-1] ACTIVE → LAPSED : 납입 실패 2회 연속")
    void 전이_ACTIVE_LAPSED() {
        Policy policy = activePolicy("POL-T-001");
        policy.recordPremiumFailure(EFFECTIVE.plusDays(30));
        policy.recordPremiumFailure(EFFECTIVE.plusDays(60));
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.LAPSED);
    }

    @Test
    @DisplayName("[허용-2] LAPSED → ACTIVE : 부활 (24개월 이내)")
    void 전이_LAPSED_ACTIVE() {
        Policy policy = lapsedPolicy("POL-T-002");
        policy.reinstate(LAPSED_AT.plusMonths(12));
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    @DisplayName("[허용-3] LAPSED → EXPIRED : 실효일로부터 24개월 경과 소멸")
    void 전이_LAPSED_EXPIRED() {
        Policy policy = lapsedPolicy("POL-T-003");
        policy.expireAfterLapse(LAPSED_AT.plusMonths(24));
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.EXPIRED);
    }

    @Test
    @DisplayName("[허용-4] ACTIVE → SURRENDERED : 계약자 임의 해지")
    void 전이_ACTIVE_SURRENDERED() {
        Policy policy = activePolicy("POL-T-004");
        policy.surrender();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.SURRENDERED);
    }

    @Test
    @DisplayName("[허용-5] ACTIVE → EXPIRED : 만기일 도래")
    void 전이_ACTIVE_EXPIRED() {
        Policy policy = activePolicy("POL-T-005");
        policy.expire();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.EXPIRED);
    }

    @Test
    @DisplayName("[허용-6] ACTIVE → CANCELLED : 청약철회 (효력일로부터 14일 이내)")
    void 전이_ACTIVE_CANCELLED() {
        Policy policy = activePolicy("POL-T-006");
        policy.cancel(EFFECTIVE.plusDays(14)); // 14일 째 → 유효
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.CANCELLED);
    }

    @Test
    @DisplayName("[허용-7] LAPSED → CANCELLED : 청약철회 (효력일로부터 14일 이내)")
    void 전이_LAPSED_CANCELLED() {
        // 효력일로부터 14일 안에 실효가 발생한 LAPSED 정책
        LocalDate earlyLapse = EFFECTIVE.plusDays(5);
        Policy policy = Policy.builder()
                .policyNumber("POL-T-007")
                .status(PolicyStatus.LAPSED)
                .effectiveDate(EFFECTIVE)
                .maturityDate(MATURITY)
                .lapsedAt(earlyLapse)
                .consecutivePremiumFailures(2)
                .premiumAmount(new BigDecimal("200000"))
                .fcId("FC-001")
                .build();

        policy.cancel(EFFECTIVE.plusDays(10)); // 효력일+10일 → 14일 이내
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.CANCELLED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Terminal 상태에서 나가는 전이 → 예외
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SURRENDERED(terminal)에서 모든 전이 시도 → 예외")
    void SURRENDERED_에서_나가는_전이_예외() {
        Policy policy = activePolicy("POL-T-SUR");
        policy.surrender();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.SURRENDERED);

        // surrender 재시도
        assertThatThrownBy(policy::surrender)
                .isInstanceOf(InvalidPolicyTransitionException.class);
        // expire 시도
        assertThatThrownBy(policy::expire)
                .isInstanceOf(InvalidPolicyTransitionException.class);
        // cancel 시도
        assertThatThrownBy(() -> policy.cancel(EFFECTIVE))
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    @Test
    @DisplayName("EXPIRED(terminal)에서 모든 전이 시도 → 예외")
    void EXPIRED_에서_나가는_전이_예외() {
        Policy policy = activePolicy("POL-T-EXP");
        policy.expire();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.EXPIRED);

        assertThatThrownBy(policy::expire)
                .isInstanceOf(InvalidPolicyTransitionException.class);
        assertThatThrownBy(policy::surrender)
                .isInstanceOf(InvalidPolicyTransitionException.class);
        assertThatThrownBy(() -> policy.cancel(EFFECTIVE))
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    @Test
    @DisplayName("CANCELLED(terminal)에서 모든 전이 시도 → 예외")
    void CANCELLED_에서_나가는_전이_예외() {
        Policy policy = activePolicy("POL-T-CAN");
        policy.cancel(EFFECTIVE.plusDays(5));
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.CANCELLED);

        assertThatThrownBy(() -> policy.cancel(EFFECTIVE.plusDays(5)))
                .isInstanceOf(InvalidPolicyTransitionException.class);
        assertThatThrownBy(policy::surrender)
                .isInstanceOf(InvalidPolicyTransitionException.class);
        assertThatThrownBy(policy::expire)
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 표에 없는 전이 → 예외
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE → LAPSED 직접 전이(1회 실패 이후)는 표에 없는 경로가 아님 — 1회 실패는 ACTIVE 유지")
    void ACTIVE_1회_실패는_LAPSED_전이_안됨() {
        Policy policy = activePolicy("POL-T-NOMAP");
        policy.recordPremiumFailure(EFFECTIVE.plusDays(30));
        // 1회 실패 후에도 ACTIVE
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    @DisplayName("ACTIVE 에서 부활(reinstate) 시도 → 예외 (표에 없는 전이)")
    void ACTIVE_에서_부활_예외() {
        Policy policy = activePolicy("POL-T-INV1");
        assertThatThrownBy(() -> policy.reinstate(EFFECTIVE.plusMonths(1)))
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    @Test
    @DisplayName("LAPSED 에서 surrender 시도 → 예외 (표에 없는 전이)")
    void LAPSED_에서_surrender_예외() {
        Policy policy = lapsedPolicy("POL-T-INV2");
        assertThatThrownBy(policy::surrender)
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    @Test
    @DisplayName("LAPSED 에서 expire() 직접 호출(만기) 시도 → 예외 (표에 없는 전이)")
    void LAPSED_에서_expire_만기_예외() {
        Policy policy = lapsedPolicy("POL-T-INV3");
        // LAPSED → EXPIRED 는 expireAfterLapse 만 허용 (24개월 경과)
        // expire() 는 ACTIVE → EXPIRED 용
        assertThatThrownBy(policy::expire)
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    @Test
    @DisplayName("LAPSED 에서 expireAfterLapse - 24개월 미경과 → 예외")
    void LAPSED_에서_24개월_미경과_소멸_예외() {
        Policy policy = lapsedPolicy("POL-T-INV4");
        assertThatThrownBy(() -> policy.expireAfterLapse(LAPSED_AT.plusMonths(23)))
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    @Test
    @DisplayName("청약철회 기간(15일) 경과 후 cancel 시도 → 예외")
    void 청약철회_기간_초과_예외() {
        Policy policy = activePolicy("POL-T-INV5");
        // 효력일로부터 15일 이후 → 창구 종료
        assertThatThrownBy(() -> policy.cancel(EFFECTIVE.plusDays(15)))
                .isInstanceOf(InvalidPolicyTransitionException.class)
                .hasMessageContaining("15일");
    }
}
