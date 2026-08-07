package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.ExpirePoliciesUseCase.ExpiryBatchResult;
import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SavePolicyPort;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 만기·실효소멸 판정 배치 서비스 테스트.
 *
 * <p>전이는 도메인 메서드를 통과하고, 전이 1건당 저장 + policy_status_changed 발행이
 * 일어나는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PolicyExpiryService — 만기·실효소멸 판정 배치")
class PolicyExpiryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);

    @Mock LoadPolicyPort loadPolicyPort;
    @Mock SavePolicyPort savePolicyPort;
    @Mock PublishInsuranceEventPort publishPort;

    private PolicyExpiryService service() {
        return new PolicyExpiryService(loadPolicyPort, savePolicyPort, publishPort);
    }

    private static Policy activeMatured() {
        return Policy.builder()
                .id(1L)
                .policyId("11111111-1111-1111-1111-111111111111")
                .policyNumber("POL-2024-001")
                .status(PolicyStatus.ACTIVE)
                .effectiveDate(LocalDate.of(2024, 8, 1))
                .maturityDate(TODAY.minusDays(1))
                .premiumAmount(new BigDecimal("50000.00"))
                .fcId("fc-100")
                .build();
    }

    private static Policy lapsedOverdue() {
        return Policy.builder()
                .id(2L)
                .policyId("22222222-2222-2222-2222-222222222222")
                .policyNumber("POL-2024-002")
                .status(PolicyStatus.LAPSED)
                .effectiveDate(LocalDate.of(2023, 1, 1))
                .lapsedAt(TODAY.minusMonths(25))  // 부활 창구(24개월) 도과
                .consecutivePremiumFailures(2)
                .premiumAmount(new BigDecimal("50000.00"))
                .fcId("fc-100")
                .build();
    }

    @Test
    @DisplayName("만기 도래 ACTIVE 계약을 EXPIRED 로 전이하고 상태변경 이벤트를 발행한다")
    void expiresMaturedActivePolicies() {
        Policy policy = activeMatured();
        when(loadPolicyPort.findActiveMaturedOnOrBefore(TODAY)).thenReturn(List.of(policy));
        when(loadPolicyPort.findLapsedOnOrBefore(any())).thenReturn(List.of());

        ExpiryBatchResult result = service().expireOn(TODAY);

        assertThat(result.maturedExpired()).isEqualTo(1);
        assertThat(result.lapsedExpired()).isZero();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.EXPIRED);
        verify(savePolicyPort).save(policy);
        verify(publishPort).publishPolicyStatusChanged(policy, PolicyStatus.ACTIVE);
    }

    @Test
    @DisplayName("부활 창구 도과 LAPSED 계약을 EXPIRED 로 전이한다 — 스캔 컷오프는 today-24개월")
    void expiresLapsedPoliciesPastReinstatementWindow() {
        Policy policy = lapsedOverdue();
        when(loadPolicyPort.findActiveMaturedOnOrBefore(TODAY)).thenReturn(List.of());
        when(loadPolicyPort.findLapsedOnOrBefore(any())).thenReturn(List.of(policy));

        ExpiryBatchResult result = service().expireOn(TODAY);

        assertThat(result.lapsedExpired()).isEqualTo(1);
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.EXPIRED);
        verify(publishPort).publishPolicyStatusChanged(policy, PolicyStatus.LAPSED);

        // 스캔 컷오프 = today - REINSTATEMENT_WINDOW_MONTHS (도메인 상수와 동일 출처)
        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        verify(loadPolicyPort).findLapsedOnOrBefore(cutoff.capture());
        assertThat(cutoff.getValue())
                .isEqualTo(TODAY.minusMonths(Policy.REINSTATEMENT_WINDOW_MONTHS));
    }

    @Test
    @DisplayName("대상이 없으면 저장·발행 없이 0 건 결과를 반환한다")
    void returnsZeroWhenNothingToExpire() {
        when(loadPolicyPort.findActiveMaturedOnOrBefore(TODAY)).thenReturn(List.of());
        when(loadPolicyPort.findLapsedOnOrBefore(any())).thenReturn(List.of());

        ExpiryBatchResult result = service().expireOn(TODAY);

        assertThat(result.total()).isZero();
        verify(savePolicyPort, never()).save(any());
        verify(publishPort, never()).publishPolicyStatusChanged(any(), eq(PolicyStatus.ACTIVE));
    }
}
