package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidCommissionScheduleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertThat(sumOf(schedules)).isEqualTo(total);

        // 처음 11회는 기본 몫 — 구현식을 되풀이하지 않고 기대값을 직접 못박는다.
        // 100000.00 / 12 = 8333.3333... → 절사 8333.33
        for (int i = 0; i < 11; i++) {
            assertThat(schedules.get(i).getInstallmentAmount())
                    .as("%d회차", i + 1)
                    .isEqualTo(new BigDecimal("8333.33"));
        }

        // 마지막(12) 회차 = 기본 몫 + 나머지(100000.00 - 8333.33×12 = 0.04)
        assertThat(schedules.get(11).getInstallmentAmount())
                .isEqualTo(new BigDecimal("8333.37"));
    }

    /**
     * 나머지가 실려야 하는 곳은 <b>마지막 회차 하나뿐</b>임을 못박는다.
     *
     * <p>1000.07 / 12 = 83.3391666... → 기본 몫 83.33, 나머지 0.11 → 마지막 83.44.
     * 나머지를 첫 회차에 얹거나 여러 회차에 흩뿌리는 구현은 이 테스트에서 걸린다.
     */
    @Test
    @DisplayName("나머지는 오직 마지막 회차에만 가산된다")
    void createSchedule_remainderGoesToLastInstallmentOnly() {
        // Arrange — 나머지가 최대(0.11)로 발생하는 값
        BigDecimal total = new BigDecimal("1000.07");

        // Act
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                "policy-006", "fc-006", total, new BigDecimal("0.030000")
        );

        // Assert — 앞 11회는 모두 동일한 기본 몫
        assertThat(schedules.subList(0, 11))
                .extracting(CommissionSchedule::getInstallmentAmount)
                .containsOnly(new BigDecimal("83.33"));

        // 마지막 회차만 나머지 0.11 을 더 받는다
        assertThat(schedules.get(11).getInstallmentAmount()).isEqualTo(new BigDecimal("83.44"));
        assertThat(schedules.get(11).getInstallmentAmount())
                .isGreaterThan(schedules.get(0).getInstallmentAmount());

        // 합계는 여전히 총액과 정확히 일치
        assertThat(sumOf(schedules)).isEqualTo(total);
    }

    /**
     * 스윕 — 나머지 0~11 전 구간을 훑어 12행 합계 == P 가 항상 성립함을 확인한다.
     *
     * <p>나머지가 0인 값부터 최대(0.11)인 값까지, 소액·대액·홀수 끝자리를 섞는다.
     */
    @ParameterizedTest(name = "총액 {0} → 12행 합계 일치")
    @ValueSource(strings = {
            "120000.00",   // 나머지 0
            "100000.00",   // 나머지 0.04
            "1000.07",     // 나머지 0.11
            "0.12",        // 최소 유효 총액 (각 회차 0.01)
            "1.00",        // 극소
            "13.01",
            "99999.99",
            "123456.78",
            "100000000.00" // 1억
    })
    @DisplayName("스윕: 나머지 전 구간에서 12행 합계 == 총액")
    void createSchedule_sumAlwaysEqualsTotal(String totalText) {
        // Arrange
        BigDecimal total = new BigDecimal(totalText);

        // Act
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                "policy-sweep", "fc-sweep", total, new BigDecimal("0.030000")
        );

        // Assert — D4: 12행, D6: 합계 정확 일치, 모든 회차 양수
        assertThat(schedules).hasSize(CommissionConstants.INSTALLMENT_COUNT);
        assertThat(sumOf(schedules)).isEqualByComparingTo(total);
        assertThat(schedules)
                .extracting(CommissionSchedule::getInstallmentAmount)
                .allSatisfy(amount -> assertThat(amount).isPositive());
    }

    /**
     * 통화 최소단위 — 모든 회차 금액의 스케일이 V1 의 NUMERIC(19,2) 와 일치해야 한다.
     */
    @Test
    @DisplayName("모든 회차 금액 스케일은 통화 최소단위(2)")
    void createSchedule_allAmountsUseCurrencyScale() {
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                "policy-007", "fc-007", new BigDecimal("100000.00"), new BigDecimal("0.030000")
        );

        assertThat(schedules)
                .extracting(s -> s.getInstallmentAmount().scale())
                .containsOnly(CommissionConstants.AMOUNT_SCALE);
    }

    /**
     * D5 — recipient_type 은 이번 범위에서 항상 FC, parent_commission_id 는 항상 null.
     */
    @Test
    @DisplayName("D5: 전 회차 recipientType=FC, parentCommissionId=null")
    void createSchedule_recipientIsAlwaysFcWithoutParent() {
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                "policy-008", "fc-008", new BigDecimal("120000.00"), new BigDecimal("0.030000")
        );

        assertThat(schedules)
                .extracting(CommissionSchedule::getRecipientType)
                .containsOnly("FC");
        assertThat(schedules)
                .extracting(CommissionSchedule::getParentCommissionId)
                .containsOnlyNulls();
    }

    /**
     * 각 회차가 0원이 되는 총액은 거부한다 —
     * V1 의 chk_commission_installment_amount_positive (installment_amount > 0) 와 정합.
     */
    @Test
    @DisplayName("회차당 0원이 되는 총액 거부 (0.11 < 0.12)")
    void createSchedule_rejectTotalTooSmallForTwelveInstallments() {
        assertThatThrownBy(() ->
                CommissionScheduleFactory.createFirstYearSchedule(
                        "policy-001", "fc-001", new BigDecimal("0.11"), new BigDecimal("0.03")
                )
        ).isInstanceOf(InvalidCommissionScheduleException.class);
    }

    private static BigDecimal sumOf(List<CommissionSchedule> schedules) {
        return schedules.stream()
                .map(CommissionSchedule::getInstallmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
