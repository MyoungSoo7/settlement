package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import github.lms.lemuel.card.application.port.in.CloseStatementUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.in.MarkDelinquentStatementsUseCase;
import github.lms.lemuel.card.application.port.in.OpenCardStatementUseCase;
import github.lms.lemuel.card.application.port.in.PayStatementUseCase;
import github.lms.lemuel.card.application.port.in.PayStatementUseCase.PayStatementCommand;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardStatement;
import github.lms.lemuel.card.domain.DeclineReason;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연체 자동 전이 + 승인 차단 통합 테스트 (Phase 2 AC3).
 *
 * <p>실 PostgreSQL(Testcontainers)에서 다음을 검증한다:
 * <ul>
 *   <li>만기 경과 미납 명세서 → markDelinquent() → 카드계정 DELINQUENT 전이</li>
 *   <li>DELINQUENT 계정에서 승인 → CARD_SUSPENDED 거절</li>
 *   <li>전액 납부 → DELINQUENT → ACTIVE 자동 회복</li>
 *   <li>ACTIVE 복구 후 승인 성공</li>
 * </ul>
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
class DelinquencyAuthorizationIT {

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
    @Autowired MarkDelinquentStatementsUseCase markDelinquentStatementsUseCase;
    @Autowired OpenCardStatementUseCase openCardStatementUseCase;
    @Autowired CloseStatementUseCase closeStatementUseCase;
    @Autowired PayStatementUseCase payStatementUseCase;
    @Autowired AuthorizeCardUseCase authorizeCardUseCase;
    @Autowired IssueCardUseCase issueCardUseCase;
    @Autowired SaveCardAccountPort saveCardAccountPort;
    @Autowired SaveOrgProjectionPort saveOrgProjectionPort;
    @Autowired LoadCardAccountPort loadCardAccountPort;

    @BeforeEach
    void clean() {
        jdbc.execute(
                "TRUNCATE TABLE opslab.statement_payments, opslab.card_statements, " +
                "opslab.authorization_holds, opslab.cards, opslab.card_accounts, " +
                "opslab.org_member_projection, opslab.outbox_events RESTART IDENTITY CASCADE");
    }

    // ── 픽스처 헬퍼 ────────────────────────────────────────────────

    private CardAccount createActiveAccount(Long orgId, String sellerId, BigDecimal masterLimit) {
        CardAccount account = CardAccount.open(orgId, sellerId);
        account.activate(masterLimit, new LimitSnapshot(masterLimit, BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "test*0.7"));
        return saveCardAccountPort.save(account);
    }

    private Card issueCard(Long cardAccountId, Long holderUserId, BigDecimal subLimit, Long ownerId) {
        return issueCardUseCase.issue(new IssueCardCommand(cardAccountId, holderUserId, subLimit, ownerId));
    }

    private void addMember(Long orgId, Long userId, OrgRole role) {
        saveOrgProjectionPort.upsertMember(orgId, userId, role.name(), orgId * 1000L + userId);
    }

    /**
     * 과거 만기일이 있는 명세서를 생성하고 마감한 뒤 ID 를 반환한다.
     */
    private CardStatement createOverdueStatement(Long accountId, BigDecimal totalAmount) {
        // 이미 만기된 주기(2026-07, 만기 2026-08-10)
        YearMonth period = YearMonth.of(2026, 7);
        LocalDate pastDueDate = LocalDate.of(2026, 8, 10);

        CardStatement statement = openCardStatementUseCase.getOrOpenStatement(
                accountId, period, pastDueDate);

        // 총액 직접 설정
        jdbc.update("UPDATE opslab.card_statements SET total_amount = ? WHERE id = ?",
                totalAmount, statement.getId());

        // 마감
        closeStatementUseCase.closeStatements(period);

        return openCardStatementUseCase.getOrOpenStatement(accountId, period, pastDueDate);
    }

    // ── 테스트 ────────────────────────────────────────────────────

    @Test
    @DisplayName("연체 배치 실행 — 만기 경과 미납 명세서가 있으면 카드계정이 DELINQUENT 로 전이한다")
    void markDelinquent_transitionsAccountToDelinquent() {
        Long orgId = 8001L;
        Long ownerId = 1001L;

        addMember(orgId, ownerId, OrgRole.OWNER);
        CardAccount account = createActiveAccount(orgId, "delinq-seller-001", new BigDecimal("500000"));

        createOverdueStatement(account.getId(), new BigDecimal("200000"));

        // 연체 배치 실행(기준일: 만기일 이후)
        int processed = markDelinquentStatementsUseCase.markDelinquent(LocalDate.of(2026, 8, 15));

        assertThat(processed).isGreaterThanOrEqualTo(1);

        // 카드계정 상태 확인
        CardAccount refreshed = loadCardAccountPort.findById(account.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(CardAccountStatus.DELINQUENT);
    }

    @Test
    @DisplayName("DELINQUENT 계정 → 승인 요청 → CARD_SUSPENDED 거절 (연체 자동 차단)")
    void delinquentAccount_authorizationDeclined_withCardSuspended() {
        Long orgId = 8002L;
        Long ownerId = 1002L;
        Long holderUserId = 2002L;

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "delinq-seller-002", new BigDecimal("500000"));
        Card card = issueCard(account.getId(), holderUserId, new BigDecimal("500000"), ownerId);

        createOverdueStatement(account.getId(), new BigDecimal("150000"));

        // 연체 배치
        markDelinquentStatementsUseCase.markDelinquent(LocalDate.of(2026, 8, 15));

        // 승인 시도 — DELINQUENT 이므로 CARD_SUSPENDED 거절
        AuthorizeCardUseCase.AuthorizationResult result = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(
                        "DELINQ-AUTH-" + UUID.randomUUID(),
                        card.getId(),
                        new BigDecimal("50000"),
                        "테스트가맹점", "5812", false, false));

        assertThat(result.approved()).isFalse();
        assertThat(result.declineReason()).isEqualTo(DeclineReason.CARD_SUSPENDED);
    }

    @Test
    @DisplayName("DELINQUENT 계정 전액 납부 → ACTIVE 자동 회복 → 승인 성공")
    void fullPayment_recoversDelinquentAccountToActive_authorizationSucceeds() {
        Long orgId = 8003L;
        Long ownerId = 1003L;
        Long holderUserId = 2003L;

        addMember(orgId, ownerId, OrgRole.OWNER);
        addMember(orgId, holderUserId, OrgRole.STAFF);

        CardAccount account = createActiveAccount(orgId, "delinq-seller-003", new BigDecimal("500000"));
        Card card = issueCard(account.getId(), holderUserId, new BigDecimal("500000"), ownerId);

        CardStatement statement = createOverdueStatement(account.getId(), new BigDecimal("100000"));

        // 연체 배치 → DELINQUENT
        markDelinquentStatementsUseCase.markDelinquent(LocalDate.of(2026, 8, 15));
        assertThat(loadCardAccountPort.findById(account.getId()).orElseThrow().getStatus())
                .isEqualTo(CardAccountStatus.DELINQUENT);

        // DELINQUENT 중 승인 → 거절
        AuthorizeCardUseCase.AuthorizationResult beforePayment = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(
                        "BEFORE-PAY-" + UUID.randomUUID(),
                        card.getId(), new BigDecimal("30000"), "가맹점", "5812", false, false));
        assertThat(beforePayment.approved()).isFalse();

        // 전액 납부
        PayStatementCommand payCmd = new PayStatementCommand(
                statement.getId(), "FULL-PAY-" + UUID.randomUUID(), new BigDecimal("100000"));
        CardStatement paid = payStatementUseCase.pay(payCmd);

        // 명세서 PAID 확인
        assertThat(paid.isFullyPaid()).isTrue();

        // 계정 ACTIVE 복구 확인
        CardAccount recovered = loadCardAccountPort.findById(account.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);

        // ACTIVE 복구 후 승인 성공
        AuthorizeCardUseCase.AuthorizationResult afterPayment = authorizeCardUseCase.authorize(
                new AuthorizeCardCommand(
                        "AFTER-PAY-" + UUID.randomUUID(),
                        card.getId(), new BigDecimal("30000"), "가맹점", "5812", false, false));
        assertThat(afterPayment.approved()).isTrue();
    }

    @Test
    @DisplayName("만기 미경과 명세서는 연체 대상 아님 — 배치가 처리하지 않는다")
    void notOverdue_notMarkedDelinquent() {
        Long orgId = 8004L;
        Long ownerId = 1004L;

        addMember(orgId, ownerId, OrgRole.OWNER);
        CardAccount account = createActiveAccount(orgId, "delinq-seller-004", new BigDecimal("500000"));

        // 아직 만기 안 됨(미래 주기)
        YearMonth futurePeriod = YearMonth.of(2026, 9);
        LocalDate futureDueDate = LocalDate.of(2026, 10, 10);
        CardStatement statement = openCardStatementUseCase.getOrOpenStatement(
                account.getId(), futurePeriod, futureDueDate);

        jdbc.update("UPDATE opslab.card_statements SET total_amount = 50000 WHERE id = ?",
                statement.getId());
        closeStatementUseCase.closeStatements(futurePeriod);

        // 오늘 날짜 기준(만기 전)으로 배치 실행
        int processed = markDelinquentStatementsUseCase.markDelinquent(LocalDate.of(2026, 9, 15));
        assertThat(processed).isEqualTo(0);

        // 계정 ACTIVE 유지
        CardAccount refreshed = loadCardAccountPort.findById(account.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("이미 DELINQUENT 인 계정 — 재연체 배치는 상태를 중복 전이시키지 않는다")
    void alreadyDelinquent_batchProcessesGracefully() {
        Long orgId = 8005L;
        Long ownerId = 1005L;

        addMember(orgId, ownerId, OrgRole.OWNER);
        CardAccount account = createActiveAccount(orgId, "delinq-seller-005", new BigDecimal("500000"));

        createOverdueStatement(account.getId(), new BigDecimal("80000"));

        // 첫 번째 배치
        int first = markDelinquentStatementsUseCase.markDelinquent(LocalDate.of(2026, 8, 15));
        assertThat(first).isGreaterThanOrEqualTo(1);

        // 두 번째 배치(같은 기준일) — 이미 DELINQUENT 인 명세서는 중복 처리 없음
        // (명세서 status 가 이미 DELINQUENT 라 findOverdueAndUnpaid 에서 제외됨)
        int second = markDelinquentStatementsUseCase.markDelinquent(LocalDate.of(2026, 8, 16));
        assertThat(second).isEqualTo(0);

        // 계정 여전히 DELINQUENT
        assertThat(loadCardAccountPort.findById(account.getId()).orElseThrow().getStatus())
                .isEqualTo(CardAccountStatus.DELINQUENT);
    }
}
