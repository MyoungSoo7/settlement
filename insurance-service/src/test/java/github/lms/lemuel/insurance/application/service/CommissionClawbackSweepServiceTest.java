package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.SweepCommissionClawbacksUseCase.ClawbackSweepResult;
import github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort;
import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SaveCommissionSchedulePort;
import github.lms.lemuel.insurance.domain.CommissionConstants;
import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.CommissionStatus;
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
 * 환수 스윕 배치 서비스 테스트 — D6 환수액 공식과 상태 정리가 핵심 돈 경로.
 *
 * <p>환수액 = 기지급합계 × (W - m) / W, W=24, 통화 최소단위 절사(DOWN).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommissionClawbackSweepService — 환수 스윕 배치")
class CommissionClawbackSweepServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final String POLICY_ID = "11111111-1111-1111-1111-111111111111";

    @Mock LoadPolicyPort loadPolicyPort;
    @Mock LoadCommissionSchedulePort loadSchedulePort;
    @Mock SaveCommissionSchedulePort saveSchedulePort;
    @Mock PublishInsuranceEventPort publishPort;

    private CommissionClawbackSweepService service() {
        return new CommissionClawbackSweepService(
                loadPolicyPort, loadSchedulePort, saveSchedulePort, publishPort);
    }

    private static Policy terminal(PolicyStatus status, LocalDate effectiveDate) {
        Policy.Builder b = Policy.builder()
                .id(1L)
                .policyId(POLICY_ID)
                .policyNumber("POL-2026-001")
                .status(status)
                .effectiveDate(effectiveDate)
                .premiumAmount(new BigDecimal("50000.00"))
                .fcId("fc-100");
        return b.build();
    }

    private static CommissionSchedule paidInstallment(int no) {
        return CommissionSchedule.builder()
                .id((long) no)
                .commissionId("00000000-0000-0000-0000-00000000000" + no)
                .policyId(POLICY_ID)
                .fcId("fc-100")
                .recipientType(CommissionConstants.RECIPIENT_TYPE_FC)
                .installmentNo(no)
                .installmentAmount(new BigDecimal("8333.33"))
                .firstYearTotal(new BigDecimal("100000.00"))
                .dueDate(TODAY.minusMonths(1))
                .paidAt(TODAY.minusMonths(1))
                .paidAmount(new BigDecimal("8333.33"))
                .status(CommissionStatus.PAID)
                .build();
    }

    private static CommissionSchedule scheduledInstallment(int no) {
        return CommissionSchedule.builder()
                .id((long) (100 + no))
                .commissionId("00000000-0000-0000-0000-0000000001" + no)
                .policyId(POLICY_ID)
                .fcId("fc-100")
                .recipientType(CommissionConstants.RECIPIENT_TYPE_FC)
                .installmentNo(no)
                .installmentAmount(new BigDecimal("8333.33"))
                .firstYearTotal(new BigDecimal("100000.00"))
                .dueDate(TODAY.plusMonths(no))
                .build();
    }

    @Test
    @DisplayName("해지(SURRENDERED) 10개월 경과: 환수액 = 기지급 × 14/24 절사, 계약 단위 이벤트 1건")
    void flagsClawbackForSurrenderedPolicyInsideWindow() {
        // effective 2025-10-07 → today 2026-08-07: m = 10개월
        Policy policy = terminal(PolicyStatus.SURRENDERED, TODAY.minusMonths(10));
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.SCHEDULED))
                .thenReturn(List.of());
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.PAID))
                .thenReturn(List.of(policy));
        when(loadSchedulePort.sumPaidTotalByPolicyId(POLICY_ID))
                .thenReturn(new BigDecimal("100000.00"));
        List<CommissionSchedule> paidRows = List.of(paidInstallment(1), paidInstallment(2));
        when(loadSchedulePort.findByPolicyIdAndStatus(POLICY_ID, CommissionStatus.PAID))
                .thenReturn(paidRows);

        ClawbackSweepResult result = service().sweepOn(TODAY);

        assertThat(result.flaggedPolicies()).isEqualTo(1);
        assertThat(result.flaggedInstallments()).isEqualTo(2);
        assertThat(paidRows).allSatisfy(s ->
                assertThat(s.getStatus()).isEqualTo(CommissionStatus.CLAWBACK_PENDING));

        // 환수액 = 100000.00 × (24-10)/24 = 58333.3333... → DOWN → 58333.33
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(publishPort).publishCommissionClawbackTriggered(
                eq(policy), eq(new BigDecimal("100000.00")), amount.capture());
        assertThat(amount.getValue()).isEqualByComparingTo(new BigDecimal("58333.33"));
    }

    @Test
    @DisplayName("청약철회(CANCELLED): 경과 무관 전액 환수")
    void flagsFullClawbackForCancelledPolicy() {
        Policy policy = terminal(PolicyStatus.CANCELLED, TODAY.minusDays(10));
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.SCHEDULED))
                .thenReturn(List.of());
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.PAID))
                .thenReturn(List.of(policy));
        when(loadSchedulePort.sumPaidTotalByPolicyId(POLICY_ID))
                .thenReturn(new BigDecimal("8333.33"));
        when(loadSchedulePort.findByPolicyIdAndStatus(POLICY_ID, CommissionStatus.PAID))
                .thenReturn(List.of(paidInstallment(1)));

        service().sweepOn(TODAY);

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(publishPort).publishCommissionClawbackTriggered(eq(policy), any(), amount.capture());
        assertThat(amount.getValue()).isEqualByComparingTo(new BigDecimal("8333.33"));
    }

    @Test
    @DisplayName("환수 창구(24개월) 경과 해지: 환수액 0 — PAID 유지, 이벤트 없음")
    void skipsPolicyPastClawbackWindow() {
        Policy policy = terminal(PolicyStatus.SURRENDERED,
                TODAY.minusMonths(CommissionConstants.CLAWBACK_WINDOW_MONTHS));
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.SCHEDULED))
                .thenReturn(List.of());
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.PAID))
                .thenReturn(List.of(policy));
        when(loadSchedulePort.sumPaidTotalByPolicyId(POLICY_ID))
                .thenReturn(new BigDecimal("100000.00"));

        ClawbackSweepResult result = service().sweepOn(TODAY);

        assertThat(result.flaggedPolicies()).isZero();
        assertThat(result.flaggedInstallments()).isZero();
        verify(saveSchedulePort, never()).save(any());
        verify(publishPort, never()).publishCommissionClawbackTriggered(any(), any(), any());
    }

    @Test
    @DisplayName("실효 후 소멸(EXPIRED+lapsedAt): 환수 기산 종료일은 실효일이다")
    void usesLapsedAtAsEndDateForExpiredAfterLapse() {
        // effective 2022-08-07, lapsedAt 2023-06-07 (m=10) — today 기준이면 m=48 로 환수 0 이 되는 케이스.
        // 실효일 기준이므로 환수가 트리거되어야 한다.
        LocalDate effective = TODAY.minusMonths(48);
        Policy policy = Policy.builder()
                .id(1L)
                .policyId(POLICY_ID)
                .policyNumber("POL-2022-001")
                .status(PolicyStatus.EXPIRED)
                .effectiveDate(effective)
                .lapsedAt(effective.plusMonths(10))
                .premiumAmount(new BigDecimal("50000.00"))
                .fcId("fc-100")
                .build();
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.SCHEDULED))
                .thenReturn(List.of());
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.PAID))
                .thenReturn(List.of(policy));
        when(loadSchedulePort.sumPaidTotalByPolicyId(POLICY_ID))
                .thenReturn(new BigDecimal("24000.00"));
        when(loadSchedulePort.findByPolicyIdAndStatus(POLICY_ID, CommissionStatus.PAID))
                .thenReturn(List.of(paidInstallment(1)));

        service().sweepOn(TODAY);

        // 환수액 = 24000.00 × (24-10)/24 = 14000.00
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(publishPort).publishCommissionClawbackTriggered(eq(policy), any(), amount.capture());
        assertThat(amount.getValue()).isEqualByComparingTo(new BigDecimal("14000.00"));
    }

    @Test
    @DisplayName("terminal 계약의 미지급(SCHEDULED) 회차는 CANCELLED 로 소멸한다")
    void cancelsUnpaidInstallmentsOfTerminalPolicies() {
        Policy policy = terminal(PolicyStatus.SURRENDERED, TODAY.minusMonths(3));
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.SCHEDULED))
                .thenReturn(List.of(policy));
        List<CommissionSchedule> unpaid = List.of(scheduledInstallment(5), scheduledInstallment(6));
        when(loadSchedulePort.findByPolicyIdAndStatus(POLICY_ID, CommissionStatus.SCHEDULED))
                .thenReturn(unpaid);
        when(loadPolicyPort.findTerminalWithCommissionsInStatus(CommissionStatus.PAID))
                .thenReturn(List.of());

        ClawbackSweepResult result = service().sweepOn(TODAY);

        assertThat(result.cancelledInstallments()).isEqualTo(2);
        assertThat(unpaid).allSatisfy(s ->
                assertThat(s.getStatus()).isEqualTo(CommissionStatus.CANCELLED));
    }
}
