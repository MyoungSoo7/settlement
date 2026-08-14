package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidSalesChannelException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V6 판매채널(방카슈랑스) 도메인 테스트.
 *
 * <p>채널 불변식(BANCA ↔ 판매 은행 존재)과 채널 기반 수수료 수령 주체 결정이 핵심.
 */
@DisplayName("SalesChannel — 판매채널(FC/BANCA)")
class SalesChannelTest {

    private static Policy.Builder basePolicy() {
        return Policy.builder()
                .policyNumber("POL-2026-100")
                .status(PolicyStatus.ACTIVE)
                .effectiveDate(LocalDate.of(2026, 1, 1))
                .premiumAmount(new BigDecimal("50000.00"))
                .fcId("fc-100");
    }

    @Test
    @DisplayName("채널 미지정 계약은 FC 채널로 기본 설정된다 (V6 이전 데이터 호환)")
    void defaultsToFcChannel() {
        Policy policy = basePolicy().build();

        assertThat(policy.getSalesChannel()).isEqualTo(SalesChannel.FC);
        assertThat(policy.getPartnerBankCode()).isNull();
        assertThat(policy.commissionRecipientId()).isEqualTo("fc-100");
    }

    @Test
    @DisplayName("BANCA 계약의 수수료 수령 주체는 판매 은행이다")
    void bancaPolicyRecipientIsBank() {
        Policy policy = basePolicy()
                .salesChannel(SalesChannel.BANCA)
                .partnerBankCode("BANK-KB")
                .build();

        assertThat(policy.commissionRecipientId()).isEqualTo("BANK-KB");
        assertThat(policy.getSalesChannel().commissionRecipientType())
                .isEqualTo(CommissionConstants.RECIPIENT_TYPE_BANK);
    }

    @Test
    @DisplayName("BANCA 인데 판매 은행이 없으면 거부한다 — chk_policy_banca_bank 와 동일 불변식")
    void rejectsBancaWithoutBank() {
        assertThatThrownBy(() -> basePolicy().salesChannel(SalesChannel.BANCA).build())
                .isInstanceOf(InvalidSalesChannelException.class);
    }

    @Test
    @DisplayName("FC 인데 판매 은행이 지정되면 거부한다")
    void rejectsFcWithBank() {
        assertThatThrownBy(() -> basePolicy().partnerBankCode("BANK-KB").build())
                .isInstanceOf(InvalidSalesChannelException.class);
    }

    @Test
    @DisplayName("BANCA 채널 수수료 스케줄 — 전 회차 recipientType=BANK, 수령자는 은행 코드")
    void bancaScheduleUsesBankRecipient() {
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                "policy-uuid-1", "BANK-KB", new BigDecimal("100000.00"),
                new BigDecimal("0.03"), SalesChannel.BANCA);

        assertThat(schedules).hasSize(CommissionConstants.INSTALLMENT_COUNT);
        assertThat(schedules).allSatisfy(s -> {
            assertThat(s.getRecipientType()).isEqualTo(CommissionConstants.RECIPIENT_TYPE_BANK);
            assertThat(s.getFcId()).isEqualTo("BANK-KB");
        });
        // 분할 불변식은 채널과 무관하게 유지 — 12회 합계 == 초년도 총액
        BigDecimal sum = schedules.stream()
                .map(CommissionSchedule::getInstallmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    @Test
    @DisplayName("기존 FC 시그니처는 recipientType=FC 를 유지한다 (하위호환)")
    void legacyFactorySignatureKeepsFcRecipient() {
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                "policy-uuid-1", "fc-100", new BigDecimal("100000.00"), new BigDecimal("0.03"));

        assertThat(schedules).allSatisfy(s ->
                assertThat(s.getRecipientType()).isEqualTo(CommissionConstants.RECIPIENT_TYPE_FC));
    }
}
