package github.lms.lemuel.insurance.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부팅 스모크 — 실 PostgreSQL(Testcontainers)에서 Flyway 가 opslab 스키마를 만들고
 * Spring 컨텍스트가 기동되는지 확인한다.
 *
 * <p>확인 항목:
 * <ul>
 *   <li>Outbox 폴러가 opslab.outbox_events 를 요구하므로 V2 가 올바르게 적용되어야 부팅 성공</li>
 *   <li>AuditLogJpaEntity(shared-common)가 audit_logs 를 요구하므로 V3 필수</li>
 *   <li>ddl-auto=validate 가 shared-common JPA 엔티티와 DB 스키마 정합을 강제</li>
 *   <li>V1 도메인 테이블이 모두 생성되었는지 확인</li>
 * </ul>
 */
class InsuranceBootIT extends InsuranceIntegrationTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway V2: outbox_events·processed_events·shedlock 이 opslab 스키마에 생성된다")
    void flywayCreatesOutboxInfrastructure() {
        assertThat(tableExists("outbox_events")).isTrue();
        assertThat(tableExists("processed_events")).isTrue();
        assertThat(tableExists("shedlock")).isTrue();
    }

    @Test
    @DisplayName("Flyway V3: audit_logs 파티션드 테이블이 생성된다 (AuditLogJpaEntity ddl-auto:validate 필요)")
    void flywayCreatesAuditLogs() {
        assertThat(tableExists("audit_logs")).isTrue();
    }

    @Test
    @DisplayName("Flyway V11: application_documents 테이블이 생성된다 (청약서류 OCR 대사, ADR 0036)")
    void flywayCreatesApplicationDocuments() {
        assertThat(tableExists("application_documents")).isTrue();
    }

    @Test
    @DisplayName("Flyway V1: insurance 도메인 테이블 8종이 모두 생성된다")
    void flywayCreatesInsuranceDomainTables() {
        assertThat(tableExists("insurance_products")).isTrue();
        assertThat(tableExists("consultations")).isTrue();
        assertThat(tableExists("insured_person_pii")).isTrue();
        assertThat(tableExists("contractor_pii")).isTrue();
        assertThat(tableExists("insurance_applications")).isTrue();
        assertThat(tableExists("insurance_policies")).isTrue();
        assertThat(tableExists("policy_coverages")).isTrue();
        assertThat(tableExists("servicing_changes")).isTrue();
        assertThat(tableExists("commission_schedules")).isTrue();
    }

    @Test
    @DisplayName("Flyway 마이그레이션이 V1 → V12 순서로 적용된다")
    void flywayAppliesMigrationsInOrder() {
        List<String> versions = jdbc.queryForList("""
                SELECT version FROM opslab.flyway_schema_history
                 WHERE version IS NOT NULL
                 ORDER BY installed_rank
                """, String.class);
        assertThat(versions).containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    @Test
    @DisplayName("opslab 스키마가 생성된다 (Flyway schemas: opslab)")
    void opslabSchemaIsCreated() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'opslab'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'opslab' AND table_name = ?
                """, Integer.class, table);
        return n != null && n > 0;
    }
}
