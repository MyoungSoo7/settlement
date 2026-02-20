package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * Settlement 도메인 단위 테스트
 * 수수료 계산 및 실 지급액 로직 검증
 */
class SettlementTest {

    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.03"); // 3% 수수료

    @Test
    @DisplayName("정산 생성 시 수수료와 실 지급액 계산")
    void testCreateSettlement_CalculateNetAmount() {
        // Given
        Long paymentId = 1L;
        Long orderId = 100L;
        BigDecimal paymentAmount = new BigDecimal("10000");
        LocalDate settlementDate = LocalDate.of(2024, 1, 15);

        // When
        Settlement settlement = Settlement.createFromPayment(
                paymentId, 
                orderId, 
                paymentAmount, 
                settlementDate
        );

        // Then
        // 수수료: 10000 * 0.03 = 300
        BigDecimal expectedCommission = new BigDecimal("300.00");
        // 실 지급액: 10000 - 300 = 9700
        BigDecimal expectedNetAmount = new BigDecimal("9700.00");

        assertThat(settlement.getCommission()).isEqualByComparingTo(expectedCommission);
        assertThat(settlement.getNetAmount()).isEqualByComparingTo(expectedNetAmount);
        assertThat(settlement.getPaymentAmount()).isEqualByComparingTo(paymentAmount);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    @DisplayName("수수료 계산 정확도 테스트 - 소수점 둘째자리 반올림")
    void testCalculateCommission_Precision() {
        // Given
        BigDecimal amount1 = new BigDecimal("10001"); // 10001 * 0.03 = 300.03
        BigDecimal amount2 = new BigDecimal("10003"); // 10003 * 0.03 = 300.09

        // When
        Settlement settlement1 = Settlement.createFromPayment(1L, 1L, amount1, LocalDate.now());
        Settlement settlement2 = Settlement.createFromPayment(2L, 2L, amount2, LocalDate.now());

        // Then
        assertThat(settlement1.getCommission()).isEqualByComparingTo(new BigDecimal("300.03"));
        assertThat(settlement1.getNetAmount()).isEqualByComparingTo(new BigDecimal("9700.97"));

        assertThat(settlement2.getCommission()).isEqualByComparingTo(new BigDecimal("300.09"));
        assertThat(settlement2.getNetAmount()).isEqualByComparingTo(new BigDecimal("9702.91"));
    }

    @Test
    @DisplayName("PENDING 상태에서 confirm() 호출 시 CONFIRMED로 전이")
    void testConfirm_FromPending() {
        // Given
        Settlement settlement = Settlement.createFromPayment(
                1L, 1L, new BigDecimal("5000"), LocalDate.now()
        );
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);

        // When
        settlement.confirm();

        // Then
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(settlement.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("WAITING_APPROVAL 상태에서 confirm() 호출 시 CONFIRMED로 전이")
    void testConfirm_FromWaitingApproval() {
        // Given
        Settlement settlement = Settlement.createFromPayment(
                1L, 1L, new BigDecimal("5000"), LocalDate.now()
        );
        settlement.setStatus(SettlementStatus.WAITING_APPROVAL);

        // When
        settlement.confirm();

        // Then
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }

    @Test
    @DisplayName("CONFIRMED 상태에서 confirm() 시도 시 예외 발생")
    void testConfirm_FromConfirmed_ThrowsException() {
        // Given
        Settlement settlement = Settlement.createFromPayment(
                1L, 1L, new BigDecimal("5000"), LocalDate.now()
        );
        settlement.confirm();

        // When & Then
        assertThatThrownBy(() -> settlement.confirm())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only PENDING or WAITING_APPROVAL settlements can be confirmed");
    }

    @Test
    @DisplayName("PENDING/WAITING_APPROVAL 상태에서 cancel() 호출 시 CANCELED로 전이")
    void testCancel() {
        // Given
        Settlement settlement1 = Settlement.createFromPayment(
                1L, 1L, new BigDecimal("3000"), LocalDate.now()
        );
        Settlement settlement2 = Settlement.createFromPayment(
                2L, 2L, new BigDecimal("4000"), LocalDate.now()
        );
        settlement2.setStatus(SettlementStatus.WAITING_APPROVAL);

        // When
        settlement1.cancel();
        settlement2.cancel();

        // Then
        assertThat(settlement1.getStatus()).isEqualTo(SettlementStatus.CANCELED);
        assertThat(settlement2.getStatus()).isEqualTo(SettlementStatus.CANCELED);
    }

    @Test
    @DisplayName("CONFIRMED 상태에서 cancel() 시도 시 예외 발생")
    void testCancel_FromConfirmed_ThrowsException() {
        // Given
        Settlement settlement = Settlement.createFromPayment(
                1L, 1L, new BigDecimal("5000"), LocalDate.now()
        );
        settlement.confirm();

        // When & Then
        assertThatThrownBy(() -> settlement.cancel())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED settlements cannot be canceled");
    }

    @Test
    @DisplayName("isPending() - PENDING 또는 WAITING_APPROVAL일 때 true")
    void testIsPending() {
        Settlement settlement1 = Settlement.createFromPayment(
                1L, 1L, new BigDecimal("1000"), LocalDate.now()
        );
        Settlement settlement2 = Settlement.createFromPayment(
                2L, 2L, new BigDecimal("2000"), LocalDate.now()
        );
        settlement2.setStatus(SettlementStatus.WAITING_APPROVAL);

        Settlement settlement3 = Settlement.createFromPayment(
                3L, 3L, new BigDecimal("3000"), LocalDate.now()
        );
        settlement3.confirm();

        assertThat(settlement1.isPending()).isTrue();
        assertThat(settlement2.isPending()).isTrue();
        assertThat(settlement3.isPending()).isFalse();
    }

    @Test
    @DisplayName("isConfirmed() - CONFIRMED일 때만 true")
    void testIsConfirmed() {
        Settlement settlement1 = Settlement.createFromPayment(
                1L, 1L, new BigDecimal("1000"), LocalDate.now()
        );
        Settlement settlement2 = Settlement.createFromPayment(
                2L, 2L, new BigDecimal("2000"), LocalDate.now()
        );
        settlement2.confirm();

        assertThat(settlement1.isConfirmed()).isFalse();
        assertThat(settlement2.isConfirmed()).isTrue();
    }

    @Test
    @DisplayName("정산 금액이 0 이하일 경우 예외 발생")
    void testCreateSettlement_InvalidAmount() {
        // When & Then
        assertThatThrownBy(() -> Settlement.createFromPayment(
                1L, 1L, BigDecimal.ZERO, LocalDate.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amount must be greater than zero");

        assertThatThrownBy(() -> Settlement.createFromPayment(
                1L, 1L, new BigDecimal("-100"), LocalDate.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amount must be greater than zero");
    }

    @Test
    @DisplayName("paymentId가 null일 경우 예외 발생")
    void testCreateSettlement_NullPaymentId() {
        // When & Then
        assertThatThrownBy(() -> Settlement.createFromPayment(
                null, 1L, new BigDecimal("1000"), LocalDate.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payment ID must be a positive number");
    }
}
