package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase.SubmitApplicationCommand;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase.IssuedPolicySummary;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationPort;
import github.lms.lemuel.insurance.application.port.out.LoadDisclosureDeliveryPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort;
import github.lms.lemuel.insurance.application.port.out.LoadInsuranceProductPort.ProductSnapshot;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationPort.ApplicationPii;
import github.lms.lemuel.insurance.application.port.out.SaveCommissionSchedulePort;
import github.lms.lemuel.insurance.application.port.out.SavePolicyPort;
import github.lms.lemuel.insurance.application.port.out.SavePolicyPort.PolicyIssuanceAttributes;
import github.lms.lemuel.insurance.domain.ApplicationStatus;
import github.lms.lemuel.insurance.domain.CommissionConstants;
import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.ApplicationNotFoundException;
import github.lms.lemuel.insurance.domain.exception.DisclosureNotDeliveredException;
import github.lms.lemuel.insurance.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 언더라이팅 서비스 테스트 — 승인 돈 경로(계약 발행 + 수수료 12행 + 이벤트 2건)와
 * 완전판매 게이트가 핵심.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationUnderwritingService — 청약 접수·언더라이팅")
class ApplicationUnderwritingServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);
    private static final Clock FIXED = Clock.fixed(
            TODAY.atStartOfDay(KST).toInstant().plusSeconds(9 * 3600), KST);

    @Mock LoadApplicationPort loadApplicationPort;
    @Mock SaveApplicationPort saveApplicationPort;
    @Mock LoadInsuranceProductPort loadProductPort;
    @Mock LoadDisclosureDeliveryPort loadDisclosurePort;
    @Mock SavePolicyPort savePolicyPort;
    @Mock SaveCommissionSchedulePort saveSchedulePort;
    @Mock PublishInsuranceEventPort publishPort;
    @Mock AuditLogger auditLogger;

    private ApplicationUnderwritingService service() {
        return new ApplicationUnderwritingService(
                loadApplicationPort, saveApplicationPort, loadProductPort, loadDisclosurePort,
                savePolicyPort, saveSchedulePort, publishPort, FIXED, auditLogger);
    }

    private static ProductSnapshot product() {
        return new ProductSnapshot("PROD-1", "레무엘 종신보험", "LIFE",
                new BigDecimal("1200000.00"), new BigDecimal("100000000.00"),
                new BigDecimal("0.035000"), "INS-A", true);
    }

    private static InsuranceApplication underReview(SalesChannel channel, String bankCode) {
        InsuranceApplication app = InsuranceApplication.builder()
                .id(1L)
                .applicationId("11111111-1111-1111-1111-111111111111")
                .productCode("PROD-1")
                .fcId(channel == SalesChannel.BANCA ? "teller-1" : "fc-100")
                .insuredName("김피보")
                .contractorName("홍길동")
                .desiredCoverage(new BigDecimal("100000000.00"))
                .desiredPremium(new BigDecimal("1200000.00"))
                .status(ApplicationStatus.UNDER_REVIEW)
                .salesChannel(channel)
                .partnerBankCode(bankCode)
                .build();
        return app;
    }

    @Test
    @DisplayName("승인 — 계약 발행 + 수수료 12행(합계=총액) + 이벤트 2건 + 감사 기록")
    void approvesAndIssuesPolicyWithCommissionSchedule() {
        InsuranceApplication app = underReview(SalesChannel.FC, null);
        when(loadApplicationPort.findByApplicationId(app.getApplicationId()))
                .thenReturn(Optional.of(app));
        when(loadDisclosurePort.existsForApplication(app.getApplicationId())).thenReturn(true);
        when(loadProductPort.findByCode("PROD-1")).thenReturn(Optional.of(product()));
        when(savePolicyPort.insertIssued(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSchedulePort.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        IssuedPolicySummary summary = service().approve(app.getApplicationId());

        // 청약 종결
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        verify(saveApplicationPort).update(app);

        // 초년도 수수료 총액 = 1,200,000.00 × 0.035 = 42,000.00
        assertThat(summary.firstYearCommissionTotal()).isEqualByComparingTo(new BigDecimal("42000.00"));
        assertThat(summary.installmentCount()).isEqualTo(CommissionConstants.INSTALLMENT_COUNT);
        assertThat(summary.policyNumber()).startsWith("POL-20260808-");

        // 발행 계약 — ACTIVE, 효력일 = 승인일, SoR 컬럼은 attributes 로 전달
        ArgumentCaptor<Policy> policy = ArgumentCaptor.forClass(Policy.class);
        ArgumentCaptor<PolicyIssuanceAttributes> attrs =
                ArgumentCaptor.forClass(PolicyIssuanceAttributes.class);
        verify(savePolicyPort).insertIssued(policy.capture(), attrs.capture());
        assertThat(policy.getValue().getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(policy.getValue().getEffectiveDate()).isEqualTo(TODAY);
        assertThat(attrs.getValue().applicationId()).isEqualTo(app.getApplicationId());
        assertThat(attrs.getValue().coverageAmount()).isEqualByComparingTo(new BigDecimal("100000000.00"));

        // 스케줄 12행 — 합계 == 총액, 1회차 due = 효력일
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionSchedule>> schedules = ArgumentCaptor.forClass(List.class);
        verify(saveSchedulePort).saveAll(schedules.capture());
        assertThat(schedules.getValue()).hasSize(CommissionConstants.INSTALLMENT_COUNT);
        BigDecimal sum = schedules.getValue().stream()
                .map(CommissionSchedule::getInstallmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("42000.00"));
        assertThat(schedules.getValue().get(0).getDueDate()).isEqualTo(TODAY);

        // 이벤트 2건 + 감사
        verify(publishPort).publishPolicyIssued(any(Policy.class), eq(new BigDecimal("100000000.00")));
        verify(publishPort).publishCommissionConfirmed(eq(summary.policyNumber()), anyList());
        verify(auditLogger).record(any(), eq("Policy"), eq(summary.policyNumber()), any());
    }

    @Test
    @DisplayName("BANCA 승인 — 수수료 수령 주체는 판매 은행(recipientType=BANK)")
    void bancaApprovalRoutesCommissionToBank() {
        InsuranceApplication app = underReview(SalesChannel.BANCA, "BANK-KB");
        when(loadApplicationPort.findByApplicationId(app.getApplicationId()))
                .thenReturn(Optional.of(app));
        when(loadDisclosurePort.existsForApplication(app.getApplicationId())).thenReturn(true);
        when(loadProductPort.findByCode("PROD-1")).thenReturn(Optional.of(product()));
        when(savePolicyPort.insertIssued(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSchedulePort.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service().approve(app.getApplicationId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionSchedule>> schedules = ArgumentCaptor.forClass(List.class);
        verify(saveSchedulePort).saveAll(schedules.capture());
        assertThat(schedules.getValue()).allSatisfy(s -> {
            assertThat(s.getRecipientType()).isEqualTo(CommissionConstants.RECIPIENT_TYPE_BANK);
            assertThat(s.getFcId()).isEqualTo("BANK-KB");
        });
    }

    @Test
    @DisplayName("완전판매 게이트 — 교부 증빙 없으면 승인 거부, 청약은 UNDER_REVIEW 로 남는다")
    void rejectsApprovalWithoutDisclosureDelivery() {
        InsuranceApplication app = underReview(SalesChannel.FC, null);
        when(loadApplicationPort.findByApplicationId(app.getApplicationId()))
                .thenReturn(Optional.of(app));
        when(loadDisclosurePort.existsForApplication(app.getApplicationId())).thenReturn(false);

        assertThatThrownBy(() -> service().approve(app.getApplicationId()))
                .isInstanceOf(DisclosureNotDeliveredException.class);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.UNDER_REVIEW);
        verify(saveApplicationPort, never()).update(any());
        verify(savePolicyPort, never()).insertIssued(any(), any());
        verify(publishPort, never()).publishPolicyIssued(any(), any());
    }

    @Test
    @DisplayName("없는 청약 승인 시도는 404 동형 예외")
    void rejectsApprovalOfUnknownApplication() {
        when(loadApplicationPort.findByApplicationId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().approve("nope"))
                .isInstanceOf(ApplicationNotFoundException.class);
    }

    @Test
    @DisplayName("접수 — 판매 종료 상품 청약은 입구에서 거부한다")
    void rejectsSubmissionForInactiveProduct() {
        ProductSnapshot inactive = new ProductSnapshot("PROD-X", "종료 상품", "LIFE",
                new BigDecimal("1200000.00"), new BigDecimal("100000000.00"),
                new BigDecimal("0.035000"), "INS-A", false);
        when(loadProductPort.findByCode("PROD-X")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service().submit(new SubmitApplicationCommand(
                null, "PROD-X", "fc-100", "김피보", "홍길동", null, null,
                new BigDecimal("100000000.00"), new BigDecimal("1200000.00"),
                SalesChannel.FC, null)))
                .isInstanceOf(ProductNotFoundException.class);
        verify(saveApplicationPort, never()).saveNew(any(), any());
    }

    @Test
    @DisplayName("접수 — PII 는 분리 저장 경로로 전달된다")
    void submitPassesPiiToSeparateStorage() {
        when(loadProductPort.findByCode("PROD-1")).thenReturn(Optional.of(product()));
        when(saveApplicationPort.saveNew(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        String applicationId = service().submit(new SubmitApplicationCommand(
                null, "PROD-1", "fc-100", "김피보", "홍길동",
                "900101-1234567", "010-1234-5678",
                new BigDecimal("100000000.00"), new BigDecimal("1200000.00"),
                SalesChannel.FC, null));

        assertThat(applicationId).isNotBlank();
        ArgumentCaptor<ApplicationPii> pii = ArgumentCaptor.forClass(ApplicationPii.class);
        verify(saveApplicationPort).saveNew(any(InsuranceApplication.class), pii.capture());
        assertThat(pii.getValue().insuredRrn()).isEqualTo("900101-1234567");
        assertThat(pii.getValue().contractorPhone()).isEqualTo("010-1234-5678");
    }

    @Test
    @DisplayName("반려 — 사유가 기록되고 갱신된다")
    void rejectsWithReason() {
        InsuranceApplication app = underReview(SalesChannel.FC, null);
        when(loadApplicationPort.findByApplicationId(app.getApplicationId()))
                .thenReturn(Optional.of(app));

        service().reject(app.getApplicationId(), "고지의무 위반");

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(app.getRejectReason()).isEqualTo("고지의무 위반");
        verify(saveApplicationPort).update(app);
    }
}
