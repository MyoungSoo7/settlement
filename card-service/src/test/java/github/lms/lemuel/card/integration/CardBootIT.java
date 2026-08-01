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

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'opslab' AND table_name = ?
                """, Integer.class, table);
        return n != null && n > 0;
    }
}
