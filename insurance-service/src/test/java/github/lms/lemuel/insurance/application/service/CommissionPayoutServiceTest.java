package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.PayDueCommissionsUseCase.PayoutBatchResult;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수수료 회차 지급 배치 서비스 테스트 — 계약 상태 게이트(ACTIVE 만 지급)가 핵심.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommissionPayoutService — 수수료 회차 지급 배치")
class CommissionPayoutServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final String POLICY_ID = "11111111-1111-1111-1111-111111111111";

    @Mock LoadCommissionSchedulePort loadSchedulePort;
    @Mock SaveCommissionSchedulePort saveSchedulePort;
    @Mock LoadPolicyPort loadPolicyPort;
    @Mock PublishInsuranceEventPort publishPort;

    private CommissionPayoutService service() {
        return new CommissionPayoutService(loadSchedulePort, saveSchedulePort, loadPolicyPort, publishPort);
    }

    private static Policy policyIn(PolicyStatus status) {
        Policy.Builder b = Policy.builder()
                .id(1L)
                .policyId(POLICY_ID)
                .policyNumber("POL-2026-001")
                .status(status)
                .effectiveDate(LocalDate.of(2026, 1, 1))
                .premiumAmount(new BigDecimal("50000.00"))
                .fcId("fc-100");
        if (status == PolicyStatus.LAPSED) {
            b.lapsedAt(LocalDate.of(2026, 7, 1)).consecutivePremiumFailures(2);
        }
        return b.build();
    }

    private static CommissionSchedule dueSchedule() {
        return CommissionSchedule.builder()
                .id(10L)
                .commissionId("33333333-3333-3333-3333-333333333333")
                .policyId(POLICY_ID)
                .fcId("fc-100")
                .recipientType(CommissionConstants.RECIPIENT_TYPE_FC)
                .installmentNo(3)
                .installmentAmount(new BigDecimal("8333.33"))
                .firstYearTotal(new BigDecimal("100000.00"))
                .dueDate(TODAY.minusDays(1))
                .build();
    }

    @Test
    @DisplayName("ACTIVE 계약의 due 회차를 지급한다 — PAID + 지급액 기록 + commission_paid 발행")
    void paysDueInstallmentOfActivePolicy() {
        CommissionSchedule schedule = dueSchedule();
        when(loadSchedulePort.findDueScheduledOnOrBefore(TODAY)).thenReturn(List.of(schedule));
        when(loadPolicyPort.findByPolicyId(POLICY_ID)).thenReturn(Optional.of(policyIn(PolicyStatus.ACTIVE)));

        PayoutBatchResult result = service().payDueOn(TODAY);

        assertThat(result.paid()).isEqualTo(1);
        assertThat(result.held()).isZero();
        assertThat(schedule.getStatus()).isEqualTo(CommissionStatus.PAID);
        assertThat(schedule.getPaidAt()).isEqualTo(TODAY);
        assertThat(schedule.getPaidAmount()).isEqualByComparingTo(new BigDecimal("8333.33"));
        verify(saveSchedulePort).save(schedule);
        verify(publishPort).publishCommissionPaid(any(Policy.class), any(CommissionSchedule.class));
    }

    @Test
    @DisplayName("LAPSED 계약의 회차는 보류한다 — SCHEDULED 유지, 다음 배치 재시도")
    void holdsInstallmentOfLapsedPolicy() {
        CommissionSchedule schedule = dueSchedule();
        when(loadSchedulePort.findDueScheduledOnOrBefore(TODAY)).thenReturn(List.of(schedule));
        when(loadPolicyPort.findByPolicyId(POLICY_ID)).thenReturn(Optional.of(policyIn(PolicyStatus.LAPSED)));

        PayoutBatchResult result = service().payDueOn(TODAY);

        assertThat(result.paid()).isZero();
        assertThat(result.held()).isEqualTo(1);
        assertThat(schedule.getStatus()).isEqualTo(CommissionStatus.SCHEDULED);
        verify(saveSchedulePort, never()).save(any());
        verify(publishPort, never()).publishCommissionPaid(any(), any());
    }

    @Test
    @DisplayName("계약 행이 없는 고아 회차는 보류하고 지급하지 않는다")
    void holdsOrphanInstallment() {
        when(loadSchedulePort.findDueScheduledOnOrBefore(TODAY)).thenReturn(List.of(dueSchedule()));
        when(loadPolicyPort.findByPolicyId(POLICY_ID)).thenReturn(Optional.empty());

        PayoutBatchResult result = service().payDueOn(TODAY);

        assertThat(result.held()).isEqualTo(1);
        verify(saveSchedulePort, never()).save(any());
    }

    @Test
    @DisplayName("같은 계약의 여러 due 회차는 계약 1회 조회로 모두 지급된다")
    void paysMultipleInstallmentsWithSinglePolicyLookup() {
        CommissionSchedule s1 = dueSchedule();
        CommissionSchedule s2 = CommissionSchedule.builder()
                .id(11L)
                .commissionId("44444444-4444-4444-4444-444444444444")
                .policyId(POLICY_ID)
                .fcId("fc-100")
                .recipientType(CommissionConstants.RECIPIENT_TYPE_FC)
                .installmentNo(4)
                .installmentAmount(new BigDecimal("8333.33"))
                .firstYearTotal(new BigDecimal("100000.00"))
                .dueDate(TODAY)
                .build();
        when(loadSchedulePort.findDueScheduledOnOrBefore(TODAY)).thenReturn(List.of(s1, s2));
        when(loadPolicyPort.findByPolicyId(POLICY_ID)).thenReturn(Optional.of(policyIn(PolicyStatus.ACTIVE)));

        PayoutBatchResult result = service().payDueOn(TODAY);

        assertThat(result.paid()).isEqualTo(2);
        verify(loadPolicyPort).findByPolicyId(POLICY_ID);  // 캐시 — 계약 조회는 1회
    }
}
