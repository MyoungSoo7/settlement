package github.lms.lemuel.insurance.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class InsuranceV4PlaintextMigrationIT {

    private static PostgreSQLContainer<?> postgres;

    static boolean isDockerAvailable() {
        return InsuranceIntegrationTestSupport.isDockerAvailable();
    }

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("insurance_v4_test")
                .withUsername("test")
                .withPassword("test");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void v4FailsClosedAndDoesNotRewriteExistingPlaintextPii() throws Exception {
        flyway("3").migrate();
        UUID applicationId = UUID.randomUUID();
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     INSERT INTO opslab.insured_person_pii (application_id, encrypted_rrn)
                     VALUES (?, '900101-1234567')
                     """)) {
            statement.setObject(1, applicationId);
            statement.executeUpdate();
        }

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("encrypted_rrn 평문/비지원 암호문 존재");

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT encrypted_rrn
                     FROM opslab.insured_person_pii
                     WHERE application_id = '%s'
                     """.formatted(applicationId))) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("900101-1234567");
        }
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("opslab")
                .defaultSchema("opslab");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
