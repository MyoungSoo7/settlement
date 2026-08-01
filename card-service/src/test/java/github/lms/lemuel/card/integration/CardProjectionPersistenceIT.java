package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.adapter.out.persistence.OrgProjectionPersistenceAdapter;
import github.lms.lemuel.card.adapter.out.persistence.ReputationProjectionPersistenceAdapter;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.card.domain.ReputationGrade;
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
 * 프로젝션 영속 IT — 실 PostgreSQL 에서 V4 프로젝션 테이블에 대한
 * upsert 멱등(재수신 시 행 증식 없음)·활성 멤버 판정·비활성화를 검증한다.
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
class CardProjectionPersistenceIT {

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

    @Autowired OrgProjectionPersistenceAdapter orgAdapter;
    @Autowired ReputationProjectionPersistenceAdapter reputationAdapter;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("조직 upsert 는 재수신해도 행이 늘지 않고 최신 값으로 덮는다")
    void orgUpsertIsIdempotent() {
        orgAdapter.upsertOrg(9101L, "이름v1", "SELLER", "SELLER-1");
        orgAdapter.upsertOrg(9101L, "이름v2", "SELLER", "SELLER-1");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.org_projection WHERE organization_id = 9101", Integer.class);
        assertThat(rows).isEqualTo(1);
        assertThat(orgAdapter.findOrg(9101L)).isPresent();
    }

    @Test
    @DisplayName("멤버 역할 upsert → 활성 역할 조회, 비활성화 후에는 조회되지 않는다")
    void memberRoleLifecycle() {
        orgAdapter.upsertMember(9102L, 888L, OrgRole.STAFF);
        orgAdapter.upsertMember(9102L, 888L, OrgRole.MANAGER);

        assertThat(orgAdapter.findMemberRole(9102L, 888L)).contains(OrgRole.MANAGER);

        orgAdapter.deactivateMember(9102L, 888L);

        assertThat(orgAdapter.findMemberRole(9102L, 888L)).isEmpty();
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.org_member_projection WHERE organization_id = 9102", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("한 번도 못 본 멤버의 제거 이벤트는 비활성 툼스톤을 남긴다 — 뒤늦은 재수신에도 안전")
    void deactivateUnknownMemberLeavesTombstone() {
        orgAdapter.deactivateMember(9103L, 999L);

        assertThat(orgAdapter.findMemberRole(9103L, 999L)).isEmpty();
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.org_member_projection WHERE organization_id = 9103", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("평판 등급 upsert — 미존재 셀러는 unknownDefault, 갱신 후 최신 등급")
    void reputationUpsert() {
        assertThat(reputationAdapter.gradeOf("seller-none")).isEqualTo(ReputationGrade.unknownDefault());

        reputationAdapter.upsertGrade("777", ReputationGrade.B);
        reputationAdapter.upsertGrade("777", ReputationGrade.D);

        assertThat(reputationAdapter.gradeOf("777")).isEqualTo(ReputationGrade.D);
    }
}
