package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.CloseStatementUseCase;
import github.lms.lemuel.card.application.port.in.OpenCardStatementUseCase;
import github.lms.lemuel.card.application.port.in.PayStatementUseCase;
import github.lms.lemuel.card.application.port.in.PayStatementUseCase.PayStatementCommand;
import github.lms.lemuel.card.application.port.out.LoadCardStatementPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardStatement;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.card.domain.StatementStatus;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 명세서 청구·상환 통합 테스트 (Phase 2 AC3).
 *
 * <p>실 PostgreSQL(Testcontainers)에서 다음을 검증한다:
 * <ul>
 *   <li>OPEN 명세서 마감(OPEN→CLOSED)</li>
 *   <li>일부 납부(CLOSED→PARTIALLY_PAID)</li>
 *   <li>전액 납부(→PAID) + lemuel.card.statement_paid Outbox 이벤트 발행</li>
 *   <li>paymentId 멱등 — 동일 paymentId 재전송은 중복 처리 안 됨</li>
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
class StatementBillingIT {

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
    @Autowired OpenCardStatementUseCase openCardStatementUseCase;
    @Autowired CloseStatementUseCase closeStatementUseCase;
    @Autowired PayStatementUseCase payStatementUseCase;
    @Autowired LoadCardStatementPort loadCardStatementPort;
    @Autowired SaveCardAccountPort saveCardAccountPort;

    @BeforeEach
    void clean() {
        jdbc.execute(
                "TRUNCATE TABLE opslab.statement_payments, opslab.card_statements, " +
                "opslab.card_accounts, opslab.outbox_events RESTART IDENTITY CASCADE");
    }

    // ── 공통 픽스처 헬퍼 ──────────────────────────────────────────

    private CardAccount createActiveAccount(Long orgId, String sellerId, BigDecimal masterLimit) {
        CardAccount account = CardAccount.open(orgId, sellerId);
        account.activate(masterLimit, new LimitSnapshot(masterLimit, BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "test*0.7"));
        return saveCardAccountPort.save(account);
    }

    // ── 테스트 ────────────────────────────────────────────────────

    @Test
    @DisplayName("OPEN 명세서 마감 — OPEN → CLOSED, closedAt 이 채워진다")
    void closeStatement_changesStatusToClosedAndSetsClosedAt() {
        CardAccount account = createActiveAccount(7001L, "stmt-seller-001", new BigDecimal("500000"));
        YearMonth period = YearMonth.of(2026, 7);  // 이전 달
        LocalDate dueDate = LocalDate.of(2026, 8, 10);

        // OPEN 명세서 생성
        CardStatement statement = openCardStatementUseCase.getOrOpenStatement(
                account.getId(), period, dueDate);
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.OPEN);

        // 마감
        List<Long> closedIds = closeStatementUseCase.closeStatements(period);

        assertThat(closedIds).contains(statement.getId());

        // DB 재조회 검증
        CardStatement closed = loadCardStatementPort.findById(statement.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(StatementStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("일부 납부 — CLOSED → PARTIALLY_PAID, 미납 잔액이 계산된다")
    void partialPayment_changesStatusToPartiallyPaid() {
        CardAccount account = createActiveAccount(7002L, "stmt-seller-002", new BigDecimal("500000"));
        YearMonth period = YearMonth.of(2026, 7);
        LocalDate dueDate = LocalDate.of(2026, 8, 10);

        CardStatement statement = openCardStatementUseCase.getOrOpenStatement(
                account.getId(), period, dueDate);

        // DB에 직접 총액 설정(테스트 편의 — addCharge 직접 호출)
        jdbc.update(
                "UPDATE opslab.card_statements SET total_amount = 300000 WHERE id = ?",
                statement.getId());

        // 마감
        closeStatementUseCase.closeStatements(period);

        // 일부 납부
        String paymentId = "PAY-PARTIAL-" + UUID.randomUUID();
        CardStatement result = payStatementUseCase.pay(
                new PayStatementCommand(statement.getId(), paymentId, new BigDecimal("100000")));

        assertThat(result.getStatus()).isEqualTo(StatementStatus.PARTIALLY_PAID);
        assertThat(result.getPaidAmount()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(result.unpaidAmount()).isEqualByComparingTo(new BigDecimal("200000"));
    }

    @Test
    @DisplayName("전액 납부 — PAID 로 전환 + lemuel.card.statement_paid Outbox 이벤트 발행")
    void fullPayment_changeStatusToPaid_andPublishesEvent() {
        CardAccount account = createActiveAccount(7003L, "stmt-seller-003", new BigDecimal("500000"));
        YearMonth period = YearMonth.of(2026, 7);
        LocalDate dueDate = LocalDate.of(2026, 8, 10);

        CardStatement statement = openCardStatementUseCase.getOrOpenStatement(
                account.getId(), period, dueDate);

        // DB에 직접 총액 설정
        jdbc.update(
                "UPDATE opslab.card_statements SET total_amount = 200000 WHERE id = ?",
                statement.getId());

        // 마감
        closeStatementUseCase.closeStatements(period);

        // 전액 납부
        String paymentId = "PAY-FULL-" + UUID.randomUUID();
        CardStatement result = payStatementUseCase.pay(
                new PayStatementCommand(statement.getId(), paymentId, new BigDecimal("200000")));

        assertThat(result.getStatus()).isEqualTo(StatementStatus.PAID);
        assertThat(result.isFullyPaid()).isTrue();

        // Outbox 이벤트 발행 검증
        Integer eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE event_type = 'CardStatementPaid'",
                Integer.class);
        assertThat(eventCount).isEqualTo(1);

        // 이벤트 페이로드 검증
        String payload = jdbc.queryForObject(
                "SELECT payload FROM opslab.outbox_events WHERE event_type = 'CardStatementPaid'",
                String.class);
        assertThat(payload)
                .contains("\"statementId\"")
                .contains("\"cardAccountId\"")
                .contains("\"billingYearMonth\"")
                .contains("\"paidAmount\"")     // DATA-STANDARD N5 — 금액은 JSON string
                .contains("200000")             // 금액 값 포함 (소수점 형식은 DB 정밀도에 따름)
                .contains("\"paymentId\"")
                .contains("\"paidAt\"");
    }

    @Test
    @DisplayName("동일 paymentId 재전송 — 멱등 처리, 중복 납부 레코드 미생성")
    void idempotentPayment_noDuplicateRecord() {
        CardAccount account = createActiveAccount(7004L, "stmt-seller-004", new BigDecimal("500000"));
        YearMonth period = YearMonth.of(2026, 7);
        LocalDate dueDate = LocalDate.of(2026, 8, 10);

        CardStatement statement = openCardStatementUseCase.getOrOpenStatement(
                account.getId(), period, dueDate);

        jdbc.update(
                "UPDATE opslab.card_statements SET total_amount = 100000 WHERE id = ?",
                statement.getId());
        closeStatementUseCase.closeStatements(period);

        String paymentId = "PAY-IDEM-" + UUID.randomUUID();
        PayStatementCommand cmd = new PayStatementCommand(
                statement.getId(), paymentId, new BigDecimal("50000"));

        // 동일 커맨드 두 번
        CardStatement r1 = payStatementUseCase.pay(cmd);
        CardStatement r2 = payStatementUseCase.pay(cmd);

        // 두 결과 상태 동일
        assertThat(r1.getStatus()).isEqualTo(r2.getStatus());

        // statement_payments 는 1건만
        Integer paymentCount = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.statement_payments WHERE payment_id = ?",
                Integer.class, paymentId);
        assertThat(paymentCount).isEqualTo(1);
    }

    @Test
    @DisplayName("getOrOpenStatement 멱등 — 같은 계정·주기로 두 번 호출해도 명세서 1개")
    void getOrOpenStatement_idempotent() {
        CardAccount account = createActiveAccount(7005L, "stmt-seller-005", new BigDecimal("500000"));
        YearMonth period = YearMonth.of(2026, 8);
        LocalDate dueDate = LocalDate.of(2026, 9, 10);

        CardStatement s1 = openCardStatementUseCase.getOrOpenStatement(
                account.getId(), period, dueDate);
        CardStatement s2 = openCardStatementUseCase.getOrOpenStatement(
                account.getId(), period, dueDate);

        assertThat(s1.getId()).isEqualTo(s2.getId());

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.card_statements WHERE card_account_id = ? AND billing_year_month = ?",
                Integer.class, account.getId(), period.toString());
        assertThat(count).isEqualTo(1);
    }
}
