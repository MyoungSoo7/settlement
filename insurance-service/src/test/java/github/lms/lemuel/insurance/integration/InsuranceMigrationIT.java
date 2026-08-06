package github.lms.lemuel.insurance.integration;

import github.lms.lemuel.InsuranceServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB 마이그레이션 구조 검증 — V1 도메인 테이블의 제약조건과 D4·D5 설계 결정을 실 PostgreSQL 로 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>D4: commission_schedules 의 UNIQUE(policy_id, recipient_type, fc_id, installment_no) 제약</li>
 *   <li>D5: recipient_type, parent_commission_id 가 NULL 허용 컬럼임을 확인</li>
 *   <li>D7: insurance_policies 의 status CHECK 제약 (5개 상태만 허용)</li>
 *   <li>D2: consultations 의 status CHECK 제약 (5개 상태만 허용)</li>
 *   <li>servicing_changes 의 append-only 트리거 (UPDATE 거부)</li>
 *   <li>policy_number 의 UNIQUE 제약 (3단 멱등성 L3)</li>
 * </ul>
 */
@SpringBootTest(
        classes = InsuranceServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class InsuranceMigrationIT {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("insurance_test_migration").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    // ────────────────────────────────────────────────────────────────────
    // D4: 수수료 스케줄 UNIQUE 제약 검증
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D4: commission_schedules — UNIQUE(policy_id, recipient_type, fc_id, installment_no) 위반 시 DataIntegrityViolationException")
    void d4CommissionUniqueConstraintEnforced() {
        UUID policyId = UUID.randomUUID();
        UUID commId1 = UUID.randomUUID();
        UUID commId2 = UUID.randomUUID();

        // 첫 번째 row 삽입 (installment_no=1)
        jdbc.update("""
                INSERT INTO opslab.commission_schedules
                    (commission_id, policy_id, fc_id, recipient_type, installment_no,
                     installment_amount, first_year_total, due_date, status)
                VALUES (?, ?, 'fc-001', 'FC', 1, 100000.00, 1200000.00, '2026-09-01', 'SCHEDULED')
                """, commId1, policyId);

        // 같은 (policy_id, recipient_type, fc_id, installment_no) 로 두 번째 row — 위반
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO opslab.commission_schedules
                    (commission_id, policy_id, fc_id, recipient_type, installment_no,
                     installment_amount, first_year_total, due_date, status)
                VALUES (?, ?, 'fc-001', 'FC', 1, 100000.00, 1200000.00, '2026-09-01', 'SCHEDULED')
                """, commId2, policyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("D4: 같은 policy_id·fc_id 라도 installment_no 가 다르면 12행 모두 삽입 가능")
    void d4TwelveInstallmentsCanCoexist() {
        UUID policyId = UUID.randomUUID();

        // 12회차 모두 삽입
        for (int i = 1; i <= 12; i++) {
            final int installmentNo = i;
            // 나머지는 마지막 회차에 가산 (D6) — 11회 × 100000 + 마지막 1회 100012 = 1200012? 여기선 테스트 단순화
            long amount = (installmentNo < 12) ? 100000L : 100000L;
            jdbc.update("""
                    INSERT INTO opslab.commission_schedules
                        (commission_id, policy_id, fc_id, recipient_type, installment_no,
                         installment_amount, first_year_total, due_date, status)
                    VALUES (?, ?, 'fc-twin', 'FC', ?, ?, 1200000.00, '2026-09-01', 'SCHEDULED')
                    """, UUID.randomUUID(), policyId, installmentNo, new java.math.BigDecimal(amount));
        }

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.commission_schedules WHERE policy_id = ?",
                Long.class, policyId);
        assertThat(count).isEqualTo(12L);
    }

    @Test
    @DisplayName("D4: installment_no 범위 위반 (0, 13) 시 CHECK 제약 실패")
    void d4InstallmentNoOutOfRangeRejected() {
        UUID policyId = UUID.randomUUID();

        // installment_no = 0 → 위반
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO opslab.commission_schedules
                    (commission_id, policy_id, fc_id, recipient_type, installment_no,
                     installment_amount, first_year_total, due_date, status)
                VALUES (?, ?, 'fc-x', 'FC', 0, 100000.00, 1200000.00, '2026-09-01', 'SCHEDULED')
                """, UUID.randomUUID(), policyId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // installment_no = 13 → 위반
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO opslab.commission_schedules
                    (commission_id, policy_id, fc_id, recipient_type, installment_no,
                     installment_amount, first_year_total, due_date, status)
                VALUES (?, ?, 'fc-x', 'FC', 13, 100000.00, 1200000.00, '2026-09-01', 'SCHEDULED')
                """, UUID.randomUUID(), policyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ────────────────────────────────────────────────────────────────────
    // D5: nullable 컬럼 확인
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D5: recipient_type 과 parent_commission_id 는 NULL 허용 컬럼이다")
    void d5NullableColumnsExistWithNullAllowed() {
        UUID policyId = UUID.randomUUID();

        // recipient_type = NULL, parent_commission_id = NULL 로 삽입 가능
        jdbc.update("""
                INSERT INTO opslab.commission_schedules
                    (commission_id, policy_id, fc_id, recipient_type, parent_commission_id,
                     installment_no, installment_amount, first_year_total, due_date, status)
                VALUES (?, ?, 'fc-d5', NULL, NULL, 1, 100000.00, 1200000.00, '2026-09-01', 'SCHEDULED')
                """, UUID.randomUUID(), policyId);

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.commission_schedules WHERE policy_id = ? AND recipient_type IS NULL",
                Long.class, policyId);
        assertThat(count).isEqualTo(1L);
    }

    // ────────────────────────────────────────────────────────────────────
    // D7: Policy 상태 CHECK 제약
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D7: insurance_policies — 허용 5개 상태(ACTIVE,LAPSED,SURRENDERED,EXPIRED,CANCELLED)만 삽입 가능")
    void d7PolicyStatusCheckConstraint() {
        // 허용 상태들 삽입 OK
        for (String status : new String[]{"ACTIVE", "LAPSED", "SURRENDERED", "EXPIRED", "CANCELLED"}) {
            jdbc.update("""
                    INSERT INTO opslab.insurance_policies
                        (policy_id, policy_number, application_id, fc_id, product_code, status,
                         premium_amount, coverage_amount, effective_date)
                    VALUES (?, ?, ?, 'fc-st', 'PROD-001', ?,
                            50000.00, 100000000.00, '2026-01-01')
                    """, UUID.randomUUID(), "POL-" + status, UUID.randomUUID(), status);
        }

        // 허용되지 않은 상태 → CHECK 위반
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO opslab.insurance_policies
                    (policy_id, policy_number, application_id, fc_id, product_code, status,
                     premium_amount, coverage_amount, effective_date)
                VALUES (?, 'POL-INVALID', ?, 'fc-st', 'PROD-001', 'INVALID_STATUS',
                        50000.00, 100000000.00, '2026-01-01')
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("D7: policy_number UNIQUE 제약 — 중복 증권번호 삽입 시 위반 (3단 멱등성 L3)")
    void d7PolicyNumberUniqueConstraint() {
        String policyNumber = "POL-UNIQUE-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO opslab.insurance_policies
                    (policy_id, policy_number, application_id, fc_id, product_code, status,
                     premium_amount, coverage_amount, effective_date)
                VALUES (?, ?, ?, 'fc-uq', 'PROD-001', 'ACTIVE',
                        50000.00, 100000000.00, '2026-01-01')
                """, UUID.randomUUID(), policyNumber, UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO opslab.insurance_policies
                    (policy_id, policy_number, application_id, fc_id, product_code, status,
                     premium_amount, coverage_amount, effective_date)
                VALUES (?, ?, ?, 'fc-uq', 'PROD-001', 'ACTIVE',
                        50000.00, 100000000.00, '2026-01-01')
                """, UUID.randomUUID(), policyNumber, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ────────────────────────────────────────────────────────────────────
    // D2: Consultation 상태 CHECK 제약
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D2: consultations — 허용 5개 상태만 삽입 가능, 그 외 CHECK 위반")
    void d2ConsultationStatusCheckConstraint() {
        for (String status : new String[]{"NEW", "ASSIGNED", "IN_PROGRESS", "COMPLETED", "LOST"}) {
            jdbc.update("""
                    INSERT INTO opslab.consultations
                        (consultation_id, fc_id, lead_name, status)
                    VALUES (?, 'fc-cs', 'Lead Name', ?)
                    """, UUID.randomUUID(), status);
        }

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO opslab.consultations
                    (consultation_id, fc_id, lead_name, status)
                VALUES (?, 'fc-cs', 'Lead Name', 'PENDING')
                """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ────────────────────────────────────────────────────────────────────
    // servicing_changes append-only 트리거
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("servicing_changes — UPDATE 시도 시 append-only 트리거가 예외를 던진다")
    void servicingChangesIsAppendOnly() {
        UUID changeId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO opslab.servicing_changes (change_id, policy_id, change_type)
                VALUES (?, ?, 'PREMIUM_FAILURE')
                """, changeId, policyId);

        // UPDATE 시도 → 트리거가 예외
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE opslab.servicing_changes SET change_type = 'MODIFIED' WHERE change_id = ?
                """, changeId))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("append-only");
    }

    // ────────────────────────────────────────────────────────────────────
    // outbox_events UNIQUE event_id 제약 (V2)
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V2: outbox_events — 동일 event_id 2회 삽입 시 UNIQUE 위반 (3단 멱등성 L1)")
    void v2OutboxEventIdUniqueConstraint() {
        UUID eventId = UUID.randomUUID();
        String sql = """
                INSERT INTO opslab.outbox_events
                    (aggregate_type, aggregate_id, event_type, event_id, payload,
                     status, occurred_at)
                VALUES ('Insurance', ?, 'PolicyIssued', ?, '{}', 'PENDING', NOW())
                """;

        jdbc.update(sql, UUID.randomUUID().toString(), eventId);

        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID().toString(), eventId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
