package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.OrgRole;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Task 12 — 조직에서 제거된 임직원의 카드가 자동 정지되는지 실 PostgreSQL 위에서 검증한다.
 *
 * <p>목으로 대체할 수 없는 이유가 둘 있다. 하나는 <b>프로젝션 비활성화와 카드 정지가 같은
 * 트랜잭션</b>이어야 한다는 것 — 멤버는 빠졌는데 카드가 살아 있는 중간 상태가 커밋되면 그 틈에
 * 결제가 승인된다. 다른 하나는 Outbox 기록도 같은 트랜잭션이라는 것 — 상태만 바뀌고 이벤트가
 * 없으면 알림·감사 쪽에서 조용히 유실된다.
 *
 * <p>{@code app.kafka.enabled=false} 로 브로커 없이 부팅하고 유스케이스를 직접 호출한다.
 * 컨슈머 → 유스케이스 경로는 {@link OrgProjectionIntegrationIT} 가 이미 덮고 있어, 여기서는
 * "제거 처리에 카드 정지가 붙었는가"만 본다.
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
class MemberRemovedSuspendsCardIT {

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
    @Autowired IngestOrgProjectionUseCase orgProjectionService;
    @Autowired SaveCardAccountPort saveCardAccountPort;
    @Autowired SaveCardPort saveCardPort;
    @Autowired SaveOrgProjectionPort saveOrgProjectionPort;
    @Autowired LoadCardPort loadCardPort;
    @Autowired LoadOrgProjectionPort loadOrgProjectionPort;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE opslab.cards, opslab.card_accounts,"
                + " opslab.org_member_projection, opslab.outbox_events RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("조직에서 제거된 임직원의 카드가 자동 정지된다")
    void memberRemovalSuspendsCard() {
        CardAccount account = saveActiveAccount(3001L, "777", new BigDecimal("1000000"));
        saveMemberProjection(3001L, 888L, OrgRole.STAFF);
        Card card = saveCardPort.save(Card.issue(account.getId(), 888L, "m", new BigDecimal("100000")));

        orgProjectionService.removeMember(3001L, 888L);

        assertThat(loadCardPort.findById(card.getId()))
                .map(Card::getStatus).contains(CardStatus.SUSPENDED);
        assertThat(loadOrgProjectionPort.findMemberRole(3001L, 888L)).isEmpty();
        assertThat(outboxCountOf("CardStatusChanged")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 안전하다 — 재처리·리플레이 멱등")
    void removalIsIdempotent() {
        CardAccount account = saveActiveAccount(3003L, "779", new BigDecimal("1000000"));
        saveMemberProjection(3003L, 888L, OrgRole.STAFF);
        Card card = saveCardPort.save(Card.issue(account.getId(), 888L, "m", new BigDecimal("100000")));

        orgProjectionService.removeMember(3003L, 888L);
        orgProjectionService.removeMember(3003L, 888L);   // 리플레이

        assertThat(loadCardPort.findById(card.getId()))
                .map(Card::getStatus).contains(CardStatus.SUSPENDED);
        // 상태가 이미 SUSPENDED 면 이벤트를 다시 내지 않는다 — 소비측 노이즈 방지.
        assertThat(outboxCountOf("CardStatusChanged")).isEqualTo(1);
    }

    @Test
    @DisplayName("정지돼도 서브한도 합계에는 계속 잡힌다 — 복직 시 한도 충돌 방지")
    void suspendedCardStillOccupiesLimit() {
        CardAccount account = saveActiveAccount(3004L, "780", new BigDecimal("1000000"));
        saveMemberProjection(3004L, 888L, OrgRole.STAFF);
        saveCardPort.save(Card.issue(account.getId(), 888L, "m", new BigDecimal("400000")));
        BigDecimal before = loadCardPort.sumActiveSubLimits(account.getId());

        orgProjectionService.removeMember(3004L, 888L);

        assertThat(loadCardPort.sumActiveSubLimits(account.getId()))
                .isEqualByComparingTo(before)
                .isEqualByComparingTo("400000");
    }

    @Test
    @DisplayName("카드가 없는 멤버 제거는 무해하다 — 프로젝션만 비활성화된다")
    void removalWithoutCardIsNoop() {
        saveActiveAccount(3005L, "781", new BigDecimal("1000000"));
        saveMemberProjection(3005L, 888L, OrgRole.STAFF);

        assertThatCode(() -> orgProjectionService.removeMember(3005L, 888L))
                .doesNotThrowAnyException();
        assertThat(loadOrgProjectionPort.findMemberRole(3005L, 888L)).isEmpty();
        assertThat(outboxCountOf("CardStatusChanged")).isZero();
    }

    /**
     * 카드계정이 아직 없는 조직에서의 제거도 무해해야 한다 — organization-service 는 카드 도입
     * 여부를 모르고 이벤트를 보낸다. 카드계정 조회 결과를 확인하지 않으면 여기서 NPE 가 난다.
     */
    @Test
    @DisplayName("카드계정이 없는 조직의 멤버 제거도 무해하다")
    void removalWithoutCardAccountIsNoop() {
        saveMemberProjection(3006L, 888L, OrgRole.STAFF);

        assertThatCode(() -> orgProjectionService.removeMember(3006L, 888L))
                .doesNotThrowAnyException();
        assertThat(loadOrgProjectionPort.findMemberRole(3006L, 888L)).isEmpty();
        assertThat(outboxCountOf("CardStatusChanged")).isZero();
    }

    /**
     * 해지된 카드는 건드리지 않는다. CANCELED → SUSPENDED 는 도메인이 금지하는 전이라
     * 무심코 전부 정지시키면 이탈 이벤트 하나가 예외로 죽어 프로젝션 비활성화까지 롤백된다.
     */
    @Test
    @DisplayName("이미 해지된 카드의 보유자 제거는 예외 없이 넘어간다")
    void canceledCardIsLeftAlone() {
        CardAccount account = saveActiveAccount(3007L, "782", new BigDecimal("1000000"));
        saveMemberProjection(3007L, 888L, OrgRole.STAFF);
        Card issued = Card.issue(account.getId(), 888L, "m", new BigDecimal("100000"));
        issued.cancel();
        Card card = saveCardPort.save(issued);

        assertThatCode(() -> orgProjectionService.removeMember(3007L, 888L))
                .doesNotThrowAnyException();

        assertThat(loadCardPort.findById(card.getId()))
                .map(Card::getStatus).contains(CardStatus.CANCELED);
        assertThat(loadOrgProjectionPort.findMemberRole(3007L, 888L)).isEmpty();
        assertThat(outboxCountOf("CardStatusChanged")).isZero();
    }

    // ---- helpers ----

    private CardAccount saveActiveAccount(Long organizationId, String sellerId, BigDecimal masterLimit) {
        CardAccount account = CardAccount.open(organizationId, sellerId);
        account.activate(masterLimit, new LimitSnapshot(masterLimit, BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "seller*0.7"));
        return saveCardAccountPort.save(account);
    }

    private void saveMemberProjection(Long organizationId, Long userId, OrgRole role) {
        saveOrgProjectionPort.upsertMember(organizationId, userId, role.name());
    }

    private int outboxCountOf(String eventType) {
        Integer count = jdbc.queryForObject(
                "select count(*) from opslab.outbox_events where event_type = ?",
                Integer.class, eventType);
        return count == null ? 0 : count;
    }
}
