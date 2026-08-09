package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase.GeneralPayoutSummary;
import github.lms.lemuel.insurance.application.port.in.PayRequestedGeneralPayoutsUseCase.GeneralPayoutBatchResult;
import github.lms.lemuel.insurance.application.port.out.LoadGeneralPayoutPort;
import github.lms.lemuel.insurance.application.port.out.LoadPolicyPort;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.application.port.out.SaveGeneralPayoutPort;
import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.GeneralPayoutCalculator.PayoutQuote;
import github.lms.lemuel.insurance.domain.GeneralPayoutStatus;
import github.lms.lemuel.insurance.domain.GeneralPayoutType;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 일반지급 실행 배치 + 내역 조회 검증 — REQUESTED 전건 지급, 지급 1건당 이벤트,
 * 산출근거 포함 조회.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneralPayoutService — 일반지급 실행 배치·조회 (§14)")
class GeneralPayoutServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);
    private static final String POLICY_ID = "33333333-3333-3333-3333-333333333333";
    private static final String POLICY_NUMBER = "POL-20200101-cccc3333";

    @Mock LoadGeneralPayoutPort loadPayoutPort;
    @Mock SaveGeneralPayoutPort savePayoutPort;
    @Mock LoadPolicyPort loadPolicyPort;
    @Mock PublishInsuranceEventPort publishPort;

    private GeneralPayoutService service() {
        return new GeneralPayoutService(loadPayoutPort, savePayoutPort, loadPolicyPort, publishPort);
    }

    private static GeneralPayout requested(String amount) {
        return GeneralPayout.request(POLICY_ID, POLICY_NUMBER, GeneralPayoutType.SURRENDER_REFUND,
                new PayoutQuote(new BigDecimal(amount), new BigDecimal("3700000.00"),
                        new BigDecimal("0.6000"), 36, 37),
                TODAY.minusDays(1));
    }

    @Test
    @DisplayName("배치: REQUESTED 전건을 PAID 로 전이하고 건당 paid 이벤트를 남긴다")
    void paysAllRequestedPayouts() {
        GeneralPayout first = requested("2220000.00");
        GeneralPayout second = requested("100000.00");
        when(loadPayoutPort.findRequested()).thenReturn(List.of(first, second));

        GeneralPayoutBatchResult result = service().payRequestedOn(TODAY);

        assertThat(result.paid()).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(GeneralPayoutStatus.PAID);
        assertThat(first.getPaidOn()).isEqualTo(TODAY);
        assertThat(second.getStatus()).isEqualTo(GeneralPayoutStatus.PAID);
        verify(savePayoutPort).save(first);
        verify(savePayoutPort).save(second);
        verify(publishPort).publishGeneralPayoutPaid(first);
        verify(publishPort).publishGeneralPayoutPaid(second);
    }

    @Test
    @DisplayName("배치: 대상이 없으면 저장·발행 없이 0 건")
    void paysNothingWhenNoneRequested() {
        when(loadPayoutPort.findRequested()).thenReturn(List.of());

        GeneralPayoutBatchResult result = service().payRequestedOn(TODAY);

        assertThat(result.paid()).isZero();
        verify(savePayoutPort, never()).save(any());
        verify(publishPort, never()).publishGeneralPayoutPaid(any());
    }

    @Test
    @DisplayName("조회: 계약의 payout 을 산출근거 포함 요약으로 반환한다")
    void listsPayoutsWithCalculationBasis() {
        Policy policy = Policy.builder()
                .id(1L)
                .policyId(POLICY_ID)
                .policyNumber(POLICY_NUMBER)
                .status(PolicyStatus.SURRENDERED)
                .effectiveDate(LocalDate.of(2020, 1, 1))
                .premiumAmount(new BigDecimal("1200000.00"))
                .fcId("fc-100")
                .build();
        when(loadPolicyPort.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy));
        when(loadPayoutPort.findByPolicyId(POLICY_ID)).thenReturn(List.of(requested("2220000.00")));

        List<GeneralPayoutSummary> summaries = service().byPolicyNumber(POLICY_NUMBER);

        assertThat(summaries).hasSize(1);
        GeneralPayoutSummary summary = summaries.get(0);
        assertThat(summary.amount()).isEqualByComparingTo("2220000.00");
        assertThat(summary.paidPremiumTotal()).isEqualByComparingTo("3700000.00");
        assertThat(summary.appliedRate()).isEqualByComparingTo("0.60");
        assertThat(summary.installmentCount()).isEqualTo(37);
        assertThat(summary.status()).isEqualTo(GeneralPayoutStatus.REQUESTED);
    }

    @Test
    @DisplayName("조회: 없는 계약은 404")
    void listUnknownPolicy() {
        when(loadPolicyPort.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().byPolicyNumber(POLICY_NUMBER))
                .isInstanceOf(PolicyNotFoundException.class);
    }
}
