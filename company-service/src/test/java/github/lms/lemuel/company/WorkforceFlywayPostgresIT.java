package github.lms.lemuel.company;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class WorkforceFlywayPostgresIT {

    private static final String PROVENANCE_VERSION = "20260806110000";
    private static final String REPLACEMENT_VERSION = "20260806120000";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("company_workforce_test")
            .withUsername("test")
            .withPassword("test");

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable exception) {
            return false;
        }
    }

    @Test
    void fullMigrationReplacesTheOldSeedWithTheStrictSeoulItCohort() {
        String schema = uniqueSchema("normal");
        migrate(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM company_workforce
                WHERE snapshot_month = '2026-06'
                  AND sido = '서울특별시'
                  AND industry_code IN ('642004', '721000', '722000', '722001', '722002', '722003',
                                        '722004', '722005', '723001', '724000', '729000', '940926')
                """, Integer.class)).isEqualTo(11_313);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM company_workforce WHERE snapshot_month = '2026-06'",
                Integer.class)).isEqualTo(11_313);
        assertThat(jdbc.queryForMap("""
                SELECT status, source_row_count, accepted_row_count, rejected_row_count,
                       source_release_date::text AS source_release_date, source_sha256,
                       raw_source_row_count, coverage_scope, region_scope, industry_scope
                FROM workforce_aggregate_build WHERE snapshot_month = '2026-06'
                """))
                .containsEntry("status", "COMPLETE")
                .containsEntry("source_row_count", 11_318L)
                .containsEntry("accepted_row_count", 11_313L)
                .containsEntry("rejected_row_count", 5L)
                .containsEntry("source_release_date", "2026-07-23")
                .containsEntry("source_sha256", "2AAC48EF155D268D544EB8A5BA04CCA201A1E1806A847C5772307775B4657F2B")
                .containsEntry("raw_source_row_count", 593_127L)
                .containsEntry("coverage_scope", "SEOUL_IT_FULL")
                .containsEntry("region_scope", "SEOUL")
                .containsEntry("industry_scope", "SOFTWARE_IT_SERVICE");

        BigDecimal storedMedian = jdbc.queryForObject("""
                SELECT median FROM workforce_aggregate
                WHERE snapshot_month = '2026-06' AND axis = 'REGION' AND level = 'BROADENED'
                  AND group_key = '서울특별시' AND metric = 'ESTIMATED_ANNUAL_SALARY'
                """, BigDecimal.class);
        BigDecimal independentlyCalculatedMedian = jdbc.queryForObject("""
                WITH salaries AS (
                    SELECT ROUND(monthly_billed_amount * 12 / (headcount * 0.095::numeric), 0) AS value,
                           ROW_NUMBER() OVER (ORDER BY ROUND(monthly_billed_amount * 12 / (headcount * 0.095::numeric), 0)) AS rn,
                           COUNT(*) OVER () AS n
                    FROM company_workforce
                    WHERE snapshot_month = '2026-06' AND sido = '서울특별시'
                      AND headcount > 0 AND monthly_billed_amount > 0
                )
                SELECT ROUND(AVG(value), 2) FROM salaries WHERE rn IN ((n + 1) / 2, (n + 2) / 2)
                """, BigDecimal.class);
        assertThat(storedMedian).isEqualByComparingTo(independentlyCalculatedMedian);
        assertThat(storedMedian).isEqualByComparingTo("43265152.00");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM workforce_percentile WHERE snapshot_month = '2026-06'
                """, Integer.class)).isEqualTo(90_504);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT workforce.workplace_name, workforce.biz_reg_no_prefix
                    FROM company_workforce workforce
                    LEFT JOIN workforce_percentile percentile
                      ON percentile.snapshot_month = workforce.snapshot_month
                     AND percentile.workplace_name = workforce.workplace_name
                     AND percentile.biz_reg_no_prefix = workforce.biz_reg_no_prefix
                    WHERE workforce.snapshot_month = '2026-06'
                    GROUP BY workforce.workplace_name, workforce.biz_reg_no_prefix, workforce.sigungu
                    HAVING COUNT(percentile.metric) <> 6 + CASE WHEN workforce.sigungu IS NULL THEN 0 ELSE 2 END
                ) incomplete
                """, Integer.class)).isZero();

        BigDecimal storedRepresentativePercentile = jdbc.queryForObject("""
                SELECT percentile FROM workforce_percentile
                WHERE snapshot_month = '2026-06'
                  AND workplace_name = '(사)전국지방의료원연합회'
                  AND biz_reg_no_prefix = '116820'
                  AND axis = 'REGION' AND level = 'BROADENED' AND metric = 'HEADCOUNT'
                """, BigDecimal.class);
        BigDecimal independentlyCalculatedPercentile = jdbc.queryForObject("""
                WITH target AS (
                    SELECT headcount
                    FROM company_workforce
                    WHERE snapshot_month = '2026-06'
                      AND workplace_name = '(사)전국지방의료원연합회'
                      AND biz_reg_no_prefix = '116820'
                )
                SELECT ROUND(
                    COUNT(*) FILTER (WHERE workforce.headcount <= target.headcount)::numeric
                    * 100 / COUNT(*), 2)
                FROM company_workforce workforce
                CROSS JOIN target
                WHERE workforce.snapshot_month = '2026-06' AND workforce.sido = '서울특별시'
                GROUP BY target.headcount
                """, BigDecimal.class);
        assertThat(storedRepresentativePercentile)
                .isEqualByComparingTo(independentlyCalculatedPercentile)
                .isEqualByComparingTo("85.29");
    }

    @Test
    void sameCountMutationFailsBeforeAnyReplacementDelete() {
        String schema = uniqueSchema("mutated");
        migrate(schema, MigrationVersion.fromVersion(PROVENANCE_VERSION)).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM company_workforce WHERE snapshot_month = '2026-06'",
                Integer.class)).isEqualTo(4_247);

        Long mutatedId = jdbc.queryForObject(
                "SELECT MIN(id) FROM company_workforce WHERE snapshot_month = '2026-06'", Long.class);
        jdbc.update("UPDATE company_workforce SET headcount = headcount + 1 WHERE id = ?", mutatedId);
        Integer mutatedHeadcount = jdbc.queryForObject(
                "SELECT headcount FROM company_workforce WHERE id = ?", Integer.class, mutatedId);

        assertThatThrownBy(() -> migrate(schema, null).migrate())
                .rootCause()
                .hasMessageContaining("Refusing to replace unknown workforce dataset for 2026-06");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM company_workforce WHERE snapshot_month = '2026-06'",
                Integer.class)).isEqualTo(4_247);
        assertThat(jdbc.queryForObject(
                "SELECT headcount FROM company_workforce WHERE id = ?", Integer.class, mutatedId))
                .isEqualTo(mutatedHeadcount);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success",
                Integer.class, REPLACEMENT_VERSION)).isZero();
    }

    private static Flyway migrate(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static JdbcTemplate jdbc(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private static String uniqueSchema(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
