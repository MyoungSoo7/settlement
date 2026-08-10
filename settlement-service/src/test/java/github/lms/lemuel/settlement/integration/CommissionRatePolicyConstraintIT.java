package github.lms.lemuel.settlement.integration;

import github.lms.lemuel.SettlementServiceApplication;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 요율 정책 기간 중첩 차단의 계약 테스트 (ADR 0032).
 *
 * <p>ADR 0032 설계 전체가 "같은 scope 안의 기간 중첩을 <b>DB 가</b> 거부한다"에 걸려 있다. 애플리케이션
 * 검증만으로는 동시 등록·직접 INSERT 를 막지 못하고, 중첩이 한 번 생기면 "왜 이 요율이 적용됐나"를
 * 설명할 수 없게 된다. 그래서 제약이 실제 스키마에 살아 있는지를 실 DB 로 못박는다.
 *
 * <p>{@code [from, to)} 반열림이라 경계 접촉(앞 정책의 종료일 == 뒤 정책의 발효일)은 중첩이 아니다 —
 * 정책을 이어 붙일 때 하루가 겹치거나 비지 않아야 하므로 이 성질도 함께 검증한다.
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.schemas=public",
                "spring.flyway.default-schema=public",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class CommissionRatePolicyConstraintIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> SETTLEMENT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_db").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SETTLEMENT_DB::getJdbcUrl);
        r.add("spring.datasource.username", SETTLEMENT_DB::getUsername);
        r.add("spring.datasource.password", SETTLEMENT_DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    private void insert(String scopeKey, String rate, String from, String to) {
        jdbc.update("""
                INSERT INTO commission_rate_policy
                    (scope, scope_key, rate, effective_from, effective_to, reason, created_by)
                VALUES ('TIER', ?, ?::numeric, ?::date, ?::date, 'it', 'it')
                """, scopeKey, rate, from, to);
    }

    @Test @DisplayName("btree_gist 가 설치돼 있다 — EXCLUDE 제약의 전제")
    void btreeGistIsInstalled() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test @DisplayName("같은 scope 의 기간 중첩은 DB 가 거부한다")
    void overlappingPeriodIsRejectedByDb() {
        insert("VIP_OVERLAP", "0.02500", "2026-01-01", "2026-09-01");

        assertThatThrownBy(() -> insert("VIP_OVERLAP", "0.02000", "2026-08-01", "2026-10-01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @DisplayName("경계 접촉은 중첩이 아니다 — [from, to) 반열림이라 이어 붙일 수 있다")
    void adjacentPeriodsAreAllowed() {
        insert("VIP_ADJACENT", "0.02500", "2026-01-01", "2026-09-01");

        assertThatCode(() -> insert("VIP_ADJACENT", "0.02300", "2026-09-01", null))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("무기한(NULL) 정책도 이후 등록을 막는다 — 열린 구간이 중첩 판정에 참여한다")
    void openEndedPolicyBlocksLaterOverlap() {
        insert("VIP_OPEN", "0.02500", "2026-01-01", null);

        assertThatThrownBy(() -> insert("VIP_OPEN", "0.02000", "2027-01-01", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @DisplayName("다른 scope_key 는 같은 기간이어도 서로 독립이다")
    void differentScopeKeysAreIndependent() {
        insert("VIP_A", "0.02500", "2026-01-01", null);

        assertThatCode(() -> insert("VIP_B", "0.02000", "2026-01-01", null))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("요율은 0~1 범위를 벗어날 수 없다 — 100% 초과 수수료 차단")
    void rateRangeIsConstrained() {
        assertThatThrownBy(() -> insert("VIP_RANGE", "1.50000", "2026-01-01", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @DisplayName("종료일이 발효일보다 앞설 수 없다")
    void reversedPeriodIsRejected() {
        assertThatThrownBy(() -> insert("VIP_REVERSED", "0.02500", "2026-09-01", "2026-01-01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
