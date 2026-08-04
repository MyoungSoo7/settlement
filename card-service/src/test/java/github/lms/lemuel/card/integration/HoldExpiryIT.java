package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import github.lms.lemuel.card.application.port.in.ExpireStaleHoldsUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.out.LoadAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.HoldStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미매입 홀드 만료 배치 통합 테스트.
 *
 * <p>ACTIVE 홀드 만료·최근 홀드 미만료·만료 건수 반환을 실 PostgreSQL 로 검증한다.
 * 만료 후 가용한도가 복구됨을 ACTIVE 홀드 합계로 확인한다.
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
class HoldExpiryIT {

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
    @Autowired AuthorizeCardUseCase authorizeCardUseCase;
    @Autowired ExpireStaleHoldsUseCase expireStaleHoldsUseCase;
    @Autowired IssueCardUseCase issueCardUseCase;
    @Autowired SaveCardAccountPort saveCardAccountPort;
    @Autowired SaveOrgProjectionPort saveOrgProjectionPort;
    @Autowired LoadAuthorizationHoldPort loadAuthorizationHoldPort;

    @BeforeEach
    void clean() {
        jdbc.execute(
                "TRUNCATE TABLE opslab.card_captures, opslab.authorization_holds, opslab.cards,"
                        + " opslab.card_accounts, opslab.org_member_projection,"
                        + " opslab.outbox_events RESTART IDENTITY CASCADE");
    }

    private CardAccount createActiveAccount(Long orgId, String sellerId, BigDecimal masterLimit) {
        CardAccount account = CardAccount.open(orgId, sellerId);
        account.activate(masterLimit, new LimitSnapshot(masterLimit, BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "test*0.7"));
        return saveCardAccountPort.save(account);
    }

    private void addMember(Long orgId, Long userId, OrgRole role) {
        saveOrgProjectionPort.upsertMember(orgId, userId, role.name(), orgId * 1000L + userId);
    }

    private Card issueCard(Long cardAccountId, Long holderUserId, BigDecimal subLimit, Long requesterId) {
        return issueCardUseCase.issue(new IssueCardCommand(cardAccountId, holderUserId, subLimit, requesterId));
    }

    @Test
    @DisplayName("expiryDays 이상 된 ACTIVE 홀드는 EXPIRED 로 전환된다 — 가용한도 복구")
    void staleActiveHold_isExpired_andLimitRestored() {
        long orgId = 9001L;
        long ownerId = 500L;
        long holderUserId = 501L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "expiry-seller-001", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        String authId = "AUTH-STALE-" + UUID.randomUUID();
        var result = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(authId, card.getId(), new BigDecimal("50000"),
                        "테스트가맹점", "5812", false, false));
        assertThat(result.approved()).isTrue();

        // authorized_at 을 8일 전으로 조작 (DB 직접 업데이트)
        jdbc.update(
                "UPDATE opslab.authorization_holds SET authorized_at = NOW() - INTERVAL '8 days'"
                        + " WHERE authorization_id = ?",
                authId);

        // 7일 만료 배치 실행
        int expired = expireStaleHoldsUseCase.expireStaleHolds(7);

        assertThat(expired).isEqualTo(1);

        // 홀드 상태 검증
        var hold = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(hold).isPresent();
        assertThat(hold.get().getStatus()).isEqualTo(HoldStatus.EXPIRED);

        // 한도 복구
        assertThat(loadAuthorizationHoldPort.sumActiveHoldsByCard(card.getId()))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("expiryDays 미만 된 ACTIVE 홀드는 만료되지 않는다")
    void recentActiveHold_isNotExpired() {
        long orgId = 9002L;
        long ownerId = 502L;
        long holderUserId = 503L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "expiry-seller-002", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        String authId = "AUTH-FRESH-" + UUID.randomUUID();
        var result = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(authId, card.getId(), new BigDecimal("30000"),
                        "테스트가맹점", "5812", false, false));
        assertThat(result.approved()).isTrue();

        // 최근 홀드(만료 없이 즉시 배치 실행)
        int expired = expireStaleHoldsUseCase.expireStaleHolds(7);

        assertThat(expired).isEqualTo(0);

        // 홀드 여전히 ACTIVE
        var hold = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(hold).isPresent();
        assertThat(hold.get().getStatus()).isEqualTo(HoldStatus.ACTIVE);

        // 한도 여전히 차감 중
        assertThat(loadAuthorizationHoldPort.sumActiveHoldsByCard(card.getId()))
                .isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("복수 stale 홀드 만료 — 만료 건수가 정확히 반환된다")
    void multipleStaleHolds_allExpired_countIsCorrect() {
        long orgId = 9003L;
        long ownerId = 504L;
        long holderUserId = 505L;
        BigDecimal limit = new BigDecimal("500000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "expiry-seller-003", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        // 3건의 승인 홀드 생성
        for (int i = 0; i < 3; i++) {
            String authId = "AUTH-MULTI-" + i + "-" + UUID.randomUUID();
            var res = authorizeCardUseCase.authorize(
                    new AuthorizeCardCommand(authId, card.getId(), new BigDecimal("50000"),
                            "테스트가맹점", "5812", false, false));
            assertThat(res.approved()).isTrue();
        }

        // 모두 8일 전으로 조작
        jdbc.update(
                "UPDATE opslab.authorization_holds SET authorized_at = NOW() - INTERVAL '8 days'"
                        + " WHERE card_id = ? AND status = 'ACTIVE'",
                card.getId());

        int expired = expireStaleHoldsUseCase.expireStaleHolds(7);

        assertThat(expired).isEqualTo(3);

        // 모든 홀드 EXPIRED
        Integer activeCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.authorization_holds WHERE card_id = ? AND status = 'ACTIVE'",
                Integer.class, card.getId());
        assertThat(activeCount).isEqualTo(0);

        Integer expiredCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.authorization_holds WHERE card_id = ? AND status = 'EXPIRED'",
                Integer.class, card.getId());
        assertThat(expiredCount).isEqualTo(3);
    }
}
