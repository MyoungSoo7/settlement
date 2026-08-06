package github.lms.lemuel.insurance.integration;

import github.lms.lemuel.InsuranceServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@SpringBootTest(
        classes = InsuranceServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class InsuranceBootIT {

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
            .withDatabaseName("insurance_test").withUsername("test").withPassword("test");

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
    @DisplayName("Flyway 마이그레이션이 V1 → V2 → V3 순서로 적용된다")
    void flywayAppliesMigrationsInOrder() {
        List<String> versions = jdbc.queryForList("""
                SELECT version FROM opslab.flyway_schema_history
                 WHERE version IS NOT NULL
                 ORDER BY installed_rank
                """, String.class);
        assertThat(versions).containsExactly("1", "2", "3");
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
