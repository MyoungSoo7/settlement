package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D7 — Policy 전체 생애주기 통합 시나리오.
 *
 * <p>D7 상태머신의 완전한 경로 커버리지:
 * <ul>
 *   <li>정상 계약 → 만기 소멸</li>
 *   <li>실효 → 부활 → 다시 실효 → 부활 기간 도과 소멸</li>
 *   <li>실효 → 부활 → 임의 해지</li>
 *   <li>청약철회 (ACTIVE, LAPSED 양쪽)</li>
 *   <li>Terminal 상태 불변 보장</li>
 * </ul>
 */
@DisplayName("Policy 생애주기 (D7 전체 시나리오)")
class PolicyLifecycleTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2024, 1, 1);
    private static final LocalDate MATURITY   = LocalDate.of(2044, 1, 1);

    private Policy newActivePolicy(String policyNumber) {
        return Policy.builder()
                .policyNumber(policyNumber)
                .status(PolicyStatus.ACTIVE)
                .effectiveDate(EFFECTIVE)
                .maturityDate(MATURITY)
                .consecutivePremiumFailures(0)
                .premiumAmount(new BigDecimal("150000"))
                .fcId("FC-100")
                .build();
    }

    @Test
    @DisplayName("생애주기-1: 정상 계약 → 만기(ACTIVE → EXPIRED)")
    void 정상계약_만기소멸() {
        Policy policy = newActivePolicy("POL-LIFE-001");
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);

        policy.expire(); // 만기일 도래

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.EXPIRED);
        assertThat(policy.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("생애주기-2: 계약자 해지 (ACTIVE → SURRENDERED)")
    void 계약자_해지() {
        Policy policy = newActivePolicy("POL-LIFE-002");

        policy.surrender();

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.SURRENDERED);
        assertThat(policy.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("생애주기-3: 실효 → 부활 → 만기 (전체 정상 경로)")
    void 실효_부활_만기() {
        Policy policy = newActivePolicy("POL-LIFE-003");
        LocalDate fail1 = EFFECTIVE.plusMonths(2);
        LocalDate fail2 = EFFECTIVE.plusMonths(3);

        // 납입 실패 2회 → LAPSED
        policy.recordPremiumFailure(fail1);
        policy.recordPremiumFailure(fail2);
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.LAPSED);
        assertThat(policy.getLapsedAt()).isEqualTo(fail2);

        // 부활 (실효일 +6개월 이내)
        policy.reinstate(fail2.plusMonths(6));
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(policy.getConsecutivePremiumFailures()).isEqualTo(0);

        // 만기
        policy.expire();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.EXPIRED);
    }

    @Test
    @DisplayName("생애주기-4: 실효 → 부활 기간(24개월) 도과 → EXPIRED 소멸")
    void 실효_부활기간_도과_소멸() {
        Policy policy = newActivePolicy("POL-LIFE-004");
        LocalDate fail1 = EFFECTIVE.plusMonths(2);
        LocalDate fail2 = EFFECTIVE.plusMonths(3);

        policy.recordPremiumFailure(fail1);
        policy.recordPremiumFailure(fail2);
        LocalDate lapseDate = policy.getLapsedAt();

        // 24개월 경과 후 소멸 처리
        policy.expireAfterLapse(lapseDate.plusMonths(24));
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.EXPIRED);
        assertThat(policy.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("생애주기-5: 청약철회 — ACTIVE 상태, 효력일 당일")
    void 청약철회_ACTIVE_당일() {
        Policy policy = newActivePolicy("POL-LIFE-005");

        policy.cancel(EFFECTIVE); // 효력일 당일(0일) → 유효

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.CANCELLED);
        assertThat(policy.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("생애주기-5: 청약철회 — ACTIVE, 효력일+14일 (경계: 마지막 유효일)")
    void 청약철회_ACTIVE_14일() {
        Policy policy = newActivePolicy("POL-LIFE-005b");

        policy.cancel(EFFECTIVE.plusDays(14)); // 14일 → 유효 (14 < 15)

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.CANCELLED);
    }

    @Test
    @DisplayName("생애주기-6: 청약철회 — ACTIVE, 효력일+15일 → 창구 초과 예외")
    void 청약철회_ACTIVE_15일_초과_예외() {
        Policy policy = newActivePolicy("POL-LIFE-006");

        assertThatThrownBy(() -> policy.cancel(EFFECTIVE.plusDays(15)))
                .isInstanceOf(InvalidPolicyTransitionException.class);

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE); // 상태 변화 없음
    }

    @Test
    @DisplayName("생애주기-7: 청약철회 — LAPSED 상태, 효력일 기준 14일 이내")
    void 청약철회_LAPSED() {
        // 효력일로부터 5일 만에 실효된 케이스
        LocalDate earlyLapse = EFFECTIVE.plusDays(5);
        Policy policy = Policy.builder()
                .policyNumber("POL-LIFE-007")
                .status(PolicyStatus.LAPSED)
                .effectiveDate(EFFECTIVE)
                .maturityDate(MATURITY)
                .lapsedAt(earlyLapse)
                .consecutivePremiumFailures(2)
                .premiumAmount(new BigDecimal("150000"))
                .fcId("FC-100")
                .build();

        policy.cancel(EFFECTIVE.plusDays(10)); // 효력일 기준 10일 → 유효

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.CANCELLED);
    }

    @Test
    @DisplayName("생애주기-8: 부활 횟수 제한 없음 — 2회 부활 성공")
    void 부활_횟수_제한_없음() {
        Policy policy = newActivePolicy("POL-LIFE-008");

        // 1차 실효
        policy.recordPremiumFailure(EFFECTIVE.plusMonths(2));
        LocalDate lapse1 = EFFECTIVE.plusMonths(3);
        policy.recordPremiumFailure(lapse1);
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.LAPSED);

        // 1차 부활
        policy.reinstate(lapse1.plusMonths(1));
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);

        // 2차 실효
        policy.recordPremiumFailure(lapse1.plusMonths(4));
        LocalDate lapse2 = lapse1.plusMonths(5);
        policy.recordPremiumFailure(lapse2);
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.LAPSED);

        // 2차 부활
        policy.reinstate(lapse2.plusMonths(1));
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    @DisplayName("생애주기-9: terminal 상태(SURRENDERED)에서 모든 전이 → 예외 (불변성)")
    void terminal_SURRENDERED_불변성() {
        Policy policy = newActivePolicy("POL-LIFE-009");
        policy.surrender();

        // terminal 에서 나가는 모든 시도
        assertThatThrownBy(policy::surrender)
                .isInstanceOf(InvalidPolicyTransitionException.class);
        assertThatThrownBy(policy::expire)
                .isInstanceOf(InvalidPolicyTransitionException.class);
        assertThatThrownBy(() -> policy.cancel(EFFECTIVE))
                .isInstanceOf(InvalidPolicyTransitionException.class);
        assertThatThrownBy(() -> policy.reinstate(EFFECTIVE.plusMonths(1)))
                .isInstanceOf(InvalidPolicyTransitionException.class);
    }

    @Test
    @DisplayName("PolicyStatus.isTerminal() — SURRENDERED, EXPIRED, CANCELLED 는 true")
    void terminal_상태_판별() {
        assertThat(PolicyStatus.SURRENDERED.isTerminal()).isTrue();
        assertThat(PolicyStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(PolicyStatus.CANCELLED.isTerminal()).isTrue();

        assertThat(PolicyStatus.ACTIVE.isTerminal()).isFalse();
        assertThat(PolicyStatus.LAPSED.isTerminal()).isFalse();
    }
}
