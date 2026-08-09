package github.lms.lemuel.insurance.integration;

import github.lms.lemuel.insurance.application.port.in.ExpirePoliciesUseCase;
import github.lms.lemuel.insurance.application.port.in.PayRequestedGeneralPayoutsUseCase;
import github.lms.lemuel.insurance.application.port.in.PayRequestedGeneralPayoutsUseCase.GeneralPayoutBatchResult;
import github.lms.lemuel.insurance.application.port.in.PolicyTerminationResult;
import github.lms.lemuel.insurance.application.port.in.SurrenderPolicyUseCase;
import github.lms.lemuel.insurance.application.port.in.SurrenderPolicyUseCase.SurrenderPolicyCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 일반지급(§14) 의 DB 관통 검증 — 해지 유스케이스가 payout 행과 Outbox 를 남기고,
 * 실행 배치가 REQUESTED 를 지급하며, V10 유니크 제약이 이중 생성을 막는지 본다.
 *
 * <p>각 테스트는 자기 UUID 로만 행을 만들고 검증한다(컨테이너 공유 — 상호 간섭 없음).
 */
@DisplayName("일반지급 흐름 통합 (해지→payout→배치지급, V10)")
class GeneralPayoutFlowIT extends InsuranceIntegrationTestSupport {

    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

    @Autowired JdbcTemplate jdbc;
    @Autowired SurrenderPolicyUseCase surrenderPolicy;
    @Autowired PayRequestedGeneralPayoutsUseCase payRequested;
    @Autowired ExpirePoliciesUseCase expirePolicies;

    private record PolicyRow(String policyId, String policyNumber) {
    }

    private PolicyRow insertPolicy(String status, LocalDate effective, LocalDate maturity,
                                   String fcId, BigDecimal annualPremium) {
        UUID policyId = UUID.randomUUID();
        String policyNumber = "POL-GP-" + policyId.toString().substring(0, 13);
        jdbc.update("""
                INSERT INTO opslab.insurance_policies
                    (policy_id, policy_number, application_id, fc_id, product_code, status,
                     premium_amount, coverage_amount, payment_cycle_months,
                     effective_date, maturity_date, lapsed_at, consecutive_premium_failures,
                     sales_channel, partner_bank_code)
                VALUES (?, ?, ?, ?, 'PROD-GP', ?, ?, 100000000.00, 1, ?, ?, NULL, 0, 'FC', NULL)
                """,
                policyId, policyNumber, UUID.randomUUID(), fcId, status, annualPremium,
                effective, maturity);
        return new PolicyRow(policyId.toString(), policyNumber);
    }

    private List<Map<String, Object>> payoutRows(String policyId) {
        return jdbc.queryForList(
                "SELECT payout_type, amount, paid_premium_total, applied_rate, status, paid_on"
                        + " FROM opslab.general_payouts WHERE policy_id = ? ORDER BY id",
                UUID.fromString(policyId));
    }

    private int outboxCount(String eventType, String aggregateId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
        return n != null ? n : 0;
    }

    @Test
    @DisplayName("해지: 전이 + 해약환급금 payout 행 + 상태변경·지급요청 Outbox 가 한 흐름으로 남는다")
    void surrenderCreatesPayoutRowAndEvents() {
        PolicyRow row = insertPolicy("ACTIVE", TODAY.minusMonths(36), TODAY.plusYears(5),
                "fc-gp-1", new BigDecimal("1200000.00"));

        PolicyTerminationResult result = surrenderPolicy.surrender(
                new SurrenderPolicyCommand(row.policyNumber(), "fc-gp-1"));

        // 전이 + 결과 요약
        assertThat(jdbc.queryForObject(
                "SELECT status FROM opslab.insurance_policies WHERE policy_id = ?",
                String.class, UUID.fromString(row.policyId()))).isEqualTo("SURRENDERED");
        assertThat(result.payout().amount()).isEqualByComparingTo("2220000.00"); // 37회 × 60%

        // payout 행 — 산출근거 스냅샷 (D-G5)
        List<Map<String, Object>> payouts = payoutRows(row.policyId());
        assertThat(payouts).hasSize(1);
        assertThat(payouts.get(0).get("payout_type")).isEqualTo("SURRENDER_REFUND");
        assertThat((BigDecimal) payouts.get(0).get("amount")).isEqualByComparingTo("2220000.00");
        assertThat((BigDecimal) payouts.get(0).get("paid_premium_total")).isEqualByComparingTo("3700000.00");
        assertThat((BigDecimal) payouts.get(0).get("applied_rate")).isEqualByComparingTo("0.60");
        assertThat(payouts.get(0).get("status")).isEqualTo("REQUESTED");

        // Outbox — 상태변경 + 지급요청 각 1건
        assertThat(outboxCount("InsurancePolicyStatusChanged", row.policyNumber())).isEqualTo(1);
        assertThat(outboxCount("InsuranceGeneralPayoutRequested", row.policyNumber())).isEqualTo(1);
    }

    @Test
    @DisplayName("경과 12개월 미만 해지: 전이만 되고 payout 행·지급요청 이벤트가 없다 (D-G3)")
    void earlySurrenderLeavesNoPayout() {
        PolicyRow row = insertPolicy("ACTIVE", TODAY.minusMonths(3), TODAY.plusYears(5),
                "fc-gp-2", new BigDecimal("1200000.00"));

        PolicyTerminationResult result = surrenderPolicy.surrender(
                new SurrenderPolicyCommand(row.policyNumber(), "fc-gp-2"));

        assertThat(result.payout()).isNull();
        assertThat(payoutRows(row.policyId())).isEmpty();
        assertThat(outboxCount("InsuranceGeneralPayoutRequested", row.policyNumber())).isZero();
    }

    @Test
    @DisplayName("실행 배치: REQUESTED 가 PAID 로 전이되고 paid_on 과 paid 이벤트가 남는다")
    void batchPaysRequestedPayouts() {
        PolicyRow row = insertPolicy("ACTIVE", TODAY.minusMonths(40), TODAY.plusYears(5),
                "fc-gp-3", new BigDecimal("600000.00"));
        surrenderPolicy.surrender(new SurrenderPolicyCommand(row.policyNumber(), "fc-gp-3"));

        GeneralPayoutBatchResult result = payRequested.payRequestedOn(TODAY);

        assertThat(result.paid()).isGreaterThanOrEqualTo(1);
        Map<String, Object> payout = payoutRows(row.policyId()).get(0);
        assertThat(payout.get("status")).isEqualTo("PAID");
        assertThat(payout.get("paid_on")).isNotNull();
        assertThat(outboxCount("InsuranceGeneralPayoutPaid", row.policyNumber())).isEqualTo(1);
    }

    @Test
    @DisplayName("만기소멸 배치가 만기보험금 payout 을 낳는다 — 기납입 100%")
    void maturityExpiryCreatesMaturityPayout() {
        PolicyRow row = insertPolicy("ACTIVE", TODAY.minusYears(2).minusDays(1), TODAY.minusDays(1),
                "fc-gp-4", new BigDecimal("1200000.00"));

        expirePolicies.expireOn(TODAY);

        List<Map<String, Object>> payouts = payoutRows(row.policyId());
        assertThat(payouts).hasSize(1);
        assertThat(payouts.get(0).get("payout_type")).isEqualTo("MATURITY_BENEFIT");
        assertThat((BigDecimal) payouts.get(0).get("applied_rate")).isEqualByComparingTo("1");
        assertThat(outboxCount("InsuranceGeneralPayoutRequested", row.policyNumber())).isEqualTo(1);
    }

    @Test
    @DisplayName("V10 멱등 최후 방어: 같은 계약·유형의 이중 INSERT 는 DB 유니크가 거부한다")
    void uniqueConstraintBlocksDuplicatePayout() {
        PolicyRow row = insertPolicy("ACTIVE", TODAY.minusMonths(36), TODAY.plusYears(5),
                "fc-gp-5", new BigDecimal("1200000.00"));
        surrenderPolicy.surrender(new SurrenderPolicyCommand(row.policyNumber(), "fc-gp-5"));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO opslab.general_payouts
                    (payout_id, policy_id, policy_number, payout_type, amount,
                     paid_premium_total, applied_rate, elapsed_months, installment_count,
                     status, requested_on)
                VALUES (?, ?, ?, 'SURRENDER_REFUND', 1.00, 1.00, 0.4000, 36, 37, 'REQUESTED', ?)
                """, UUID.randomUUID(), UUID.fromString(row.policyId()), row.policyNumber(), TODAY))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_general_payout_policy_type");
    }
}
