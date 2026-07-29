package github.lms.lemuel.loan.integration;

import github.lms.lemuel.LoanServiceApplication;
import github.lms.lemuel.loan.application.port.in.DisburseSecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.ManageSecuredLoanCollectionUseCase;
import github.lms.lemuel.loan.application.port.in.PrepaySecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.PrepaySecuredLoanUseCase.PrepayResult;
import github.lms.lemuel.loan.application.port.in.RepaySecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase.MortgageCommand;
import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase.PersonalCreditCommand;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.SecuredLoanRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보/개인신용 대출 전 흐름 E2E — 실 Flyway 체인 · 실 DB 제약 · 실 원장.
 *
 * <p>단위 테스트는 페이크 포트로 로직을 검증하지만, <b>DB 제약과 원장 유니크 인덱스는 실 DB 에서만
 * 드러난다</b>. 특히 회차 상환이 반복될 때 {@code uq_loan_ledger_reference_accounts} 부분 인덱스가
 * 실제로 이를 허용하는지는 여기서만 확인된다 — 기존 기업대출이 바로 이 갭으로 상환 경로 전체가
 * 500 나던 전례가 있다.
 */
@SpringBootTest(
        classes = LoanServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class SecuredLoanLifecycleIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lemuel_loan")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Long BORROWER = 4242L;

    @Autowired RequestSecuredLoanUseCase requestUseCase;
    @Autowired DisburseSecuredLoanUseCase disburseUseCase;
    @Autowired RepaySecuredLoanUseCase repayUseCase;
    @Autowired PrepaySecuredLoanUseCase prepayUseCase;
    @Autowired ManageSecuredLoanCollectionUseCase collectionUseCase;
    @Autowired JdbcTemplate jdbc;

    private SecuredLoan requestMortgage(BigDecimal principal, int termMonths) {
        return requestUseCase.requestMortgage(new MortgageCommand(BORROWER, "홍길동", null,
                "서울시 강남구 테헤란로 1", new BigDecimal("500000000"),
                principal, termMonths, RepaymentMethod.EQUAL_PAYMENT));
    }

    private int ledgerCount(long loanId, String refType) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.loan_ledger_entries WHERE ref_id = ? AND ref_type = ?",
                Integer.class, loanId, refType);
        return count == null ? 0 : count;
    }

    // ─── 전 흐름 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("신청→승인→실행→분할상환(다회차)→완제 전 흐름이 실 DB 에서 완주한다")
    void fullLifecycle() {
        SecuredLoan requested = requestMortgage(new BigDecimal("300000000"), 360);
        assertThat(requested.getId()).isNotNull();
        assertThat(requested.getStatus()).isEqualTo(SecuredLoanStatus.REQUESTED);
        assertThat(requested.getCollateral().getStatus()).isEqualTo(CollateralStatus.PLEDGED);

        SecuredLoan approved = disburseUseCase.approve(requested.getId());
        assertThat(approved.getCollateral().getStatus()).isEqualTo(CollateralStatus.ACTIVE);

        SecuredLoan disbursed = disburseUseCase.disburse(requested.getId());
        assertThat(disbursed.getStatus()).isEqualTo(SecuredLoanStatus.DISBURSED);
        assertThat(disbursed.getOutstanding()).isEqualByComparingTo("300000000");
        assertThat(ledgerCount(disbursed.getId(), "SEC_DISBURSE")).isEqualTo(1);

        // 회차 상환 3회 — 같은 (ref_type, ref_id, debit, credit) 4-튜플이 반복된다.
        // 부분 유니크 인덱스가 회차성 전표를 제외하지 않으면 2회차부터 여기서 터진다.
        long loanId = disbursed.getId();
        for (int i = 0; i < 3; i++) {
            repayUseCase.repay(loanId, BORROWER, new BigDecimal("1000000"), new BigDecimal("1075000"));
        }
        assertThat(ledgerCount(loanId, "SEC_REPAYMENT")).isEqualTo(3);
        assertThat(ledgerCount(loanId, "SEC_INTEREST")).isEqualTo(3);

        // 잔여 전액 상환 → 완제 + 담보 말소
        SecuredLoan repaid = repayUseCase.repay(loanId, BORROWER,
                new BigDecimal("297000000"), new BigDecimal("1000"));
        assertThat(repaid.getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
        assertThat(repaid.getOutstanding()).isEqualByComparingTo("0");
        assertThat(repaid.getCollateral().getStatus()).isEqualTo(CollateralStatus.RELEASED);

        // 완제 이벤트가 Outbox 에 적재된다(Kafka 비활성이라 발행은 안 되고 행만 남는다).
        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE aggregate_type = 'Loan' "
                        + "AND aggregate_id = ? AND event_type = 'SecuredLoanRepaid'",
                Integer.class, String.valueOf(loanId));
        assertThat(outboxRows).isEqualTo(1);
    }

    // ─── 원장 차대 균형 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("담보대출이 만든 전 전표는 차변·대변 계정이 서로 다르고 금액이 양수다")
    void ledgerEntriesAreStructurallyBalanced() {
        SecuredLoan loan = requestMortgage(new BigDecimal("100000000"), 120);
        disburseUseCase.approve(loan.getId());
        disburseUseCase.disburse(loan.getId());
        repayUseCase.repay(loan.getId(), BORROWER, new BigDecimal("500000"), new BigDecimal("300000"));

        Integer unbalanced = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.loan_ledger_entries "
                        + "WHERE ref_id = ? AND (debit = credit OR amount <= 0)",
                Integer.class, loan.getId());
        assertThat(unbalanced).isZero();
    }

    // ─── 심사 거절 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LTV 한도 초과 신청은 거절되고 고아 담보 행을 남기지 않는다")
    void overLimitRequestLeavesNoOrphanCollateral() {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM opslab.collaterals", Integer.class);

        // 유효담보가치 5억 × LTV 0.70 = 3.5억 한도
        assertThatThrownBy(() -> requestMortgage(new BigDecimal("350000001"), 360))
                .isInstanceOf(SecuredLoanRejectedException.class);

        Integer after = jdbc.queryForObject("SELECT count(*) FROM opslab.collaterals", Integer.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("CB 등급 미달 개인신용 신청은 422 거절 예외로 막힌다")
    void lowCreditScoreIsRejected() {
        assertThatThrownBy(() -> requestUseCase.requestPersonalCredit(new PersonalCreditCommand(
                BORROWER, "홍길동", null, new BigDecimal("1000000"), 36,
                RepaymentMethod.EQUAL_PAYMENT, 500)))
                .isInstanceOf(SecuredLoanRejectedException.class);
    }

    // ─── 개인신용(무담보) 경로 ─────────────────────────────────────────────────

    @Test
    @DisplayName("무담보 개인신용대출은 담보 없이 실행·상환된다 — collateral_id NULL 제약 통과")
    void personalCreditWithoutCollateral() {
        SecuredLoan loan = requestUseCase.requestPersonalCredit(new PersonalCreditCommand(
                BORROWER, "홍길동", null, new BigDecimal("30000000"), 36,
                RepaymentMethod.EQUAL_PAYMENT, 780));
        assertThat(loan.getCollateral()).isNull();
        assertThat(loan.getCreditGrade()).isEqualTo("B");

        disburseUseCase.approve(loan.getId());
        SecuredLoan disbursed = disburseUseCase.disburse(loan.getId());
        assertThat(disbursed.getStatus()).isEqualTo(SecuredLoanStatus.DISBURSED);

        SecuredLoan repaid = repayUseCase.repay(loan.getId(), BORROWER,
                new BigDecimal("30000000"), new BigDecimal("150000"));
        assertThat(repaid.getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
    }

    // ─── 중도상환 (Phase 2-6) ─────────────────────────────────────────────────

    @Test
    @DisplayName("중도상환 2회(부분→전액)가 수수료 전표와 함께 실 DB 를 완주한다 — SEC_EARLY_FEE N회 허용")
    void prepayPartialThenFull() {
        SecuredLoan loan = requestMortgage(new BigDecimal("300000000"), 360);
        disburseUseCase.approve(loan.getId());
        SecuredLoan disbursed = disburseUseCase.disburse(loan.getId());
        long loanId = disbursed.getId();

        // 실행 시각이 DB 에 스냅샷된다 — 수수료 약정기간의 기산점.
        Integer withDisbursedAt = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.secured_loans WHERE id = ? AND disbursed_at IS NOT NULL",
                Integer.class, loanId);
        assertThat(withDisbursedAt).isEqualTo(1);

        // 실행 당일 중도상환 — 잔존=약정이라 수수료는 정확히 1.2% 정률이다(날짜 무관 결정적).
        PrepayResult partial = prepayUseCase.prepay(loanId, BORROWER, new BigDecimal("100000000"));
        assertThat(partial.prepaidAmount()).isEqualByComparingTo("100000000");
        assertThat(partial.fee()).isEqualByComparingTo("1200000.00");
        assertThat(partial.loan().getStatus()).isEqualTo(SecuredLoanStatus.DISBURSED);

        // 잔여 전액 중도상환 → 완제 + 담보 말소. 수수료 전표가 2번째로 쌓인다 —
        // uq_loan_ledger_reference_accounts 가 SEC_EARLY_FEE 를 제외하지 않으면 여기서 터진다.
        PrepayResult full = prepayUseCase.prepay(loanId, BORROWER, new BigDecimal("999999999"));
        assertThat(full.prepaidAmount()).isEqualByComparingTo("200000000");
        assertThat(full.fee()).isEqualByComparingTo("2400000.00");
        assertThat(full.loan().getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
        assertThat(full.loan().getCollateral().getStatus()).isEqualTo(CollateralStatus.RELEASED);

        assertThat(ledgerCount(loanId, "SEC_REPAYMENT")).isEqualTo(2);
        assertThat(ledgerCount(loanId, "SEC_EARLY_FEE")).isEqualTo(2);

        // 완제 이벤트는 회차 상환과 같은 계약으로 Outbox 에 적재된다.
        Integer outboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM opslab.outbox_events WHERE aggregate_type = 'Loan' "
                        + "AND aggregate_id = ? AND event_type = 'SecuredLoanRepaid'",
                Integer.class, String.valueOf(loanId));
        assertThat(outboxRows).isEqualTo(1);
    }

    @Test
    @DisplayName("연체된 대출의 중도상환은 상태머신이 거부한다 — 연체 납입은 회차 상환 경로")
    void prepayRejectedWhenOverdue() {
        SecuredLoan loan = requestMortgage(new BigDecimal("100000000"), 120);
        disburseUseCase.approve(loan.getId());
        disburseUseCase.disburse(loan.getId());
        collectionUseCase.markOverdue(loan.getId());

        assertThatThrownBy(() -> prepayUseCase.prepay(loan.getId(), BORROWER, new BigDecimal("1000")))
                .isInstanceOf(InvalidLoanStateException.class);
    }

    // ─── 연체·기한이익상실 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("연체→기한이익상실→전액회수 경로가 실 DB status CHECK 를 통과한다")
    void overdueThenAccelerateThenRepaid() {
        SecuredLoan loan = requestMortgage(new BigDecimal("200000000"), 240);
        disburseUseCase.approve(loan.getId());
        disburseUseCase.disburse(loan.getId());

        assertThat(collectionUseCase.markOverdue(loan.getId()).getStatus())
                .isEqualTo(SecuredLoanStatus.OVERDUE);
        assertThat(collectionUseCase.accelerate(loan.getId()).getStatus())
                .isEqualTo(SecuredLoanStatus.DEFAULTED);

        SecuredLoan repaid = repayUseCase.repay(loan.getId(), BORROWER,
                new BigDecimal("200000000"), BigDecimal.ZERO);
        assertThat(repaid.getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
    }

    // ─── 소유권(IDOR) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("타인 대출 상환은 서비스 계층에서 403 으로 차단된다")
    void otherUserCannotRepay() {
        SecuredLoan loan = requestMortgage(new BigDecimal("100000000"), 120);
        disburseUseCase.approve(loan.getId());
        disburseUseCase.disburse(loan.getId());

        assertThatThrownBy(() -> repayUseCase.repay(loan.getId(), 9999L,
                new BigDecimal("1000"), BigDecimal.ZERO))
                .isInstanceOf(AccessDeniedException.class);
    }
}
