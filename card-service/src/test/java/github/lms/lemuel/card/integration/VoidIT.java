package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase;
import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase.CaptureHoldCommand;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.in.VoidHoldUseCase;
import github.lms.lemuel.card.application.port.in.VoidHoldUseCase.VoidHoldCommand;
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
 * 카드 취소(Void) 통합 테스트.
 *
 * <p>ACTIVE 홀드 취소·PARTIALLY_CAPTURED 홀드 취소·이미 CAPTURED 된 홀드 취소 시도를 검증한다.
 * 취소 후 가용한도가 복구됨을 ACTIVE 홀드 합계로 확인한다.
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
class VoidIT {

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
    @Autowired VoidHoldUseCase voidHoldUseCase;
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
        String authId = "AUTH-VOID-" + UUID.randomUUID();
        var result = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(authId, cardId, amount, "테스트가맹점", "5812", false, false));
        assertThat(result.approved()).isTrue();
        return authId;
    }

    @Test
    @DisplayName("ACTIVE 홀드 취소 — 상태가 VOIDED 로 전환되고 한도가 복구된다")
    void voidActiveHold_changesStatusToVoided_andRestoresLimit() {
        long orgId = 7001L;
        long ownerId = 300L;
        long holderUserId = 301L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "void-seller-001", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        BigDecimal amount = new BigDecimal("50000");
        String authId = authorizeAndGetId(card.getId(), amount);

        // 취소 전 ACTIVE 홀드 합계 = 50000
        assertThat(loadAuthorizationHoldPort.sumActiveHoldsByCard(card.getId()))
                .isEqualByComparingTo("50000");

        // 취소
        voidHoldUseCase.voidHold(new VoidHoldCommand(authId, "테스트취소"));

        // 홀드 상태 검증
        var hold = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(hold).isPresent();
        assertThat(hold.get().getStatus()).isEqualTo(HoldStatus.VOIDED);

        // 한도 복구 — ACTIVE 홀드 합계 = 0
        assertThat(loadAuthorizationHoldPort.sumActiveHoldsByCard(card.getId()))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("PARTIALLY_CAPTURED 홀드 취소 — 상태가 VOIDED 로 전환된다")
    void voidPartiallyCapturedHold_changesStatusToVoided() {
        long orgId = 7002L;
        long ownerId = 302L;
        long holderUserId = 303L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "void-seller-002", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        String authId = authorizeAndGetId(card.getId(), limit);

        // 부분 매입 → PARTIALLY_CAPTURED
        captureHoldUseCase.capture(
                new CaptureHoldCommand("CAP-PCV-" + UUID.randomUUID(), authId,
                        new BigDecimal("60000"), null, Instant.now()));

        var holdBefore = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(holdBefore.get().getStatus()).isEqualTo(HoldStatus.PARTIALLY_CAPTURED);

        // 나머지 취소
        voidHoldUseCase.voidHold(new VoidHoldCommand(authId, "부분매입후잔여취소"));

        var holdAfter = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(holdAfter).isPresent();
        assertThat(holdAfter.get().getStatus()).isEqualTo(HoldStatus.VOIDED);
    }

    @Test
    @DisplayName("전액 매입된(CAPTURED) 홀드 취소 시도 — 도메인 예외 발생")
    void voidCapturedHold_throwsDomainException() {
        long orgId = 7003L;
        long ownerId = 304L;
        long holderUserId = 305L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "void-seller-003", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        String authId = authorizeAndGetId(card.getId(), new BigDecimal("50000"));

        // 전액 매입 → CAPTURED
        captureHoldUseCase.capture(
                new CaptureHoldCommand("CAP-FC-" + UUID.randomUUID(), authId,
                        new BigDecimal("50000"), null, Instant.now()));

        // 이미 CAPTURED 된 홀드 취소 시도 → 예외
        assertThatThrownBy(
                () -> voidHoldUseCase.voidHold(new VoidHoldCommand(authId, "CAPTURED취소시도")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAPTURED");
    }
}
