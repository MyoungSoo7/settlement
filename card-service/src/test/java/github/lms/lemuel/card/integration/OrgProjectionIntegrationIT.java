package github.lms.lemuel.card.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.adapter.in.kafka.CompanyReputationChangedConsumer;
import github.lms.lemuel.card.adapter.in.kafka.OrganizationCreatedConsumer;
import github.lms.lemuel.card.adapter.in.kafka.OrganizationMemberRemovedConsumer;
import github.lms.lemuel.card.adapter.in.kafka.OrganizationMemberRoleChangedConsumer;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.in.IngestReputationUseCase;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.application.port.out.LoadReputationPort;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task 7 프로젝션 종단 검증 — 실 PostgreSQL(Testcontainers) + 실 Flyway(V4) 위에서
 * "정본 이벤트 → 컨슈머 → 서비스 → 어댑터 → 실 DB → LoadOrgProjectionPort/LoadReputationPort 조회"
 * 전 경로를 검증한다.
 *
 * <p>{@code app.kafka.enabled=false} 로 브로커 없이 부팅하고(loan-service
 * {@code LoanSettlementSagaIntegrationTest} 와 동일 관례), 컨슈머는 실 빈 조합으로 직접 생성해
 * 레코드를 주입한다 — Kafka 인프라 없이도 멱등·팬아웃·비활성화 로직이 실 DB 제약(PK·CHECK)까지
 * 통과하는지 확인할 수 있다.
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
class OrgProjectionIntegrationIT {

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

    @Autowired IngestOrgProjectionUseCase ingestOrgProjectionUseCase;
    @Autowired IngestReputationUseCase ingestReputationUseCase;
    @Autowired LoadOrgProjectionPort loadOrgProjectionPort;
    @Autowired LoadReputationPort loadReputationPort;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired ObjectMapper objectMapper;

    private OrganizationCreatedConsumer createdConsumer;
    private OrganizationMemberRoleChangedConsumer roleChangedConsumer;
    private OrganizationMemberRemovedConsumer removedConsumer;
    private CompanyReputationChangedConsumer reputationConsumer;

    @BeforeEach
    void setUp() {
        createdConsumer = new OrganizationCreatedConsumer(ingestOrgProjectionUseCase, processedEventRepository, objectMapper);
        roleChangedConsumer = new OrganizationMemberRoleChangedConsumer(ingestOrgProjectionUseCase, processedEventRepository, objectMapper);
        removedConsumer = new OrganizationMemberRemovedConsumer(ingestOrgProjectionUseCase, processedEventRepository, objectMapper);
        reputationConsumer = new CompanyReputationChangedConsumer(ingestReputationUseCase, processedEventRepository, objectMapper);
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("조직 생성(SELLER) → 오너가 활성 OWNER 멤버로 조회된다")
    void organizationCreated_ownerBecomesActiveMember() {
        long orgId = 5001L;
        String payload = "{\"organizationId\":" + orgId + ",\"name\":\"테스트 셀러\","
                + "\"type\":\"SELLER\",\"externalRef\":\"SELLER-999\",\"ownerUserId\":999}";
        send(createdConsumer::onOrganizationCreated, "lemuel.organization.created", payload);

        assertThat(loadOrgProjectionPort.findOrg(orgId)).isPresent();
        assertThat(loadOrgProjectionPort.findOrg(orgId).get().type()).isEqualTo("SELLER");
        assertThat(loadOrgProjectionPort.findOrg(orgId).get().externalRef()).isEqualTo("SELLER-999");
        assertThat(loadOrgProjectionPort.findMemberRole(orgId, 999L)).contains(OrgRole.OWNER);
    }

    @Test
    @DisplayName("조직 생성(CORPORATE) → 프로젝션에 적재되지 않는다")
    void organizationCreated_corporateType_notPersisted() {
        long orgId = 5002L;
        String payload = "{\"organizationId\":" + orgId + ",\"name\":\"상장사\","
                + "\"type\":\"CORPORATE\",\"externalRef\":\"005930\",\"ownerUserId\":1000}";
        send(createdConsumer::onOrganizationCreated, "lemuel.organization.created", payload);

        assertThat(loadOrgProjectionPort.findOrg(orgId)).isEmpty();
    }

    @Test
    @DisplayName("역할 변경 후 제거 → findMemberRole 이 빈 Optional(비활성 멤버는 조회되지 않는다)")
    void memberRoleChangedThenRemoved_activeOnlyLookup() {
        long orgId = 5003L;
        long userId = 888L;
        send(createdConsumer::onOrganizationCreated, "lemuel.organization.created",
                "{\"organizationId\":" + orgId + ",\"name\":\"테스트\",\"type\":\"SELLER\","
                        + "\"externalRef\":null,\"ownerUserId\":1}");

        // 역할 변경: STAFF → MANAGER (newRole 만 반영)
        send(roleChangedConsumer::onMemberRoleChanged, "lemuel.organization.member_role_changed",
                "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":9001,"
                        + "\"previousRole\":\"STAFF\",\"newRole\":\"MANAGER\"}");
        assertThat(loadOrgProjectionPort.findMemberRole(orgId, userId)).contains(OrgRole.MANAGER);

        // 제거: active=false 로 전환 → findMemberRole 은 빈 Optional
        send(removedConsumer::onMemberRemoved, "lemuel.organization.member_removed",
                "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":9001}");
        assertThat(loadOrgProjectionPort.findMemberRole(orgId, userId)).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 멤버의 member_removed → 예외 없이 무해한 no-op")
    void removeUnknownMember_isNoop() {
        send(removedConsumer::onMemberRemoved, "lemuel.organization.member_removed",
                "{\"organizationId\":9999,\"userId\":9999,\"membershipId\":1}");

        assertThat(loadOrgProjectionPort.findMemberRole(9999L, 9999L)).isEmpty();
    }

    @Test
    @DisplayName("평판 이벤트 → sellerIds 각각 개별 조회 가능, 미수신 셀러는 unknownDefault(D)")
    void reputationChanged_fansOutPerSeller_unknownDefaultsToD() {
        String payload = "{\"stockCode\":\"005930\",\"snapshotDate\":\"2026-07-10\",\"score\":55,"
                + "\"grade\":\"C\",\"previousGrade\":\"B\",\"articleCount\":24,\"negativeCount\":9,"
                + "\"sellerIds\":[70001,70002],\"calculatedAt\":\"2026-07-10T09:30:00Z\"}";
        send(reputationConsumer::onReputationChanged, "lemuel.company.reputation_changed", payload);

        assertThat(loadReputationPort.gradeOf("70001")).isEqualTo(ReputationGrade.C);
        assertThat(loadReputationPort.gradeOf("70002")).isEqualTo(ReputationGrade.C);
        assertThat(loadReputationPort.gradeOf("no-such-seller")).isEqualTo(ReputationGrade.unknownDefault());
    }

    @Test
    @DisplayName("동일 event_id 의 member_removed 중복 수신 → 비활성화 1회(멱등), 예외 없음")
    void duplicateRemoved_idempotent() {
        long orgId = 5004L;
        long userId = 777L;
        send(createdConsumer::onOrganizationCreated, "lemuel.organization.created",
                "{\"organizationId\":" + orgId + ",\"name\":\"테스트\",\"type\":\"SELLER\","
                        + "\"externalRef\":null,\"ownerUserId\":" + userId + "}");
        assertThat(loadOrgProjectionPort.findMemberRole(orgId, userId)).contains(OrgRole.OWNER);

        UUID eventId = UUID.randomUUID();
        String payload = "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":1}";
        removedConsumer.onMemberRemoved(record("lemuel.organization.member_removed", eventId, payload), mock(Acknowledgment.class));
        removedConsumer.onMemberRemoved(record("lemuel.organization.member_removed", eventId, payload), mock(Acknowledgment.class));

        assertThat(loadOrgProjectionPort.findMemberRole(orgId, userId)).isEmpty();
    }

    @Test
    @DisplayName("★토픽 간 순서 역전 — 제거 뒤에 도착한 같은 세대 role_changed 는 멤버를 부활시키지 못한다")
    void staleRoleChangeAfterRemoval_doesNotResurrect() {
        long orgId = 5005L;
        long userId = 666L;

        // 합류(세대 9001) → 제거(세대 9001)
        send(roleChangedConsumer::onMemberRoleChanged, "lemuel.organization.member_role_changed",
                "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":9001,"
                        + "\"previousRole\":\"STAFF\",\"newRole\":\"STAFF\"}");
        send(removedConsumer::onMemberRemoved, "lemuel.organization.member_removed",
                "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":9001}");
        assertThat(loadOrgProjectionPort.findMemberRole(orgId, userId)).isEmpty();

        // 다른 토픽에서 늦게 도착한 같은 세대 role_changed — 부활 금지
        send(roleChangedConsumer::onMemberRoleChanged, "lemuel.organization.member_role_changed",
                "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":9001,"
                        + "\"previousRole\":\"STAFF\",\"newRole\":\"MANAGER\"}");
        assertThat(loadOrgProjectionPort.findMemberRole(orgId, userId)).isEmpty();

        // 진짜 재합류(새 세대 9002)만 다시 활성화한다
        send(roleChangedConsumer::onMemberRoleChanged, "lemuel.organization.member_role_changed",
                "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":9002,"
                        + "\"previousRole\":\"STAFF\",\"newRole\":\"STAFF\"}");
        assertThat(loadOrgProjectionPort.findMemberRole(orgId, userId)).contains(OrgRole.STAFF);

        // 재합류 뒤 늦게 도착한 과거 세대 제거 — 무시
        send(removedConsumer::onMemberRemoved, "lemuel.organization.member_removed",
                "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":9001}");
        assertThat(loadOrgProjectionPort.findMemberRole(orgId, userId)).contains(OrgRole.STAFF);
    }

    @Test
    @DisplayName("★제거가 합류보다 먼저 도착 — 톰스톤이 남아 늦은 같은 세대 합류를 막는다 (실 DB 제약 통과)")
    void removalArrivingBeforeJoin_leavesTombstoneBlockingLateJoin() {
        long orgId = 5006L;
        long userId = 555L;

        send(removedConsumer::onMemberRemoved, "lemuel.organization.member_removed",
                "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":9001}");

        send(roleChangedConsumer::onMemberRoleChanged, "lemuel.organization.member_role_changed",
                "{\"organizationId\":" + orgId + ",\"userId\":" + userId + ",\"membershipId\":9001,"
                        + "\"previousRole\":\"STAFF\",\"newRole\":\"MANAGER\"}");

        assertThat(loadOrgProjectionPort.findMemberRole(orgId, userId)).isEmpty();
    }

    // ---- helpers ----

    private interface ConsumeFn {
        void accept(ConsumerRecord<String, String> record, Acknowledgment ack);
    }

    private void send(ConsumeFn fn, String topic, String payload) {
        fn.accept(record(topic, UUID.randomUUID(), payload), mock(Acknowledgment.class));
    }

    private ConsumerRecord<String, String> record(String topic, UUID eventId, String payload) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>(topic, 0, 0L, null, payload);
        r.headers().add(new RecordHeader("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return r;
    }
}
