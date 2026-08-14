package github.lms.lemuel.insurance.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약 발행 팩토리 + 승인 경로 수수료 스케줄(dueDate 확정) 테스트.
 */
@DisplayName("Policy.issue — 계약 발행 팩토리")
class PolicyIssueTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 8, 8);

    @Test
    @DisplayName("발행 계약은 ACTIVE 로 시작하고 policyId·증권번호가 채번된다")
    void issuesActivePolicyWithGeneratedIdentifiers() {
        Policy policy = Policy.issue(EFFECTIVE, EFFECTIVE.plusYears(10),
                new BigDecimal("1200000.00"), "fc-100", SalesChannel.FC, null);

        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(policy.getPolicyId()).isNotBlank();
        assertThat(policy.getPolicyNumber()).startsWith("POL-20260808-");
        assertThat(policy.getConsecutivePremiumFailures()).isZero();
        assertThat(policy.getLapsedAt()).isNull();
        assertThat(policy.getId()).isNull();  // 미저장
    }

    @Test
    @DisplayName("종신형은 만기 없이 발행된다 — 만기 배치 스캔 대상에서 제외")
    void issuesWholeLifePolicyWithoutMaturity() {
        Policy policy = Policy.issue(EFFECTIVE, null,
                new BigDecimal("1200000.00"), "fc-100", SalesChannel.FC, null);

        assertThat(policy.getMaturityDate()).isNull();
    }

    @Test
    @DisplayName("BANCA 발행 — 채널·은행이 보존되고 수수료 수령 주체는 은행이다")
    void issuesBancaPolicy() {
        Policy policy = Policy.issue(EFFECTIVE, null,
                new BigDecimal("1200000.00"), "teller-1", SalesChannel.BANCA, "BANK-KB");

        assertThat(policy.getSalesChannel()).isEqualTo(SalesChannel.BANCA);
        assertThat(policy.commissionRecipientId()).isEqualTo("BANK-KB");
    }

    @Test
    @DisplayName("승인 경로 스케줄 — 회차 n 의 지급 예정일은 효력일 + (n-1)개월이다")
    void firstYearScheduleFixesMonthlyDueDates() {
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                "11111111-1111-1111-1111-111111111111", "fc-100",
                new BigDecimal("100000.00"), new BigDecimal("0.035"),
                SalesChannel.FC, EFFECTIVE);

        assertThat(schedules).hasSize(CommissionConstants.INSTALLMENT_COUNT);
        assertThat(schedules.get(0).getDueDate()).isEqualTo(EFFECTIVE);                 // 1회차 즉시 due
        assertThat(schedules.get(1).getDueDate()).isEqualTo(EFFECTIVE.plusMonths(1));
        assertThat(schedules.get(11).getDueDate()).isEqualTo(EFFECTIVE.plusMonths(11));

        // 분할 불변식 유지 — 12회 합계 == 초년도 총액
        BigDecimal sum = schedules.stream()
                .map(CommissionSchedule::getInstallmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    @Test
    @DisplayName("firstDueDate 없는 기존 오버로드는 dueDate 미확정(null)을 유지한다")
    void legacyOverloadKeepsDueDateUnset() {
        List<CommissionSchedule> schedules = CommissionScheduleFactory.createFirstYearSchedule(
                "11111111-1111-1111-1111-111111111111", "fc-100",
                new BigDecimal("100000.00"), new BigDecimal("0.035"), SalesChannel.FC);

        assertThat(schedules).hasSize(CommissionConstants.INSTALLMENT_COUNT);
        assertThat(schedules).allSatisfy(s -> assertThat(s.getDueDate()).isNull());
    }
}
