package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SaveGeneralPayoutPort;
import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.GeneralPayoutStatus;
import github.lms.lemuel.insurance.domain.GeneralPayoutType;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.exception.InvalidGeneralPayoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-G1 전이→지급 매핑 검증 — 해지/만기/실효소멸/철회가 올바른 유형·기준일·금액의
 * payout 을 낳는지, 0원이면 만들지 않는지.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneralPayoutRecorder — 전이가 낳는 일반지급 (D-G1)")
class GeneralPayoutRecorderTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2020, 1, 1);
    private static final String POLICY_ID = "11111111-1111-1111-1111-111111111111";

    @Mock LoadPolicyPort loadPolicyPort;
    @Mock SaveGeneralPayoutPort savePayoutPort;
    @Mock PublishInsuranceEventPort publishPort;

    private GeneralPayoutRecorder recorder() {
        lenient().when(savePayoutPort.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(loadPolicyPort.findPaymentCycleMonths(POLICY_ID))
                .thenReturn(Optional.of(1));
        return new GeneralPayoutRecorder(loadPolicyPort, savePayoutPort, publishPort);
    }

    private static Policy policy(PolicyStatus status, LocalDate maturity, LocalDate lapsedAt) {
        return Policy.builder()
                .id(1L)
                .policyId(POLICY_ID)
                .policyNumber("POL-20200101-aaaa1111")
                .status(status)
                .effectiveDate(EFFECTIVE)
                .maturityDate(maturity)
                .lapsedAt(lapsedAt)
                .premiumAmount(new BigDecimal("1200000.00"))
                .fcId("fc-100")
                .build();
    }

    @Test
    @DisplayName("해지(SURRENDERED): 해지일 기준 해약환급금 payout + requested 이벤트")
    void surrenderedCreatesSurrenderRefund() {
        Policy policy = policy(PolicyStatus.SURRENDERED, null, null);
        LocalDate today = EFFECTIVE.plusMonths(36); // m=36 → 60%

        Optional<GeneralPayout> payout = recorder().recordFor(policy, PolicyStatus.ACTIVE, today);

        assertThat(payout).isPresent();
        assertThat(payout.get().getPayoutType()).isEqualTo(GeneralPayoutType.SURRENDER_REFUND);
        assertThat(payout.get().getAmount()).isEqualByComparingTo("2220000.00");
        assertThat(payout.get().getStatus()).isEqualTo(GeneralPayoutStatus.REQUESTED);
        assertThat(payout.get().getRequestedOn()).isEqualTo(today);
        verify(savePayoutPort).insert(any(GeneralPayout.class));
        verify(publishPort).publishGeneralPayoutRequested(policy, payout.get());
    }

    @Test
    @DisplayName("경과 12개월 미만 해지: 환급액 0 — payout 미생성·이벤트 없음 (D-G3)")
    void earlySurrenderCreatesNothing() {
        Policy policy = policy(PolicyStatus.SURRENDERED, null, null);

        Optional<GeneralPayout> payout = recorder().recordFor(
                policy, PolicyStatus.ACTIVE, EFFECTIVE.plusMonths(11));

        assertThat(payout).isEmpty();
        verify(savePayoutPort, never()).insert(any());
        verify(publishPort, never()).publishGeneralPayoutRequested(any(), any());
    }

    @Test
    @DisplayName("만기소멸(ACTIVE→EXPIRED): 만기일 기준 만기보험금 — 기납입 100%")
    void maturityExpiryCreatesMaturityBenefit() {
        LocalDate maturity = EFFECTIVE.plusYears(10);
        Policy policy = policy(PolicyStatus.EXPIRED, maturity, null);

        Optional<GeneralPayout> payout = recorder().recordFor(
                policy, PolicyStatus.ACTIVE, maturity.plusDays(3)); // 배치가 며칠 늦게 돌아도 만기일 기준

        assertThat(payout).isPresent();
        assertThat(payout.get().getPayoutType()).isEqualTo(GeneralPayoutType.MATURITY_BENEFIT);
        assertThat(payout.get().getAmount()).isEqualByComparingTo("12000000.00"); // 120회 × 100,000
        assertThat(payout.get().getInstallmentCount()).isEqualTo(120);
    }

    @Test
    @DisplayName("실효소멸(LAPSED→EXPIRED): 실효일 기준 해약환급금 — 실효 이후 납입 없음")
    void lapsedExpiryCreatesSurrenderRefundBasedOnLapseDate() {
        LocalDate lapsedAt = EFFECTIVE.plusMonths(13); // m=13 → 40%, 14회 납입
        Policy policy = policy(PolicyStatus.EXPIRED, null, lapsedAt);

        Optional<GeneralPayout> payout = recorder().recordFor(
                policy, PolicyStatus.LAPSED, lapsedAt.plusMonths(25));

        assertThat(payout).isPresent();
        assertThat(payout.get().getPayoutType()).isEqualTo(GeneralPayoutType.SURRENDER_REFUND);
        // 14회 × 100,000 = 1,400,000 → × 40% = 560,000
        assertThat(payout.get().getPaidPremiumTotal()).isEqualByComparingTo("1400000.00");
        assertThat(payout.get().getAmount()).isEqualByComparingTo("560000.00");
        assertThat(payout.get().getElapsedMonths()).isEqualTo(13);
    }

    @Test
    @DisplayName("철회(CANCELLED): 기납입 전액 환급 payout")
    void cancelledCreatesFullWithdrawalRefund() {
        Policy policy = policy(PolicyStatus.CANCELLED, null, null);

        Optional<GeneralPayout> payout = recorder().recordFor(
                policy, PolicyStatus.ACTIVE, EFFECTIVE.plusDays(9));

        assertThat(payout).isPresent();
        assertThat(payout.get().getPayoutType()).isEqualTo(GeneralPayoutType.WITHDRAWAL_REFUND);
        assertThat(payout.get().getAmount()).isEqualByComparingTo("100000.00"); // 1회차 전액
        assertThat(payout.get().getAppliedRate()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("terminal 이 아닌 상태는 일반지급 트리거가 아니다")
    void rejectsNonTerminalStatus() {
        Policy policy = policy(PolicyStatus.ACTIVE, null, null);

        assertThatThrownBy(() -> recorder().recordFor(policy, PolicyStatus.ACTIVE, EFFECTIVE.plusMonths(12)))
                .isInstanceOf(InvalidGeneralPayoutException.class);
    }

    @Test
    @DisplayName("납입주기를 찾지 못하면 조용히 넘어가지 않고 실패한다")
    void failsLoudlyWhenCycleMissing() {
        Policy policy = policy(PolicyStatus.SURRENDERED, null, null);
        when(loadPolicyPort.findPaymentCycleMonths(POLICY_ID)).thenReturn(Optional.empty());
        GeneralPayoutRecorder recorder =
                new GeneralPayoutRecorder(loadPolicyPort, savePayoutPort, publishPort);

        assertThatThrownBy(() -> recorder.recordFor(policy, PolicyStatus.ACTIVE, EFFECTIVE.plusMonths(36)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("payout 생성 시 산출근거 스냅샷이 채워진다 (D-G5)")
    void snapshotsCalculationBasis() {
        Policy policy = policy(PolicyStatus.SURRENDERED, null, null);
        LocalDate today = EFFECTIVE.plusMonths(36);

        GeneralPayout payout = recorder().recordFor(policy, PolicyStatus.ACTIVE, today).orElseThrow();

        ArgumentCaptor<GeneralPayout> inserted = ArgumentCaptor.forClass(GeneralPayout.class);
        verify(savePayoutPort).insert(inserted.capture());
        assertThat(inserted.getValue().getPaidPremiumTotal()).isEqualByComparingTo("3700000.00");
        assertThat(inserted.getValue().getAppliedRate()).isEqualByComparingTo("0.60");
        assertThat(inserted.getValue().getInstallmentCount()).isEqualTo(37);
        assertThat(payout.getPolicyNumber()).isEqualTo("POL-20200101-aaaa1111");
    }
}
