package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.ApproveExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.ApproveExpenseReportUseCase.ApproveExpenseReportCommand;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase;
import github.lms.lemuel.card.application.port.in.AuthorizeCardUseCase.AuthorizeCardCommand;
import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase;
import github.lms.lemuel.card.application.port.in.CaptureHoldUseCase.CaptureHoldCommand;
import github.lms.lemuel.card.application.port.in.CreateExpenseReportFromCaptureUseCase;
import github.lms.lemuel.card.application.port.in.CreateExpenseReportFromCaptureUseCase.CreateExpenseReportCommand;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase;
import github.lms.lemuel.card.application.port.in.IssueCardUseCase.IssueCardCommand;
import github.lms.lemuel.card.application.port.in.QueryBudgetUtilizationUseCase;
import github.lms.lemuel.card.application.port.in.RejectExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.RejectExpenseReportUseCase.RejectExpenseReportCommand;
import github.lms.lemuel.card.application.port.in.SubmitExpenseReportUseCase;
import github.lms.lemuel.card.application.port.in.SubmitExpenseReportUseCase.SubmitExpenseReportCommand;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardCapture;
import github.lms.lemuel.card.domain.ExpenseReport;
import github.lms.lemuel.card.domain.ExpenseReportStatus;
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
 * 사후 지출관리 워크플로 통합 테스트 (Phase 2 AC4).
 *
 * <p>ExpenseReport DRAFT 자동 생성 → 제출 → 승인/반려 → 재제출 전 사이클과
 * 부서 예산 소진율 조회를 실 PostgreSQL(Testcontainers)로 검증한다.
 *
 * <p><b>승인 경로와 완전 비결합</b>: 이 테스트는 {@code AuthorizeCardUseCase} 에서
 * {@code ExpenseWorkflowService} 로의 의존이 없음을 전제한다. 비결합 보장은
 * {@link github.lms.lemuel.card.application.service.ExpenseWorkflowDecouplingTest} 가 담당한다.
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
class ExpenseWorkflowIT {

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
    @Autowired CreateExpenseReportFromCaptureUseCase createExpenseReportFromCaptureUseCase;
    @Autowired SubmitExpenseReportUseCase submitExpenseReportUseCase;
    @Autowired ApproveExpenseReportUseCase approveExpenseReportUseCase;
    @Autowired RejectExpenseReportUseCase rejectExpenseReportUseCase;
    @Autowired QueryBudgetUtilizationUseCase queryBudgetUtilizationUseCase;

    @BeforeEach
    void clean() {
        jdbc.execute(
                "TRUNCATE TABLE opslab.expense_reports, opslab.department_budgets,"
                        + " opslab.card_captures, opslab.authorization_holds, opslab.cards,"
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

    private Card issuedCard(CardAccount account, Long userId, BigDecimal subLimit) {
        // OWNER 등록(발급 권한 보유) + STAFF 등록(카드 소지인)
        Long ownerId = account.getOrganizationId(); // 조직 ID를 OWNER 식별자로 재사용(테스트 편의)
        saveOrgProjectionPort.upsertMember(
                account.getOrganizationId(), ownerId, OrgRole.OWNER.name(),
                account.getOrganizationId() * 1000L + ownerId);
        saveOrgProjectionPort.upsertMember(
                account.getOrganizationId(), userId, OrgRole.STAFF.name(),
                account.getOrganizationId() * 1000L + userId);
        return issueCardUseCase.issue(
                new IssueCardCommand(account.getId(), userId, subLimit, ownerId));
    }

    private String authorize(Card card, BigDecimal amount) {
        String authId = "AUTH-" + UUID.randomUUID();
        authorizeCardUseCase.authorize(new AuthorizeCardCommand(
                authId, card.getId(), amount, "슈퍼마트", "5411", false, false));
        return authId;
    }

    private CardCapture doCapture(String authId, BigDecimal amount, String merchantName) {
        String captureId = "CAP-" + UUID.randomUUID();
        return captureHoldUseCase.capture(new CaptureHoldCommand(
                captureId, authId, amount, merchantName, Instant.now()));
    }

    // ── 테스트 ────────────────────────────────────────────────

    @Test
    @DisplayName("매입 확정 후 ExpenseReport(DRAFT) 자동 생성 — captureId 멱등")
    void createExpenseReportFromCapture_draft() {
        CardAccount account = createActiveAccount(100L, "SELLER-1", new BigDecimal("500000"));
        Card card = issuedCard(account, 200L, new BigDecimal("200000"));
        String authId = authorize(card, new BigDecimal("50000"));
        CardCapture capture = doCapture(authId, new BigDecimal("50000"), "스타벅스");

        ExpenseReport report = createExpenseReportFromCaptureUseCase.createFromCapture(
                new CreateExpenseReportCommand(
                        capture.getCaptureId(),
                        capture.getAuthorizationId(),
                        capture.getCardId(),
                        capture.getCardAccountId(),
                        account.getOrganizationId(),
                        "DEPT-A",
                        capture.getHolderUserId(),
                        capture.getCapturedAmount(),
                        capture.getMerchantName(),
                        capture.getCapturedAt()
                ));

        assertThat(report.getReportId()).isNotBlank();
        assertThat(report.getStatus()).isEqualTo(ExpenseReportStatus.DRAFT);
        assertThat(report.getCaptureId()).isEqualTo(capture.getCaptureId());
        assertThat(report.getAmount()).isEqualByComparingTo(new BigDecimal("50000"));

        // 멱등: 같은 captureId 재요청 → 기존 보고서 반환
        ExpenseReport duplicate = createExpenseReportFromCaptureUseCase.createFromCapture(
                new CreateExpenseReportCommand(
                        capture.getCaptureId(),
                        capture.getAuthorizationId(),
                        capture.getCardId(),
                        capture.getCardAccountId(),
                        account.getOrganizationId(),
                        "DEPT-A",
                        capture.getHolderUserId(),
                        capture.getCapturedAmount(),
                        capture.getMerchantName(),
                        capture.getCapturedAt()
                ));
        assertThat(duplicate.getReportId()).isEqualTo(report.getReportId());
    }

    @Test
    @DisplayName("DRAFT → SUBMITTED 전이: 영수증·카테고리·메모 첨부")
    void submitExpenseReport_draftToSubmitted() {
        CardAccount account = createActiveAccount(100L, "SELLER-2", new BigDecimal("500000"));
        Card card = issuedCard(account, 201L, new BigDecimal("200000"));
        String authId = authorize(card, new BigDecimal("30000"));
        CardCapture capture = doCapture(authId, new BigDecimal("30000"), "올리브영");

        ExpenseReport draft = createExpenseReportFromCaptureUseCase.createFromCapture(
                new CreateExpenseReportCommand(
                        capture.getCaptureId(), capture.getAuthorizationId(),
                        capture.getCardId(), capture.getCardAccountId(),
                        account.getOrganizationId(), "DEPT-B",
                        capture.getHolderUserId(), capture.getCapturedAmount(),
                        capture.getMerchantName(), capture.getCapturedAt()));

        ExpenseReport submitted = submitExpenseReportUseCase.submit(
                new SubmitExpenseReportCommand(
                        draft.getReportId(),
                        "https://receipts.example.com/r1.jpg",
                        "OFFICE_SUPPLIES",
                        "사무용품 구매"));

        assertThat(submitted.getStatus()).isEqualTo(ExpenseReportStatus.SUBMITTED);
        assertThat(submitted.getReceiptUrl()).isEqualTo("https://receipts.example.com/r1.jpg");
        assertThat(submitted.getExpenseCategory()).isEqualTo("OFFICE_SUPPLIES");
        assertThat(submitted.getMemo()).isEqualTo("사무용품 구매");
    }

    @Test
    @DisplayName("SUBMITTED → APPROVED 전이: 관리자 승인")
    void approveExpenseReport_submittedToApproved() {
        CardAccount account = createActiveAccount(100L, "SELLER-3", new BigDecimal("500000"));
        Card card = issuedCard(account, 202L, new BigDecimal("200000"));
        String authId = authorize(card, new BigDecimal("20000"));
        CardCapture capture = doCapture(authId, new BigDecimal("20000"), "GS25");

        ExpenseReport draft = createExpenseReportFromCaptureUseCase.createFromCapture(
                new CreateExpenseReportCommand(
                        capture.getCaptureId(), capture.getAuthorizationId(),
                        capture.getCardId(), capture.getCardAccountId(),
                        account.getOrganizationId(), "DEPT-C",
                        capture.getHolderUserId(), capture.getCapturedAmount(),
                        capture.getMerchantName(), capture.getCapturedAt()));

        submitExpenseReportUseCase.submit(
                new SubmitExpenseReportCommand(
                        draft.getReportId(),
                        "https://receipts.example.com/r2.jpg",
                        "MEAL", "팀 점심"));

        Long managerId = 9999L;
        ExpenseReport approved = approveExpenseReportUseCase.approve(
                new ApproveExpenseReportCommand(draft.getReportId(), managerId));

        assertThat(approved.getStatus()).isEqualTo(ExpenseReportStatus.APPROVED);
        assertThat(approved.getReviewedBy()).isEqualTo(managerId);
        assertThat(approved.getReviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("SUBMITTED → REJECTED 전이, 재제출(REJECTED → SUBMITTED) 가능")
    void rejectAndResubmitExpenseReport() {
        CardAccount account = createActiveAccount(100L, "SELLER-4", new BigDecimal("500000"));
        Card card = issuedCard(account, 203L, new BigDecimal("200000"));
        String authId = authorize(card, new BigDecimal("15000"));
        CardCapture capture = doCapture(authId, new BigDecimal("15000"), "CU편의점");

        ExpenseReport draft = createExpenseReportFromCaptureUseCase.createFromCapture(
                new CreateExpenseReportCommand(
                        capture.getCaptureId(), capture.getAuthorizationId(),
                        capture.getCardId(), capture.getCardAccountId(),
                        account.getOrganizationId(), "DEPT-D",
                        capture.getHolderUserId(), capture.getCapturedAmount(),
                        capture.getMerchantName(), capture.getCapturedAt()));

        // 제출
        submitExpenseReportUseCase.submit(
                new SubmitExpenseReportCommand(draft.getReportId(), null, "MEAL", "편의점"));

        // 반려
        Long managerId = 8888L;
        ExpenseReport rejected = rejectExpenseReportUseCase.reject(
                new RejectExpenseReportCommand(draft.getReportId(), managerId, "영수증 미첨부"));

        assertThat(rejected.getStatus()).isEqualTo(ExpenseReportStatus.REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo("영수증 미첨부");

        // 재제출 (REJECTED → SUBMITTED)
        ExpenseReport resubmitted = submitExpenseReportUseCase.submit(
                new SubmitExpenseReportCommand(
                        draft.getReportId(),
                        "https://receipts.example.com/r3.jpg",
                        "MEAL", "편의점 간식"));

        assertThat(resubmitted.getStatus()).isEqualTo(ExpenseReportStatus.SUBMITTED);
        assertThat(resubmitted.getReceiptUrl()).isEqualTo("https://receipts.example.com/r3.jpg");
    }

    @Test
    @DisplayName("부서 예산 소진율 — 승인 금액 / 총 예산")
    void budgetUtilization_afterApproval() {
        CardAccount account = createActiveAccount(100L, "SELLER-5", new BigDecimal("1000000"));
        Card card = issuedCard(account, 204L, new BigDecimal("500000"));

        // 부서 예산 초기화: 2026년 8월, DEPT-E, 총 예산 100만원
        jdbc.update("""
                INSERT INTO opslab.department_budgets
                    (organization_id, department_id, budget_year, budget_month, total_budget, approved_amount)
                VALUES (?, ?, ?, ?, ?, ?)
                """, 100L, "DEPT-E", 2026, 8, new BigDecimal("1000000"), BigDecimal.ZERO);

        // 두 건 승인
        for (int i = 0; i < 2; i++) {
            String authId = authorize(card, new BigDecimal("150000"));
            CardCapture capture = doCapture(authId, new BigDecimal("150000"), "회의실렌탈");

            ExpenseReport draft = createExpenseReportFromCaptureUseCase.createFromCapture(
                    new CreateExpenseReportCommand(
                            capture.getCaptureId(), capture.getAuthorizationId(),
                            capture.getCardId(), capture.getCardAccountId(),
                            account.getOrganizationId(), "DEPT-E",
                            capture.getHolderUserId(), capture.getCapturedAmount(),
                            capture.getMerchantName(), capture.getCapturedAt()));

            submitExpenseReportUseCase.submit(
                    new SubmitExpenseReportCommand(draft.getReportId(),
                            "https://r.example.com/" + i + ".jpg", "MEETING", "회의실"));

            approveExpenseReportUseCase.approve(
                    new ApproveExpenseReportCommand(draft.getReportId(), 9999L));
        }

        // 소진율: 300,000 / 1,000,000 = 30.0%
        QueryBudgetUtilizationUseCase.BudgetUtilization utilization =
                queryBudgetUtilizationUseCase.getUtilization(100L, "DEPT-E", 2026, 8);

        assertThat(utilization.totalBudget()).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(utilization.approvedAmount()).isEqualByComparingTo(new BigDecimal("300000"));
        assertThat(utilization.utilizationPercent()).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    @Test
    @DisplayName("DRAFT 상태에서 직접 승인 시도 → 비즈니스 예외")
    void cannotApproveFromDraft() {
        CardAccount account = createActiveAccount(100L, "SELLER-6", new BigDecimal("500000"));
        Card card = issuedCard(account, 205L, new BigDecimal("200000"));
        String authId = authorize(card, new BigDecimal("10000"));
        CardCapture capture = doCapture(authId, new BigDecimal("10000"), "테스트가맹점");

        ExpenseReport draft = createExpenseReportFromCaptureUseCase.createFromCapture(
                new CreateExpenseReportCommand(
                        capture.getCaptureId(), capture.getAuthorizationId(),
                        capture.getCardId(), capture.getCardAccountId(),
                        account.getOrganizationId(), "DEPT-F",
                        capture.getHolderUserId(), capture.getCapturedAmount(),
                        capture.getMerchantName(), capture.getCapturedAt()));

        // DRAFT 에서 바로 승인 → 예외
        assertThatThrownBy(() ->
                approveExpenseReportUseCase.approve(
                        new ApproveExpenseReportCommand(draft.getReportId(), 9999L)))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Flyway 가 V8 지출보고서 테이블을 만든다")
    void flywayCreatesExpenseTables() {
        Integer expenseCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'opslab' AND table_name = 'expense_reports'
                """, Integer.class);
        assertThat(expenseCount).isEqualTo(1);

        Integer budgetCount = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'opslab' AND table_name = 'department_budgets'
                """, Integer.class);
        assertThat(budgetCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Flyway 마이그레이션이 V2 → V3 → V4 → V5 → V6 → V7 → V8 → V9 순서로 적용된다")
    void flywayAppliesMigrationsInOrder() {
        java.util.List<String> versions = jdbc.queryForList("""
                SELECT version FROM opslab.flyway_schema_history
                 WHERE version IS NOT NULL
                 ORDER BY installed_rank
                """, String.class);
        assertThat(versions).containsExactly("2", "3", "4", "5", "6", "7", "8", "9");
    }
}
