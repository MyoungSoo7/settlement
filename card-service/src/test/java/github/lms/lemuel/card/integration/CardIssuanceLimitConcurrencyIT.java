package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>이 설계의 핵심 검증</b> — {@code master_limit >= Σ sub_limit} 가 동시 발급 경합에서도 지켜지는지.
 *
 * <p>카드계정과 카드를 별개 애그리거트로 쪼갠 순간 이 불변식은 한 객체 안에서 지킬 수 없게 됐고,
 * 집계 제약은 DB 로도 표현할 수 없다(V4__card_core.sql 주석). 남은 방어선은
 * {@code IssueCardService} 의 "카드계정 행 비관적 락 → 합계 재계산" 순서 하나뿐이라,
 * 목이 아니라 <b>실 PostgreSQL 과 실 스레드</b>로 증명해야 의미가 있다.
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
class CardIssuanceLimitConcurrencyIT {

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
    @Autowired IssueCardUseCase issueCardUseCase;
    @Autowired SaveCardAccountPort saveCardAccountPort;
    @Autowired SaveOrgProjectionPort saveOrgProjectionPort;
    @Autowired LoadCardPort loadCardPort;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE opslab.cards, opslab.card_accounts,"
                + " opslab.org_member_projection, opslab.outbox_events RESTART IDENTITY CASCADE");
    }

    private CardAccount saveActiveAccount(Long organizationId, String sellerId, BigDecimal masterLimit) {
        CardAccount account = CardAccount.open(organizationId, sellerId);
        account.activate(masterLimit, new LimitSnapshot(masterLimit, BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "seller*0.7"));
        return saveCardAccountPort.save(account);
    }

    private void saveMemberProjection(Long organizationId, Long userId, OrgRole role) {
        saveOrgProjectionPort.upsertMember(organizationId, userId, role.name());
    }

    /**
     * 동시에 발급을 시도하고 성공 건수를 센다. 진 스레드의 예외는 삼키지 않고 종류만 기록해도
     * 되지만, 여기서 검증하려는 것은 <b>최종 합계</b>라 성공 카운트로 충분하다.
     */
    private int raceIssuance(Long cardAccountId, int threads, BigDecimal subLimit) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (long uid = 1; uid <= threads; uid++) {
                final long holder = uid;
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        issueCardUseCase.issue(new IssueCardCommand(cardAccountId, holder, subLimit, 100L));
                        succeeded.incrementAndGet();
                    } catch (Exception ignored) {
                        // 경쟁에서 진 스레드: 락 대기 후 합계 재계산에서 한도 초과.
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
    @DisplayName("동시 발급이 마스터 한도를 넘지 못한다 — 애그리거트를 쪼갠 대가를 락으로 갚는다")
    void concurrentIssuanceNeverExceedsMasterLimit() throws Exception {
        // 마스터 100만. 각 60만씩 4명에게 동시 발급 시도 → 최대 1건만 성공해야 한다.
        CardAccount account = saveActiveAccount(3001L, "777", new BigDecimal("1000000"));
        for (long uid = 1; uid <= 4; uid++) {
            saveMemberProjection(3001L, uid, OrgRole.STAFF);
        }
        saveMemberProjection(3001L, 100L, OrgRole.OWNER);

        int succeeded = raceIssuance(account.getId(), 4, new BigDecimal("600000"));

        assertThat(succeeded).isEqualTo(1);
        assertThat(loadCardPort.sumActiveSubLimits(account.getId()))
                .isLessThanOrEqualTo(new BigDecimal("1000000"))
                .isEqualByComparingTo("600000");
    }

    /**
     * 두 번째 테스트가 없으면 "전부 실패시키는 락"도 첫 테스트를 통과한다.
     * 불변식을 지키는 것과 처리량을 죽이는 것은 다르다.
     */
    @Test
    @DisplayName("한도에 딱 맞는 동시 발급 2건은 둘 다 성공한다 — 락이 과도하게 막지 않는다")
    void concurrentIssuanceWithinLimitBothSucceed() throws Exception {
        CardAccount account = saveActiveAccount(3002L, "778", new BigDecimal("1000000"));
        saveMemberProjection(3002L, 1L, OrgRole.STAFF);
        saveMemberProjection(3002L, 2L, OrgRole.STAFF);
        saveMemberProjection(3002L, 100L, OrgRole.OWNER);

        int succeeded = raceIssuance(account.getId(), 2, new BigDecimal("500000"));

        assertThat(succeeded).isEqualTo(2);
        assertThat(loadCardPort.sumActiveSubLimits(account.getId())).isEqualByComparingTo("1000000");
    }

    /**
     * 발급이 성공한 만큼만 Outbox 에 이벤트가 쌓여야 한다. 실패한 발급의 이벤트가 남으면
     * 소비자는 존재하지 않는 카드에 반응하고, 성공한 발급의 이벤트가 없으면 조용히 유실된다 —
     * 도메인 저장과 Outbox 기록이 같은 트랜잭션이라는 것이 여기서 확인된다.
     */
    @Test
    @DisplayName("경합 후 Outbox 발급 이벤트 수가 실제 성공 건수와 일치한다")
    void outboxMatchesActualIssuances() throws Exception {
        CardAccount account = saveActiveAccount(3003L, "779", new BigDecimal("1000000"));
        for (long uid = 1; uid <= 4; uid++) {
            saveMemberProjection(3003L, uid, OrgRole.STAFF);
        }
        saveMemberProjection(3003L, 100L, OrgRole.OWNER);

        int succeeded = raceIssuance(account.getId(), 4, new BigDecimal("400000"));

        Integer events = jdbc.queryForObject(
                "select count(*) from opslab.outbox_events where event_type = 'CardIssued'",
                Integer.class);
        assertThat(succeeded).isEqualTo(2);
        assertThat(events).isEqualTo(succeeded);
    }
}
