package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
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
 * 컨텍스트가 기동되는지 확인한다. Outbox 폴러가 opslab.outbox_events 를 하드코딩하므로
 * 스키마명이 어긋나면 여기서 먼저 깨져야 한다.
 */
@SpringBootTest(
        classes = CardServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class CardBootIT {

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
            .withDatabaseName("card_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway 가 opslab 스키마에 outbox·processed_events·shedlock 을 만든다")
    void flywayCreatesInfrastructureTables() {
        assertThat(tableExists("outbox_events")).isTrue();
        assertThat(tableExists("processed_events")).isTrue();
        assertThat(tableExists("shedlock")).isTrue();
        assertThat(tableExists("audit_logs")).isTrue();
    }

    @Test
    @DisplayName("Flyway 가 V4 카드 코어(card_accounts·cards·프로젝션 3종)를 만든다")
    void flywayCreatesCardCoreTables() {
        assertThat(tableExists("card_accounts")).isTrue();
        assertThat(tableExists("cards")).isTrue();
        assertThat(tableExists("org_projection")).isTrue();
        assertThat(tableExists("org_member_projection")).isTrue();
        assertThat(tableExists("reputation_projection")).isTrue();
    }

    @Test
    @DisplayName("Flyway 가 V6 승인 홀드·가맹점 정책 테이블을 만든다")
    void flywayCreatesAuthorizationTables() {
        assertThat(tableExists("authorization_holds")).isTrue();
        assertThat(tableExists("merchant_policies")).isTrue();
    }

    @Test
    @DisplayName("Flyway 가 V7 매입 테이블을 만든다")
    void flywayCreatesCaptureTables() {
        assertThat(tableExists("card_captures")).isTrue();
    }

    @Test
    @DisplayName("Flyway 가 V8 명세서·납부 테이블을 만든다")
    void flywayCreatesStatementTables() {
        assertThat(tableExists("card_statements")).isTrue();
        assertThat(tableExists("statement_payments")).isTrue();
    }

    @Test
    @DisplayName("Flyway 마이그레이션이 V2 ~ V10 → V20260822010000(필드별 신뢰도) 순서로 적용된다")
    void flywayAppliesMigrationsInOrder() {
        List<String> versions = jdbc.queryForList("""
                SELECT version FROM opslab.flyway_schema_history
                 WHERE version IS NOT NULL
                 ORDER BY installed_rank
                """, String.class);
        assertThat(versions).containsExactly("2", "3", "4", "5", "6", "7", "8", "9", "10",
                "20260822010000");
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'opslab' AND table_name = ?
                """, Integer.class, table);
        return n != null && n > 0;
    }
}
