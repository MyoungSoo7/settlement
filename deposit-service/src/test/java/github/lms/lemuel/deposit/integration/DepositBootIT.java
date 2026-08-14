package github.lms.lemuel.deposit.integration;

import github.lms.lemuel.DepositServiceApplication;
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
 * deposit-service 부팅 IT — 실 Flyway 체인 + Hibernate {@code ddl-auto: validate}.
 *
 * <p>deposit 에는 지금까지 IT 가 없어 마이그레이션↔엔티티 드리프트를 기계로 잡을 장치가 없었다
 * (게이트 가짜 GREEN 경로). 이 IT 는 실 PostgreSQL 에 Flyway 체인을 전부 적용하고 컨텍스트를 띄워
 * validate 를 통과시키는 것 자체가 어서션이다 — 신규 테이블(deposit_proofs, ADR 0036)을 포함해
 * DDL 과 JPA 매핑이 어긋나면 부팅이 실패한다 (loan {@code SchemaEnumContractIT} 레시피).
 */
@SpringBootTest(
        classes = DepositServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class DepositBootIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("deposit_test").withUsername("test").withPassword("test");

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
    @DisplayName("Flyway 체인 + ddl-auto:validate 로 컨텍스트가 뜬다 — 코어·증빙 테이블 존재")
    void flywayCreatesTablesAndEntitiesValidate() {
        assertThat(tableExists("deposit_accounts")).isTrue();
        assertThat(tableExists("deposit_entries")).isTrue();
        assertThat(tableExists("deposit_holds")).isTrue();
        assertThat(tableExists("deposit_proofs")).isTrue();
    }

    @Test
    @DisplayName("Flyway 로스터에 증빙 마이그레이션(V20260814140000)이 포함된다")
    void flywayRosterContainsProofMigration() {
        List<String> versions = jdbc.queryForList("""
                SELECT version FROM opslab.flyway_schema_history
                 WHERE version IS NOT NULL
                 ORDER BY installed_rank
                """, String.class);
        assertThat(versions).contains("1", "2", "3", "20260814140000");
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'opslab' AND table_name = ?
                """, Integer.class, table);
        return n != null && n > 0;
    }
}
