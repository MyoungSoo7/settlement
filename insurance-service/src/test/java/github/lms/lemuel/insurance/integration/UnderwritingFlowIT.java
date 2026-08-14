package github.lms.lemuel.insurance.integration;

import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase;
import github.lms.lemuel.insurance.application.port.in.RecordDisclosureDeliveryUseCase.RecordDeliveryCommand;
import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase;
import github.lms.lemuel.insurance.application.port.in.SubmitApplicationUseCase.SubmitApplicationCommand;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase;
import github.lms.lemuel.insurance.application.port.in.UnderwriteApplicationUseCase.IssuedPolicySummary;
import github.lms.lemuel.insurance.domain.SalesChannel;
import github.lms.lemuel.insurance.domain.exception.DisclosureNotDeliveredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 청약 접수 → 상품설명서 교부 → 심사 → 승인(계약 발행 + 수수료 12행 + 이벤트)의
 * 전 구간을 실제 PostgreSQL 로 관통 검증한다 — 배치가 소비하는 commission_schedules 행이
 * 이 시스템 안에서 자연 생성되는 경로의 완성이다.
 */
@DisplayName("언더라이팅 흐름 통합 (접수→교부→심사→승인)")
class UnderwritingFlowIT extends InsuranceIntegrationTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired SubmitApplicationUseCase submitApplication;
    @Autowired UnderwriteApplicationUseCase underwrite;
    @Autowired RecordDisclosureDeliveryUseCase recordDisclosure;

    private String insertProduct(String insurerCode) {
        String productCode = "PROD-UW-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO opslab.insurance_products
                    (product_code, product_name, product_type, annual_premium, coverage_amount,
                     first_year_commission_rate, subsequent_year_commission_rate, active, insurer_code)
                VALUES (?, '언더라이팅 IT 상품', 'LIFE', 1200000.00, 100000000.00, 0.035000, 0.010000, TRUE, ?)
                """, productCode, insurerCode);
        return productCode;
    }

    private String submit(String productCode) {
        return submitApplication.submit(new SubmitApplicationCommand(
                null, productCode, "fc-uw-1", "김피보", "홍길동",
                "900101-1234567", "010-1234-5678",
                new BigDecimal("100000000.00"), new BigDecimal("1200000.00"),
                SalesChannel.FC, null));
    }

    private void deliverDisclosure(String applicationId, String productCode) {
        recordDisclosure.record(new RecordDeliveryCommand(
                applicationId, productCode, SalesChannel.FC, "fc-uw-1", null, "홍길동"));
    }

    @Test
    @DisplayName("접수→교부→심사→승인: 계약 ACTIVE + 수수료 12행(합계=42,000) + 이벤트 2건 + PII 암호화")
    void approvesEndToEnd() {
        String productCode = insertProduct("INS-UW-A");
        String applicationId = submit(productCode);
        deliverDisclosure(applicationId, productCode);
        underwrite.startReview(applicationId);

        IssuedPolicySummary summary = underwrite.approve(applicationId);

        // 청약 종결 + 시각 스탬프
        Map<String, Object> app = jdbc.queryForMap(
                "SELECT status, reviewed_at, decided_at FROM opslab.insurance_applications WHERE application_id = ?",
                UUID.fromString(applicationId));
        assertThat(app.get("status")).isEqualTo("APPROVED");
        assertThat(app.get("reviewed_at")).isNotNull();
        assertThat(app.get("decided_at")).isNotNull();

        // 계약 발행 — ACTIVE, 채널·SoR 컬럼 보존
        Map<String, Object> policy = jdbc.queryForMap(
                "SELECT status, application_id, product_code, coverage_amount, sales_channel FROM opslab.insurance_policies WHERE policy_number = ?",
                summary.policyNumber());
        assertThat(policy.get("status")).isEqualTo("ACTIVE");
        assertThat(policy.get("application_id")).isEqualTo(UUID.fromString(applicationId));
        assertThat(policy.get("product_code")).isEqualTo(productCode);
        assertThat((BigDecimal) policy.get("coverage_amount"))
                .isEqualByComparingTo(new BigDecimal("100000000.00"));
        assertThat(policy.get("sales_channel")).isEqualTo("FC");

        // 수수료 12행 — 합계 = 1,200,000 × 0.035 = 42,000.00 (1원 오차 없음)
        List<Map<String, Object>> schedules = jdbc.queryForList(
                "SELECT installment_amount, status, due_date FROM opslab.commission_schedules WHERE policy_id = ? ORDER BY installment_no",
                UUID.fromString(summary.policyId()));
        assertThat(schedules).hasSize(12);
        BigDecimal sum = schedules.stream()
                .map(r -> (BigDecimal) r.get("installment_amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("42000.00"));
        assertThat(schedules).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("SCHEDULED");
            assertThat(r.get("due_date")).isNotNull();
        });

        // 이벤트 2건 — 같은 tx 의 Outbox
        Integer issued = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'InsurancePolicyIssued' AND aggregate_id = ?",
                Integer.class, summary.policyNumber());
        Integer confirmed = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'InsuranceCommissionConfirmed' AND aggregate_id = ?",
                Integer.class, summary.policyNumber());
        assertThat(issued).isEqualTo(1);
        assertThat(confirmed).isEqualTo(1);

        // PII 분리 저장 — raw 칼럼은 암호문(enc:v1:) 이어야 한다
        String rawRrn = jdbc.queryForObject(
                "SELECT encrypted_rrn FROM opslab.insured_person_pii WHERE application_id = ?",
                String.class, UUID.fromString(applicationId));
        assertThat(rawRrn).startsWith("enc:v1:").doesNotContain("900101");
    }

    @Test
    @DisplayName("완전판매 게이트: 교부 없이 승인하면 409 동형 예외 + 청약은 UNDER_REVIEW 유지")
    void blocksApprovalWithoutDisclosure() {
        String productCode = insertProduct("INS-UW-B");
        String applicationId = submit(productCode);
        underwrite.startReview(applicationId);

        assertThatThrownBy(() -> underwrite.approve(applicationId))
                .isInstanceOf(DisclosureNotDeliveredException.class);

        String status = jdbc.queryForObject(
                "SELECT status FROM opslab.insurance_applications WHERE application_id = ?",
                String.class, UUID.fromString(applicationId));
        assertThat(status).isEqualTo("UNDER_REVIEW");
        Integer policies = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.insurance_policies WHERE application_id = ?",
                Integer.class, UUID.fromString(applicationId));
        assertThat(policies).isZero();

        // 교부 후에는 승인이 통과된다 — 게이트는 차단이 아니라 순서 강제다
        deliverDisclosure(applicationId, productCode);
        IssuedPolicySummary summary = underwrite.approve(applicationId);
        assertThat(summary.installmentCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("BANCA 청약 승인 — 수수료 수령 주체가 판매 은행(BANK)으로 확정된다")
    void bancaApprovalRoutesCommissionToBank() {
        String productCode = insertProduct("INS-UW-C");
        String bank = "BANK-UW-" + UUID.randomUUID().toString().substring(0, 6);
        String applicationId = submitApplication.submit(new SubmitApplicationCommand(
                null, productCode, "teller-1", "김피보", "홍길동", null, null,
                new BigDecimal("100000000.00"), new BigDecimal("1200000.00"),
                SalesChannel.BANCA, bank));
        recordDisclosure.record(new RecordDeliveryCommand(
                applicationId, productCode, SalesChannel.BANCA, "teller-1", bank, "홍길동"));
        underwrite.startReview(applicationId);

        IssuedPolicySummary summary = underwrite.approve(applicationId);

        List<Map<String, Object>> schedules = jdbc.queryForList(
                "SELECT recipient_type, fc_id FROM opslab.commission_schedules WHERE policy_id = ?",
                UUID.fromString(summary.policyId()));
        assertThat(schedules).hasSize(12).allSatisfy(r -> {
            assertThat(r.get("recipient_type")).isEqualTo("BANK");
            assertThat(r.get("fc_id")).isEqualTo(bank);
        });
    }

    @Test
    @DisplayName("반려 — REJECTED + 사유가 기록되고 계약은 생기지 않는다")
    void rejectsApplication() {
        String productCode = insertProduct("INS-UW-D");
        String applicationId = submit(productCode);
        underwrite.startReview(applicationId);

        underwrite.reject(applicationId, "고지의무 위반 이력");

        Map<String, Object> app = jdbc.queryForMap(
                "SELECT status, reject_reason FROM opslab.insurance_applications WHERE application_id = ?",
                UUID.fromString(applicationId));
        assertThat(app.get("status")).isEqualTo("REJECTED");
        assertThat(app.get("reject_reason")).isEqualTo("고지의무 위반 이력");
    }
}
