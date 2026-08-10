package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase;
import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase.CaptureHoldCommand;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.in.RefundHoldUseCase;
import github.lms.lemuel.card.application.port.in.RefundHoldUseCase.RefundHoldCommand;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 카드 환불(Refund) 통합 테스트.
 *
 * <p>전액 매입 후 환불, 부분 매입 후 환불, ACTIVE 홀드 환불 시도(비정상)를 검증한다.
 * 환불 후 홀드가 REFUNDED 상태로 전환되어 가용한도에서 제외됨을 확인한다.
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
class RefundIT {

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
    @Autowired CaptureHoldUseCase captureHoldUseCase;
    @Autowired RefundHoldUseCase refundHoldUseCase;
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

    private String authorizeAndGetId(Long cardId, BigDecimal amount) {
        String authId = "AUTH-REFUND-" + UUID.randomUUID();
        var result = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(authId, cardId, amount, "테스트가맹점", "5812", false, false));
        assertThat(result.approved()).isTrue();
        return authId;
    }

    @Test
    @DisplayName("전액 매입 후 환불 — 홀드 상태가 REFUNDED 로 전환된다")
    void refundAfterFullCapture_changesStatusToRefunded() {
        long orgId = 8001L;
        long ownerId = 400L;
        long holderUserId = 401L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "refund-seller-001", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        BigDecimal amount = new BigDecimal("45000");
        String authId = authorizeAndGetId(card.getId(), amount);

        // 전액 매입 → CAPTURED
        captureHoldUseCase.capture(
                new CaptureHoldCommand("CAP-R1-" + UUID.randomUUID(), authId,
                        amount, "테스트가맹점", Instant.now()));

        var holdBeforeRefund = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(holdBeforeRefund.get().getStatus()).isEqualTo(HoldStatus.CAPTURED);

        // 환불
        refundHoldUseCase.refund(new RefundHoldCommand(authId, "고객요청환불"));

        // 홀드 상태 검증
        var hold = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(hold).isPresent();
        assertThat(hold.get().getStatus()).isEqualTo(HoldStatus.REFUNDED);

        // ACTIVE 홀드 합계 = 0 (환불로 한도 복구)
        assertThat(loadAuthorizationHoldPort.sumActiveHoldsByCard(card.getId()))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("부분 매입 후 환불 — 홀드 상태가 REFUNDED 로 전환된다")
    void refundAfterPartialCapture_changesStatusToRefunded() {
        long orgId = 8002L;
        long ownerId = 402L;
        long holderUserId = 403L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "refund-seller-002", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        String authId = authorizeAndGetId(card.getId(), limit);

        // 부분 매입 → PARTIALLY_CAPTURED
        captureHoldUseCase.capture(
                new CaptureHoldCommand("CAP-PR-" + UUID.randomUUID(), authId,
                        new BigDecimal("60000"), null, Instant.now()));

        var holdBefore = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(holdBefore.get().getStatus()).isEqualTo(HoldStatus.PARTIALLY_CAPTURED);

        // 환불
        refundHoldUseCase.refund(new RefundHoldCommand(authId, "부분매입환불"));

        var hold = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(hold).isPresent();
        assertThat(hold.get().getStatus()).isEqualTo(HoldStatus.REFUNDED);
    }

    @Test
    @DisplayName("ACTIVE 홀드 환불 시도 — 도메인 예외 발생(매입 전 환불 불가)")
    void refundActiveHold_throwsDomainException() {
        long orgId = 8003L;
        long ownerId = 404L;
        long holderUserId = 405L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "refund-seller-003", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        String authId = authorizeAndGetId(card.getId(), new BigDecimal("50000"));

        // 매입 없이 바로 환불 시도 → 예외
        assertThatThrownBy(
                () -> refundHoldUseCase.refund(new RefundHoldCommand(authId, "ACTIVE환불시도")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAPTURED");
    }
}
