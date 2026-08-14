package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase;
import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase.CaptureHoldCommand;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.out.LoadAuthorizationHoldPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardCapture;
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

/**
 * 카드 매입(Capture) 통합 테스트.
 *
 * <p>전액 매입·부분 매입·멱등 재전송을 실 PostgreSQL(Testcontainers)로 검증한다.
 * 매입 시 {@code lemuel.card.captured} Outbox 이벤트가 계약 스키마를 준수해 발행됨을 확인한다.
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
class CaptureIT {

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

    // ── 공통 픽스처 헬퍼 ────────────────────────────────────────

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
        String authId = "AUTH-" + UUID.randomUUID();
        var result = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(authId, cardId, amount, "테스트가맹점", "5812", false, false));
        assertThat(result.approved()).isTrue();
        return authId;
    }

    // ── 테스트 ───────────────────────────────────────────────────

    @Test
    @DisplayName("전액 매입 — 홀드 상태가 CAPTURED 로 전환되고 Outbox 이벤트가 발행된다")
    void fullCapture_changesStatusToCaptured_andPublishesEvent() {
        long orgId = 6001L;
        long ownerId = 200L;
        long holderUserId = 201L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "cap-seller-001", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        BigDecimal amount = new BigDecimal("45000");
        String authId = authorizeAndGetId(card.getId(), amount);

        // 전액 매입
        String captureId = "CAP-" + UUID.randomUUID();
        CardCapture capture = captureHoldUseCase.capture(
                new CaptureHoldCommand(captureId, authId, amount, "테스트가맹점", Instant.now()));

        // 매입 레코드 검증
        assertThat(capture.getCaptureId()).isEqualTo(captureId);
        assertThat(capture.getAuthorizationId()).isEqualTo(authId);
        assertThat(capture.getCapturedAmount()).isEqualByComparingTo(amount);

        // 홀드 상태 검증
        var hold = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(hold).isPresent();
        assertThat(hold.get().getStatus()).isEqualTo(HoldStatus.CAPTURED);

        // Outbox 이벤트 발행 검증
        Integer eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'CardCaptured'",
                Integer.class);
        assertThat(eventCount).isEqualTo(1);

        // 이벤트 페이로드 검증 — 계약 필수 필드
        String payload = jdbc.queryForObject(
                "SELECT payload FROM opslab.outbox_events WHERE event_type = 'CardCaptured'",
                String.class);
        assertThat(payload)
                .contains("\"captureId\"")
                .contains("\"authorizationId\"")
                .contains("\"cardId\"")
                .contains("\"cardAccountId\"")
                .contains("\"amount\"")
                .contains("\"45000\"")               // DATA-STANDARD N5 — 문자열
                .contains("\"capturedAt\"");
    }

    @Test
    @DisplayName("부분 매입 — 홀드 상태가 PARTIALLY_CAPTURED 로 전환된다")
    void partialCapture_changesStatusToPartiallyCaptured() {
        long orgId = 6002L;
        long ownerId = 202L;
        long holderUserId = 203L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "cap-seller-002", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        BigDecimal authAmount = new BigDecimal("100000");
        String authId = authorizeAndGetId(card.getId(), authAmount);

        // 부분 매입 (60%)
        BigDecimal partialAmount = new BigDecimal("60000");
        String captureId = "CAP-PARTIAL-" + UUID.randomUUID();
        CardCapture capture = captureHoldUseCase.capture(
                new CaptureHoldCommand(captureId, authId, partialAmount, "테스트가맹점", Instant.now()));

        assertThat(capture.getCapturedAmount()).isEqualByComparingTo(partialAmount);

        // 홀드는 PARTIALLY_CAPTURED 상태
        var hold = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(hold).isPresent();
        assertThat(hold.get().getStatus()).isEqualTo(HoldStatus.PARTIALLY_CAPTURED);

        // Outbox 이벤트 발행 확인
        Integer eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'CardCaptured'",
                Integer.class);
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("부분 매입 후 잔여 전액 매입 — 홀드 상태가 CAPTURED 로 전환된다")
    void partialThenFullCapture_changesStatusToCaptured() {
        long orgId = 6003L;
        long ownerId = 204L;
        long holderUserId = 205L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "cap-seller-003", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        String authId = authorizeAndGetId(card.getId(), limit);

        // 1차 부분 매입
        captureHoldUseCase.capture(
                new CaptureHoldCommand("CAP-P1-" + UUID.randomUUID(), authId,
                        new BigDecimal("60000"), null, Instant.now()));

        // 2차 전액 매입 (승인 금액과 같은 금액으로)
        captureHoldUseCase.capture(
                new CaptureHoldCommand("CAP-P2-" + UUID.randomUUID(), authId,
                        new BigDecimal("100000"), null, Instant.now()));

        // 홀드가 CAPTURED 상태여야 한다 (2차 매입이 hold.amount == capturedAmount 이므로)
        var hold = loadAuthorizationHoldPort.findByAuthorizationId(authId);
        assertThat(hold).isPresent();
        assertThat(hold.get().getStatus()).isEqualTo(HoldStatus.CAPTURED);

        // Outbox 이벤트 2건 발행
        Integer eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'CardCaptured'",
                Integer.class);
        assertThat(eventCount).isEqualTo(2);
    }

    @Test
    @DisplayName("동일 captureId 재전송은 멱등 처리 — 중복 매입 레코드 미생성")
    void idempotentCapture_returnsSameCapture_noDuplicateRecord() {
        long orgId = 6004L;
        long ownerId = 206L;
        long holderUserId = 207L;
        BigDecimal limit = new BigDecimal("100000");

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "cap-seller-004", limit);
        Card card = issueCard(account.getId(), holderUserId, limit, ownerId);

        String authId = authorizeAndGetId(card.getId(), new BigDecimal("50000"));
        String captureId = "CAP-IDEM-" + UUID.randomUUID();
        CaptureHoldCommand cmd = new CaptureHoldCommand(
                captureId, authId, new BigDecimal("50000"), "가맹점", Instant.now());

        // 동일 커맨드 두 번 호출
        CardCapture c1 = captureHoldUseCase.capture(cmd);
        CardCapture c2 = captureHoldUseCase.capture(cmd);

        assertThat(c1.getCaptureId()).isEqualTo(c2.getCaptureId());

        // card_captures 는 1건만 생성
        Integer captureCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.card_captures WHERE capture_id = ?",
                Integer.class, captureId);
        assertThat(captureCount).isEqualTo(1);

        // Outbox 이벤트도 1건만
        Integer eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'CardCaptured'",
                Integer.class);
        assertThat(eventCount).isEqualTo(1);
    }
}
