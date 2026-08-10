package github.lms.lemuel.insurance.integration;

import github.lms.lemuel.insurance.application.port.in.CloseMonthlyCommissionUseCase;
import github.lms.lemuel.insurance.application.port.in.CloseMonthlyCommissionUseCase.MonthlyClosingResult;
import github.lms.lemuel.insurance.application.port.in.ExpirePoliciesUseCase;
import github.lms.lemuel.insurance.application.port.in.ExpirePoliciesUseCase.ExpiryBatchResult;
import github.lms.lemuel.insurance.application.port.in.PayDueCommissionsUseCase;
import github.lms.lemuel.insurance.application.port.in.PayDueCommissionsUseCase.PayoutBatchResult;
import github.lms.lemuel.insurance.application.port.in.SweepCommissionClawbacksUseCase;
import github.lms.lemuel.insurance.application.port.in.SweepCommissionClawbacksUseCase.ClawbackSweepResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 배치 3종 + 월 마감의 DB 관통 검증 — 스캔 쿼리(부분 인덱스 술어·JPQL exists·집계)와
 * 도메인 전이·Outbox 기록이 실제 PostgreSQL 에서 맞물리는지 본다.
 *
 * <p>각 테스트는 자기 UUID 로만 행을 만들고 검증한다(컨테이너 공유 — 상호 간섭 없음).
 */
@DisplayName("보험 배치 흐름 통합 (만기판정·지급·환수·월마감)")
class InsuranceBatchFlowIT extends InsuranceIntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);

    @Autowired JdbcTemplate jdbc;
    @Autowired ExpirePoliciesUseCase expirePolicies;
    @Autowired PayDueCommissionsUseCase payDueCommissions;
    @Autowired SweepCommissionClawbacksUseCase sweepClawbacks;
    @Autowired CloseMonthlyCommissionUseCase closeMonthly;
    @Autowired github.lms.lemuel.insurance.application.port.in.MonitorBancaConcentrationUseCase monitorBanca;

    // ────────────────────────────────────────────────────────────────────
    // 픽스처
    // ────────────────────────────────────────────────────────────────────

    private String insertPolicy(String status, LocalDate effective, LocalDate maturity,
                                LocalDate lapsedAt, String fcId) {
        return insertPolicy(status, effective, maturity, lapsedAt, fcId, "PROD-IT",
                new BigDecimal("50000.00"), "FC", null);
    }

    private String insertPolicy(String status, LocalDate effective, LocalDate maturity,
                                LocalDate lapsedAt, String fcId, String productCode,
                                BigDecimal premium, String salesChannel, String bankCode) {
        UUID policyId = UUID.randomUUID();
        String policyNumber = "POL-IT-" + policyId.toString().substring(0, 13);
        jdbc.update("""
                INSERT INTO opslab.insurance_policies
                    (policy_id, policy_number, application_id, fc_id, product_code, status,
                     premium_amount, coverage_amount, payment_cycle_months,
                     effective_date, maturity_date, lapsed_at, consecutive_premium_failures,
                     sales_channel, partner_bank_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, 100000000.00, 1, ?, ?, ?, ?, ?, ?)
                """,
                policyId, policyNumber, UUID.randomUUID(), fcId, productCode, status, premium,
                effective, maturity, lapsedAt, lapsedAt != null ? 2 : 0, salesChannel, bankCode);
        return policyId.toString();
    }

    private long insertSchedule(String policyId, String fcId, int no, String status,
                                LocalDate dueDate, LocalDate paidAt, BigDecimal paidAmount) {
        UUID commissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO opslab.commission_schedules
                    (commission_id, policy_id, fc_id, recipient_type, installment_no,
                     installment_amount, first_year_total, due_date, status, paid_at, paid_amount)
                VALUES (?, ?, ?, 'FC', ?, 8333.33, 100000.00, ?, ?, ?, ?)
                """,
                commissionId, UUID.fromString(policyId), fcId, no, dueDate, status,
                paidAt != null ? java.sql.Timestamp.from(paidAt.atStartOfDay(KST).toInstant()) : null,
                paidAmount);
        return jdbc.queryForObject(
                "SELECT id FROM opslab.commission_schedules WHERE commission_id = ?",
                Long.class, commissionId);
    }

    private String policyStatus(String policyId) {
        return jdbc.queryForObject(
                "SELECT status FROM opslab.insurance_policies WHERE policy_id = ?",
                String.class, UUID.fromString(policyId));
    }

    private List<Map<String, Object>> schedules(String policyId) {
        return jdbc.queryForList(
                "SELECT status, paid_amount FROM opslab.commission_schedules WHERE policy_id = ? ORDER BY installment_no",
                UUID.fromString(policyId));
    }

    private int outboxCount(String eventType, String aggregateId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
        return n != null ? n : 0;
    }

    private String policyNumber(String policyId) {
        return jdbc.queryForObject(
                "SELECT policy_number FROM opslab.insurance_policies WHERE policy_id = ?",
                String.class, UUID.fromString(policyId));
    }

    // ────────────────────────────────────────────────────────────────────
    // 만기·실효소멸 판정
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("만기 도래 ACTIVE 계약이 EXPIRED 로 종결되고 상태변경 이벤트가 Outbox 에 남는다")
    void expiresMaturedPolicy() {
        String policyId = insertPolicy("ACTIVE", TODAY.minusYears(2), TODAY.minusDays(1), null, "fc-it-1");

        ExpiryBatchResult result = expirePolicies.expireOn(TODAY);

        assertThat(result.maturedExpired()).isGreaterThanOrEqualTo(1);
        assertThat(policyStatus(policyId)).isEqualTo("EXPIRED");
        assertThat(outboxCount("InsurancePolicyStatusChanged", policyNumber(policyId))).isEqualTo(1);
    }

    @Test
    @DisplayName("부활 창구 도과 LAPSED 계약이 EXPIRED 로 소멸한다 — 미도과 계약은 그대로")
    void expiresLapsedPolicyPastWindow() {
        String overdue = insertPolicy("LAPSED", TODAY.minusYears(3), null, TODAY.minusMonths(25), "fc-it-2");
        String withinWindow = insertPolicy("LAPSED", TODAY.minusYears(1), null, TODAY.minusMonths(3), "fc-it-2");

        expirePolicies.expireOn(TODAY);

        assertThat(policyStatus(overdue)).isEqualTo("EXPIRED");
        assertThat(policyStatus(withinWindow)).isEqualTo("LAPSED");
    }

    // ────────────────────────────────────────────────────────────────────
    // 수수료 회차 지급
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE 계약의 due 회차가 지급되고 commission_paid 가 Outbox 에 남는다 — LAPSED 는 보류")
    void paysDueInstallmentsOfActivePoliciesOnly() {
        String active = insertPolicy("ACTIVE", TODAY.minusMonths(6), TODAY.plusYears(1), null, "fc-it-3");
        insertSchedule(active, "fc-it-3", 1, "SCHEDULED", TODAY.minusDays(2), null, null);
        String lapsed = insertPolicy("LAPSED", TODAY.minusMonths(6), null, TODAY.minusMonths(1), "fc-it-3");
        insertSchedule(lapsed, "fc-it-3", 1, "SCHEDULED", TODAY.minusDays(2), null, null);

        PayoutBatchResult result = payDueCommissions.payDueOn(TODAY);

        assertThat(result.paid()).isGreaterThanOrEqualTo(1);
        Map<String, Object> paidRow = schedules(active).get(0);
        assertThat(paidRow.get("status")).isEqualTo("PAID");
        assertThat((BigDecimal) paidRow.get("paid_amount")).isEqualByComparingTo(new BigDecimal("8333.33"));
        assertThat(schedules(lapsed).get(0).get("status")).isEqualTo("SCHEDULED");
        assertThat(outboxCount("InsuranceCommissionPaid", policyNumber(active))).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────────
    // 환수 스윕
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("해지 계약: 미지급 회차 소멸 + 기지급 회차 환수 대기 + 환수 이벤트 1건")
    void sweepsSurrenderedPolicy() {
        // 효력 10개월 경과 해지 — 기지급 2회(16666.66), 미지급 1회
        String policyId = insertPolicy("SURRENDERED", TODAY.minusMonths(10), null, null, "fc-it-4");
        insertSchedule(policyId, "fc-it-4", 1, "PAID", TODAY.minusMonths(2), TODAY.minusMonths(2), new BigDecimal("8333.33"));
        insertSchedule(policyId, "fc-it-4", 2, "PAID", TODAY.minusMonths(1), TODAY.minusMonths(1), new BigDecimal("8333.33"));
        insertSchedule(policyId, "fc-it-4", 3, "SCHEDULED", TODAY.plusMonths(1), null, null);

        ClawbackSweepResult result = sweepClawbacks.sweepOn(TODAY);

        assertThat(result.flaggedPolicies()).isGreaterThanOrEqualTo(1);
        List<Map<String, Object>> rows = schedules(policyId);
        assertThat(rows.get(0).get("status")).isEqualTo("CLAWBACK_PENDING");
        assertThat(rows.get(1).get("status")).isEqualTo("CLAWBACK_PENDING");
        assertThat(rows.get(2).get("status")).isEqualTo("CANCELLED");
        assertThat(outboxCount("InsuranceCommissionClawbackTriggered", policyNumber(policyId))).isEqualTo(1);
    }

    @Test
    @DisplayName("환수 창구 경과 해지 계약은 PAID 를 유지한다 — 확정 지급")
    void leavesPaidRowsForPoliciesPastClawbackWindow() {
        String policyId = insertPolicy("SURRENDERED", TODAY.minusMonths(30), null, null, "fc-it-5");
        insertSchedule(policyId, "fc-it-5", 1, "PAID", TODAY.minusMonths(28), TODAY.minusMonths(28), new BigDecimal("8333.33"));

        sweepClawbacks.sweepOn(TODAY);

        assertThat(schedules(policyId).get(0).get("status")).isEqualTo("PAID");
        assertThat(outboxCount("InsuranceCommissionClawbackTriggered", policyNumber(policyId))).isZero();
    }

    // ────────────────────────────────────────────────────────────────────
    // 월 마감
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("월 마감: FC별 당월 지급 합계 스냅샷 + 재실행 멱등 + append-only 강제")
    void closesMonthIdempotentlyAndAppendOnly() {
        // 다른 테스트와 겹치지 않는 전용 월(2026-03)에 지급 이력을 만든다.
        YearMonth march = YearMonth.of(2026, 3);
        String fcId = "fc-it-close-" + UUID.randomUUID().toString().substring(0, 8);
        String policyId = insertPolicy("ACTIVE", march.atDay(1).minusMonths(2), TODAY.plusYears(1), null, fcId);
        insertSchedule(policyId, fcId, 1, "PAID", march.atDay(5), march.atDay(5), new BigDecimal("8333.33"));
        insertSchedule(policyId, fcId, 2, "PAID", march.atDay(20), march.atDay(20), new BigDecimal("8333.33"));
        // 4월 지급분은 3월 마감에 포함되면 안 된다
        insertSchedule(policyId, fcId, 3, "PAID", march.atEndOfMonth().plusDays(1),
                march.atEndOfMonth().plusDays(1), new BigDecimal("8333.33"));

        MonthlyClosingResult first = closeMonthly.closeMonth(march);
        assertThat(first.closedFcCount()).isGreaterThanOrEqualTo(1);

        Map<String, Object> closing = jdbc.queryForMap("""
                SELECT total_paid_amount, installment_count FROM opslab.commission_closings
                 WHERE fc_id = ? AND closing_month = ?
                """, fcId, java.sql.Date.valueOf(march.atDay(1)));
        assertThat((BigDecimal) closing.get("total_paid_amount"))
                .isEqualByComparingTo(new BigDecimal("16666.66"));
        assertThat(((Number) closing.get("installment_count")).intValue()).isEqualTo(2);

        // 재실행 — 이미 마감된 FC 는 스킵되고 행이 늘지 않는다
        closeMonthly.closeMonth(march);
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.commission_closings WHERE fc_id = ?", Integer.class, fcId);
        assertThat(rows).isEqualTo(1);

        // append-only — 마감 스냅샷 UPDATE 는 DB 트리거가 거부한다 (P0001 RAISE → DataAccessException)
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE opslab.commission_closings SET total_paid_amount = 0 WHERE fc_id = ?", fcId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    // ────────────────────────────────────────────────────────────────────
    // 방카 25%룰
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("방카 25%룰: 은행 × 부문 × 원수사 집계가 위반을 찾아내고 이벤트를 남긴다 — 자산 미등록 은행은 fail-closed 적용")
    void detectsBancaConcentrationViolation() {
        // 전용 상품 2개 (원수사 A/B, 생보) — 전용 연도(2025)로 다른 테스트와 격리.
        // 은행은 자산 레지스트리에 미등록 → 적용 대상(fail-closed).
        String prodA = "PROD-BC-A-" + UUID.randomUUID().toString().substring(0, 8);
        String prodB = "PROD-BC-B-" + UUID.randomUUID().toString().substring(0, 8);
        String bank = "BANK-IT-" + UUID.randomUUID().toString().substring(0, 8);
        insertProduct(prodA, "INS-IT-A");
        insertProduct(prodB, "INS-IT-B");
        LocalDate effective = LocalDate.of(2025, 3, 1);
        // 은행 생보 풀 총 100만: INS-A 90만(90% → 위반) / INS-B 10만
        insertPolicy("ACTIVE", effective, null, null, "fc-it-bc", prodA,
                new BigDecimal("900000.00"), "BANCA", bank);
        insertPolicy("ACTIVE", effective, null, null, "fc-it-bc", prodB,
                new BigDecimal("100000.00"), "BANCA", bank);

        var result = monitorBanca.checkYear(java.time.Year.of(2025));

        assertThat(result.violations())
                .anySatisfy(v -> {
                    assertThat(v.bankCode()).isEqualTo(bank);
                    assertThat(v.sector()).isEqualTo(github.lms.lemuel.insurance.domain.InsurerSector.LIFE);
                    assertThat(v.insurerCode()).isEqualTo("INS-IT-A");
                    assertThat(v.share()).isEqualByComparingTo(new BigDecimal("0.9000"));
                });
        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'InsuranceBancaRuleViolated' AND aggregate_id = ?",
                Integer.class, bank);
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("방카 25%룰: 자산 2조 미만으로 등록된 은행은 면제 — 100% 집중이어도 이벤트가 없다")
    void exemptsBankRegisteredUnderAssetThreshold() {
        String prod = "PROD-BC-EX-" + UUID.randomUUID().toString().substring(0, 8);
        String bank = "BANK-EX-" + UUID.randomUUID().toString().substring(0, 8);
        insertProduct(prod, "INS-IT-EX");
        insertPartnerBank(bank, "1000000000000.00");   // 1조 — 적용 하한(2조) 미만
        insertPolicy("ACTIVE", LocalDate.of(2025, 5, 1), null, null, "fc-it-ex", prod,
                new BigDecimal("500000.00"), "BANCA", bank);   // 단일 원수사 100% 집중

        var result = monitorBanca.checkYear(java.time.Year.of(2025));

        assertThat(result.violations()).noneMatch(v -> v.bankCode().equals(bank));
        assertThat(result.exemptBankCount()).isGreaterThanOrEqualTo(1);
        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'InsuranceBancaRuleViolated' AND aggregate_id = ?",
                Integer.class, bank);
        assertThat(events).isZero();
    }

    private void insertProduct(String productCode, String insurerCode) {
        jdbc.update("""
                INSERT INTO opslab.insurance_products
                    (product_code, product_name, product_type, annual_premium, coverage_amount,
                     first_year_commission_rate, subsequent_year_commission_rate, active,
                     insurer_code, insurer_sector)
                VALUES (?, 'IT 상품', 'LIFE', 1200000.00, 100000000.00, 0.035000, 0.010000, TRUE, ?, 'LIFE')
                """, productCode, insurerCode);
    }

    private void insertPartnerBank(String bankCode, String totalAssets) {
        jdbc.update("""
                INSERT INTO opslab.banca_partner_banks (bank_code, bank_name, total_assets, as_of_date)
                VALUES (?, 'IT 은행', ?::numeric, '2025-12-31')
                """, bankCode, totalAssets);
    }

    @Test
    @DisplayName("월 마감 이벤트가 FC 단위로 Outbox 에 남는다")
    void publishesMonthlyClosedEventPerFc() {
        YearMonth feb = YearMonth.of(2026, 2);
        String fcId = "fc-it-evt-" + UUID.randomUUID().toString().substring(0, 8);
        String policyId = insertPolicy("ACTIVE", feb.atDay(1).minusMonths(1), TODAY.plusYears(1), null, fcId);
        insertSchedule(policyId, fcId, 1, "PAID", feb.atDay(10), feb.atDay(10), new BigDecimal("8333.33"));

        closeMonthly.closeMonth(feb);

        assertThat(outboxCount("InsuranceCommissionMonthlyClosed", fcId)).isEqualTo(1);
    }
}
