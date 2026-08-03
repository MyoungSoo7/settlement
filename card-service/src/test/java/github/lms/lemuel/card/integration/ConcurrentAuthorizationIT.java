package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.out.LoadAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>동시 승인 경합 불변식 통합 테스트</b>
 *
 * <p>가용한도 = masterLimit − Σ(ACTIVE 홀드) 불변식이 동시 승인 경합에서도 깨지지 않음을 증명한다.
 * 카드계정(master)와 카드(sub)가 별개 애그리거트이므로 이 불변식은 DB 제약으로 표현할 수 없다.
 * {@code AuthorizeCardService} 는 1단계 발급과 동일한 "계정 비관적 락 → 합계 재계산" 순서로
 * 이 불변식을 지킨다. 이 테스트는 그 락이 실 PostgreSQL 동시 트랜잭션을 방어함을 직접 증명한다.
 *
 * <p>락이 없거나 락 순서가 뒤집히면 두 스레드가 모두 "한도 안에 있다"는 스냅샷을 읽고 둘 다
 * 승인해 ACTIVE 홀드 합계가 한도를 초과한다. 이 테스트는 그 시나리오에서 반드시 실패해야 한다 —
 * 그래야만 의미 있는 방어선이다.
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
class ConcurrentAuthorizationIT {

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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthorizeCardUseCase authorizeCardUseCase;

    @Autowired
    IssueCardUseCase issueCardUseCase;

    @Autowired
    SaveCardAccountPort saveCardAccountPort;

    @Autowired
    SaveOrgProjectionPort saveOrgProjectionPort;

    @Autowired
    LoadAuthorizationHoldPort loadAuthorizationHoldPort;

    @BeforeEach
    void clean() {
        jdbc.execute(
                "TRUNCATE TABLE opslab.authorization_holds, opslab.cards, opslab.card_accounts,"
                        + " opslab.org_member_projection, opslab.outbox_events RESTART IDENTITY CASCADE");
    }

    /**
     * 활성 카드계정을 생성하고 영속 ID 가 붙은 인스턴스를 반환한다.
     */
    private CardAccount createActiveAccount(Long orgId, String sellerId, BigDecimal masterLimit) {
        CardAccount account = CardAccount.open(orgId, sellerId);
        account.activate(masterLimit, new LimitSnapshot(masterLimit, BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "test*0.7"));
        return saveCardAccountPort.save(account);
    }

    /**
     * 조직에 멤버 프로젝션을 등록한다.
     */
    private void addMember(Long orgId, Long userId, OrgRole role) {
        saveOrgProjectionPort.upsertMember(orgId, userId, role.name(), orgId * 1000L + userId);
    }

    /**
     * 카드를 발급한다. holderUserId 가 이미 org 멤버로 등록되어 있어야 한다.
     */
    private Card issueCard(Long cardAccountId, Long holderUserId, BigDecimal subLimit, Long requesterId) {
        return issueCardUseCase.issue(new IssueCardCommand(cardAccountId, holderUserId, subLimit, requesterId));
    }

    /**
     * N개 스레드가 동시에 승인을 시도해 성공 건수를 반환한다.
     */
    private int raceConcurrentAuthorizations(Long cardId, int threads, BigDecimal amountPerTx)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final String authId = "RACE-" + UUID.randomUUID();
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        AuthorizeCardUseCase.AuthorizationResult result = authorizeCardUseCase.authorize(
                                new AuthorizeCardCommand(
                                        authId, cardId, amountPerTx,
                                        "테스트가맹점", "5812", false, false));
                        if (result.approved()) {
                            succeeded.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 락 대기 중 예외 — 실패로 간주
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        return succeeded.get();
    }

    @Test
    @DisplayName("동시 승인이 카드 서브한도를 넘지 못한다 — 비관적 락 불변식")
    void concurrentAuthorizationNeverExceedsSubLimit() throws Exception {
        // 마스터 100만, 서브 100만
        long orgId = 5001L;
        long holderUserId = 1L;
        long ownerId = 100L;
        BigDecimal masterLimit = new BigDecimal("1000000");
        BigDecimal subLimit = new BigDecimal("1000000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "conc-test-seller-001", masterLimit);
        Card card = issueCard(account.getId(), holderUserId, subLimit, ownerId);

        // 4명이 동시에 60만씩 승인 시도 → 합계 240만이지만 한도 100만이므로 1건만 성공해야 한다
        int succeeded = raceConcurrentAuthorizations(card.getId(), 4, new BigDecimal("600000"));

        assertThat(succeeded).isEqualTo(1);

        BigDecimal totalActiveHolds = loadAuthorizationHoldPort.sumActiveHoldsByCard(card.getId());
        assertThat(totalActiveHolds)
                .as("ACTIVE 홀드 합계가 서브한도를 넘지 않아야 한다")
                .isLessThanOrEqualTo(subLimit)
                .isEqualByComparingTo("600000");
    }

    @Test
    @DisplayName("한도에 딱 맞는 동시 승인 2건은 둘 다 성공 — 락이 과도하게 막지 않는다")
    void twoAuthorizationsExactlyAtLimitBothSucceed() throws Exception {
        long orgId = 5002L;
        long holder1 = 2L, holder2 = 3L;
        long ownerId = 101L;
        BigDecimal masterLimit = new BigDecimal("1000000");
        BigDecimal subLimit = new BigDecimal("1000000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holder1, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "conc-test-seller-002", masterLimit);
        Card card = issueCard(account.getId(), holder1, subLimit, ownerId);

        // 각 50만씩 2건 → 합계 100만 = 한도. 둘 다 성공해야 한다.
        int succeeded = raceConcurrentAuthorizations(card.getId(), 2, new BigDecimal("500000"));

        assertThat(succeeded).isEqualTo(2);
        assertThat(loadAuthorizationHoldPort.sumActiveHoldsByCard(card.getId()))
                .isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("승인 성공 건수와 Outbox 이벤트 수가 일치한다 — 원자성 보장")
    void outboxEventCountMatchesSucceededAuthorizations() throws Exception {
        long orgId = 5003L;
        long holderUserId = 4L;
        long ownerId = 102L;
        BigDecimal masterLimit = new BigDecimal("1000000");
        BigDecimal subLimit = new BigDecimal("1000000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "conc-test-seller-003", masterLimit);
        Card card = issueCard(account.getId(), holderUserId, subLimit, ownerId);

        // 4건 경합, 한도 기반 최대 성공 수: floor(100만 / 40만) = 2
        int succeeded = raceConcurrentAuthorizations(card.getId(), 4, new BigDecimal("400000"));

        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'CardAuthorized'",
                Integer.class);

        assertThat(succeeded).isEqualTo(2);
        assertThat(events)
                .as("Outbox 이벤트 수가 실제 승인 건수와 일치해야 한다")
                .isEqualTo(succeeded);
    }

    @Test
    @DisplayName("동일 authorizationId 재전송은 멱등 처리 — 중복 홀드 미생성")
    void idempotentResubmission_returnsSameHold_noduplicateHold() throws Exception {
        long orgId = 5004L;
        long holderUserId = 5L;
        long ownerId = 103L;

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "conc-test-seller-004", new BigDecimal("1000000"));
        Card card = issueCard(account.getId(), holderUserId, new BigDecimal("1000000"), ownerId);

        String authId = "IDEMPOTENT-" + UUID.randomUUID();
        AuthorizeCardCommand cmd = new AuthorizeCardCommand(
                authId, card.getId(), new BigDecimal("50000"), "테스트가맹점", "5812", false, false);

        // 동일 커맨드를 두 번 호출
        AuthorizeCardUseCase.AuthorizationResult r1 = authorizeCardUseCase.authorize(cmd);
        AuthorizeCardUseCase.AuthorizationResult r2 = authorizeCardUseCase.authorize(cmd);

        assertThat(r1.approved()).isTrue();
        assertThat(r2.approved()).isTrue();
        assertThat(r1.hold().getAuthorizationId()).isEqualTo(r2.hold().getAuthorizationId());

        // 홀드는 1건만 생성됨
        Integer holdCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.authorization_holds WHERE authorization_id = ?",
                Integer.class, authId);
        assertThat(holdCount).isEqualTo(1);

        // 한도 차감도 1건분만
        assertThat(loadAuthorizationHoldPort.sumActiveHoldsByCard(card.getId()))
                .isEqualByComparingTo("50000");
    }
}
