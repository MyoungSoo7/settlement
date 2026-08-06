package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidCommissionScheduleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CommissionScheduleFactory 테스트 — D6 환수 24개월 + D4 선지급 12회 정확성.
 *
 * <p>초년도 수수료 총액 P 를 정확히 12행으로 분할하고,
 * 나머지(P mod 12)를 마지막 회차에 가산하여
 * 12회 합계 == P 가 1원 오차 없이 성립함을 검증한다.
 */
@DisplayName("CommissionScheduleFactory 단위 테스트")
class CommissionScheduleFactoryTest {

    /**
     * 대표값: 수수료 총액 120,000원 (12로 정확히 나누어짐).
     *
     * <p>기대: 각 회차 10,000원 × 12회.
     */
    @Test
    @DisplayName("총액이 12로 정확히 나누어지는 경우: 각 회차 동일 금액")
    void createSchedule_whenTotalDivisibleBy12_thenAllInstallmentsEqual() {
        // Arrange
        BigDecimal total = new BigDecimal("120000.00");
        String policyId = "policy-001";
        String fcId = "fc-001";
        BigDecimal rate = new BigDecimal("0.030000");

        // Act
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                policyId, fcId, total, rate
        );

        // Assert — D4: 정확히 12행
        assertThat(schedules).hasSize(CommissionConstants.INSTALLMENT_COUNT);
        // D6: 12회 합계 = 총액 (1원 오차 없이)
        BigDecimal sum = schedules.stream()
                .map(CommissionSchedule::getInstallmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualTo(total);
        // 각 회차 == 10000.00
        for (CommissionSchedule schedule : schedules) {
            assertThat(schedule.getInstallmentAmount()).isEqualTo(new BigDecimal("10000.00"));
        }
    }

    /**
     * 경계값: 수수료 총액 100,000원 (12로 나누면 나머지 4원).
     *
     * <p>기대: 처음 11회는 8,333.33원, 마지막 회차는 8,333.37원 (8333.33 + 0.04 = 합계 100000).
     */
    @Test
    @DisplayName("총액이 12로 나누어떨어지지 않는 경우: 나머지를 마지막 회차에 가산")
    void createSchedule_whenTotalNotDivisibleBy12_thenRemainderInLastInstallment() {
        // Arrange
        BigDecimal total = new BigDecimal("100000.00");
        String policyId = "policy-002";
        String fcId = "fc-002";
        BigDecimal rate = new BigDecimal("0.030000");

        // Act
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                policyId, fcId, total, rate
        );

        // Assert — D4: 정확히 12행
        assertThat(schedules).hasSize(CommissionConstants.INSTALLMENT_COUNT);

        // D6: 12회 합계 = 총액 (1원 오차 없이)
        BigDecimal sum = schedules.stream()
                .map(CommissionSchedule::getInstallmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualTo(total);

        // 처음 11회는 기본 몫
        BigDecimal baseAmount = total.divide(new BigDecimal("12"), 2, java.math.RoundingMode.DOWN);
        for (int i = 0; i < 11; i++) {
            assertThat(schedules.get(i).getInstallmentAmount()).isEqualTo(baseAmount);
        }

        // 마지막 회차는 나머지를 포함
        BigDecimal expectedLast = total.subtract(baseAmount.multiply(new BigDecimal("11")));
        assertThat(schedules.get(11).getInstallmentAmount()).isEqualTo(expectedLast);
    }

    /**
     * 경계값: 매우 작은 금액 (1원).
     *
     * <p>기대: 모든 회차는 0.08원(1/12 내림)이지만, 마지막 회차가 나머지를 흡수.
     */
    @Test
    @DisplayName("극소 금액: 1원을 12회로 분할")
    void createSchedule_whenTotalIs1Won_thenHandlesCorrectly() {
        // Arrange
        BigDecimal total = new BigDecimal("1.00");
        String policyId = "policy-003";
        String fcId = "fc-003";
        BigDecimal rate = new BigDecimal("0.030000");

        // Act
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                policyId, fcId, total, rate
        );

        // Assert
        assertThat(schedules).hasSize(CommissionConstants.INSTALLMENT_COUNT);
        BigDecimal sum = schedules.stream()
                .map(CommissionSchedule::getInstallmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualTo(total);
    }

    /**
     * 경계값: 큰 금액 (100,000,000원 — 1억).
     *
     * <p>기대: 정확히 12회로 분할되고 합계 정확.
     */
    @Test
    @DisplayName("대액: 1억원을 12회로 분할")
    void createSchedule_whenTotalIsLarge_thenHandlesCorrectly() {
        // Arrange
        BigDecimal total = new BigDecimal("100000000.00");
        String policyId = "policy-004";
        String fcId = "fc-004";
        BigDecimal rate = new BigDecimal("0.030000");

        // Act
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                policyId, fcId, total, rate
        );

        // Assert
        assertThat(schedules).hasSize(CommissionConstants.INSTALLMENT_COUNT);
        BigDecimal sum = schedules.stream()
                .map(CommissionSchedule::getInstallmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualTo(total);
    }

    /**
     * 설치일 회차 검증: 회차 번호가 1부터 12까지.
     */
    @Test
    @DisplayName("회차 번호: 1부터 12까지 순서대로")
    void createSchedule_checkInstallmentNumbers() {
        // Arrange
        BigDecimal total = new BigDecimal("120000.00");
        String policyId = "policy-005";
        String fcId = "fc-005";
        BigDecimal rate = new BigDecimal("0.030000");

        // Act
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                policyId, fcId, total, rate
        );

        // Assert
        for (int i = 0; i < schedules.size(); i++) {
            assertThat(schedules.get(i).getInstallmentNo()).isEqualTo(i + 1);
        }
    }

    /**
     * null 입력 검증.
     */
    @Test
    @DisplayName("null 입력 거부: policyId")
    void createSchedule_rejectNullPolicyId() {
        assertThatThrownBy(() ->
                CommissionScheduleFactory.createFirstYearSchedule(
                        null, "fc-001", new BigDecimal("100000"), new BigDecimal("0.03")
                )
        ).isInstanceOf(InvalidCommissionScheduleException.class);
    }

    @Test
    @DisplayName("null 입력 거부: fcId")
    void createSchedule_rejectNullFcId() {
        assertThatThrownBy(() ->
                CommissionScheduleFactory.createFirstYearSchedule(
                        "policy-001", null, new BigDecimal("100000"), new BigDecimal("0.03")
                )
        ).isInstanceOf(InvalidCommissionScheduleException.class);
    }

    @Test
    @DisplayName("null 입력 거부: total")
    void createSchedule_rejectNullTotal() {
        assertThatThrownBy(() ->
                CommissionScheduleFactory.createFirstYearSchedule(
                        "policy-001", "fc-001", null, new BigDecimal("0.03")
                )
        ).isInstanceOf(InvalidCommissionScheduleException.class);
    }

    @Test
    @DisplayName("null 입력 거부: rate")
    void createSchedule_rejectNullRate() {
        assertThatThrownBy(() ->
                CommissionScheduleFactory.createFirstYearSchedule(
                        "policy-001", "fc-001", new BigDecimal("100000"), null
                )
        ).isInstanceOf(InvalidCommissionScheduleException.class);
    }

    /**
     * 음수·0 거부.
     */
    @Test
    @DisplayName("음수 총액 거부")
    void createSchedule_rejectNegativeTotal() {
        assertThatThrownBy(() ->
                CommissionScheduleFactory.createFirstYearSchedule(
                        "policy-001", "fc-001", new BigDecimal("-100000"), new BigDecimal("0.03")
                )
        ).isInstanceOf(InvalidCommissionScheduleException.class);
    }

    @Test
    @DisplayName("0원 총액 거부")
    void createSchedule_rejectZeroTotal() {
        assertThatThrownBy(() ->
                CommissionScheduleFactory.createFirstYearSchedule(
                        "policy-001", "fc-001", new BigDecimal("0.00"), new BigDecimal("0.03")
                )
        ).isInstanceOf(InvalidCommissionScheduleException.class);
    }
}
