package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.insurance.application.port.in.CancelPolicyUseCase.CancelPolicyCommand;
import github.lms.lemuel.insurance.application.port.in.PolicyTerminationResult;
import github.lms.lemuel.insurance.application.port.in.SurrenderPolicyUseCase.SurrenderPolicyCommand;
import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SavePolicyPort;
import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.GeneralPayoutCalculator.PayoutQuote;
import github.lms.lemuel.insurance.domain.GeneralPayoutType;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException;
import github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException;
import github.lms.lemuel.insurance.domain.exception.PolicyOwnershipException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해지·철회 유스케이스 검증 — 전이 + 이벤트 + payout 생성 + 감사가 한 흐름으로 일어나는지,
 * 404/403/409 가드가 전이 이전에 서는지.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PolicyTerminationService — 해지·철회 (§14 돈 경로)")
class PolicyTerminationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String POLICY_NUMBER = "POL-20230101-bbbb2222";

    @Mock LoadPolicyPort loadPolicyPort;
    @Mock SavePolicyPort savePolicyPort;
    @Mock PublishInsuranceEventPort publishPort;
    @Mock GeneralPayoutRecorder payoutRecorder;
    @Mock AuditLogger auditLogger;

    private PolicyTerminationService service() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);
        return new PolicyTerminationService(
                loadPolicyPort, savePolicyPort, publishPort, payoutRecorder, clock, auditLogger);
    }

    private static Policy activePolicy(LocalDate effectiveDate) {
        return Policy.builder()
                .id(1L)
                .policyId("22222222-2222-2222-2222-222222222222")
                .policyNumber(POLICY_NUMBER)
                .status(PolicyStatus.ACTIVE)
                .effectiveDate(effectiveDate)
                .premiumAmount(new BigDecimal("1200000.00"))
                .fcId("fc-100")
                .build();
    }

    private static GeneralPayout payoutOf(GeneralPayoutType type, String amount) {
        return GeneralPayout.request("22222222-2222-2222-2222-222222222222", POLICY_NUMBER, type,
                new PayoutQuote(new BigDecimal(amount), new BigDecimal(amount),
                        BigDecimal.ONE, 36, 37),
                TODAY);
    }

    // ────────────────────────────────────────────────────────────────────
    // 해지
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("해지: 전이 + 상태변경 이벤트 + payout 생성 + 감사 — 결과에 payout 요약 포함")
    void surrenderHappyPath() {
        Policy policy = activePolicy(TODAY.minusYears(3));
        when(loadPolicyPort.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy));
        GeneralPayout payout = payoutOf(GeneralPayoutType.SURRENDER_REFUND, "2220000.00");
        when(payoutRecorder.recordFor(policy, PolicyStatus.ACTIVE, TODAY))
                .thenReturn(Optional.of(payout));

        PolicyTerminationResult result = service().surrender(
                new SurrenderPolicyCommand(POLICY_NUMBER, "fc-100"));

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.SURRENDERED);
        assertThat(result.status()).isEqualTo(PolicyStatus.SURRENDERED);
        assertThat(result.payout().amount()).isEqualByComparingTo("2220000.00");
        verify(savePolicyPort).save(policy);
        verify(publishPort).publishPolicyStatusChanged(policy, PolicyStatus.ACTIVE);
        verify(auditLogger).record(
                eq(AuditAction.INSURANCE_POLICY_SURRENDERED), anyString(), eq(POLICY_NUMBER), anyString());
    }

    @Test
    @DisplayName("환급액 0 해지: payout 없이 전이만 — 결과 payout 은 null")
    void surrenderWithZeroRefund() {
        Policy policy = activePolicy(TODAY.minusMonths(3));
        when(loadPolicyPort.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy));
        when(payoutRecorder.recordFor(policy, PolicyStatus.ACTIVE, TODAY))
                .thenReturn(Optional.empty());

        PolicyTerminationResult result = service().surrender(
                new SurrenderPolicyCommand(POLICY_NUMBER, "fc-100"));

        assertThat(result.payout()).isNull();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.SURRENDERED);
    }

    @Test
    @DisplayName("없는 계약 해지는 404 — 어떤 부수효과도 없다")
    void surrenderUnknownPolicy() {
        when(loadPolicyPort.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().surrender(new SurrenderPolicyCommand(POLICY_NUMBER, "fc-100")))
                .isInstanceOf(PolicyNotFoundException.class);
        verify(savePolicyPort, never()).save(any());
    }

    @Test
    @DisplayName("담당 FC 불일치는 403 — 전이 이전에 차단된다")
    void surrenderByWrongFc() {
        Policy policy = activePolicy(TODAY.minusYears(3));
        when(loadPolicyPort.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service().surrender(new SurrenderPolicyCommand(POLICY_NUMBER, "fc-999")))
                .isInstanceOf(PolicyOwnershipException.class);
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE); // 전이 안 됨
        verify(savePolicyPort, never()).save(any());
    }

    @Test
    @DisplayName("이미 종료된 계약 해지는 409 — 도메인 상태머신이 거부한다")
    void surrenderTerminalPolicy() {
        Policy policy = Policy.builder()
                .id(1L)
                .policyId("22222222-2222-2222-2222-222222222222")
                .policyNumber(POLICY_NUMBER)
                .status(PolicyStatus.SURRENDERED)
                .effectiveDate(TODAY.minusYears(3))
                .premiumAmount(new BigDecimal("1200000.00"))
                .fcId("fc-100")
                .build();
        when(loadPolicyPort.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service().surrender(new SurrenderPolicyCommand(POLICY_NUMBER, "fc-100")))
                .isInstanceOf(InvalidPolicyTransitionException.class);
        verify(savePolicyPort, never()).save(any());
    }

    // ────────────────────────────────────────────────────────────────────
    // 청약철회
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("철회: 15일 창구 내 — 전이 + 전액 환급 payout + 감사")
    void cancelWithinWindow() {
        Policy policy = activePolicy(TODAY.minusDays(10));
        when(loadPolicyPort.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy));
        GeneralPayout payout = payoutOf(GeneralPayoutType.WITHDRAWAL_REFUND, "100000.00");
        when(payoutRecorder.recordFor(policy, PolicyStatus.ACTIVE, TODAY))
                .thenReturn(Optional.of(payout));

        PolicyTerminationResult result = service().cancel(
                new CancelPolicyCommand(POLICY_NUMBER, "fc-100"));

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.CANCELLED);
        assertThat(result.payout().payoutType()).isEqualTo(GeneralPayoutType.WITHDRAWAL_REFUND);
        verify(publishPort).publishPolicyStatusChanged(policy, PolicyStatus.ACTIVE);
        verify(auditLogger).record(
                eq(AuditAction.INSURANCE_POLICY_CANCELLED), anyString(), eq(POLICY_NUMBER), anyString());
    }

    @Test
    @DisplayName("철회 창구(15일) 초과는 409 — payout 생성 없음")
    void cancelPastWindow() {
        Policy policy = activePolicy(TODAY.minusDays(20));
        when(loadPolicyPort.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service().cancel(new CancelPolicyCommand(POLICY_NUMBER, "fc-100")))
                .isInstanceOf(InvalidPolicyTransitionException.class);
        verify(savePolicyPort, never()).save(any());
        verify(payoutRecorder, never()).recordFor(any(), any(), any());
    }
}
