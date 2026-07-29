package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase.MortgageCommand;
import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase.PersonalCreditCommand;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.BaseRatePort;
import github.lms.lemuel.loan.application.port.out.CollateralValuationPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.PublishSecuredLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.BorrowerType;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.CollateralType;
import github.lms.lemuel.loan.domain.LedgerAccount;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import github.lms.lemuel.loan.domain.exception.SecuredLoanRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보/개인신용 대출 응용 서비스 — 심사·실행·상환·연체 흐름.
 *
 * <p>포트를 인메모리 페이크로 대체해 <b>원장 전표와 이벤트 발행까지 관찰</b>한다. 돈이 움직이는
 * 경로라 "상태가 바뀌었다"만으로는 부족하고, 전표가 정확히 몇 건 어떤 계정으로 쌓였는지를 본다.
 */
class SecuredLoanServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final BigDecimal BASE_RATE = new BigDecimal("3.5");
    private static final BigDecimal LTV = new BigDecimal("0.70");

    private FakeSecuredLoanStore store;
    private RecordingLedgerPort ledger;
    private RecordingEventPort events;
    private RequestSecuredLoanService requestService;
    private DisburseSecuredLoanService disburseService;
    private RepaySecuredLoanService repayService;
    private SecuredLoanCollectionService collectionService;

    @BeforeEach
    void setUp() {
        store = new FakeSecuredLoanStore();
        ledger = new RecordingLedgerPort();
        events = new RecordingEventPort();
        CollateralValuationPort valuation = (type, description, declared) -> declared;
        BaseRatePort baseRate = () -> BASE_RATE;

        requestService = new RequestSecuredLoanService(store, valuation, baseRate, LTV, FIXED_CLOCK);
        disburseService = new DisburseSecuredLoanService(store, store, ledger, events);
        repayService = new RepaySecuredLoanService(store, store, ledger, events);
        collectionService = new SecuredLoanCollectionService(store, store);
    }

    private MortgageCommand mortgageCommand(BigDecimal principal) {
        return new MortgageCommand(42L, "홍길동", null, "서울시 강남구 테헤란로 1",
                new BigDecimal("500000000"), principal, 360, RepaymentMethod.EQUAL_PAYMENT);
    }

    private PersonalCreditCommand personalCommand(BigDecimal principal, int cbScore) {
        return new PersonalCreditCommand(42L, "홍길동", null, principal, 36,
                RepaymentMethod.EQUAL_PAYMENT, cbScore);
    }

    // ─── 신청: 주택담보 ────────────────────────────────────────────────────────

    @Test
    void 주택담보_신청은_담보를_설정하고_금리를_확정한다() {
        SecuredLoan loan = requestService.requestMortgage(mortgageCommand(new BigDecimal("300000000")));

        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.REQUESTED);
        assertThat(loan.getProductType()).isEqualTo(LoanProductType.MORTGAGE);
        assertThat(loan.getCollateral().getStatus()).isEqualTo(CollateralStatus.PLEDGED);
        assertThat(loan.getCollateral().getAppraisedValue()).isEqualByComparingTo("500000000");
        // 기준금리 3.5 + 담보형 고정 가산 0.8
        assertThat(loan.getAnnualRatePercent()).isEqualByComparingTo("4.30");
        assertThat(loan.getBorrower().type()).isEqualTo(BorrowerType.INDIVIDUAL);
    }

    @Test
    void 주택담보_신청액이_LTV한도를_넘으면_거절한다() {
        // 유효담보가치 5억 × LTV 0.70 = 3.5억 한도
        assertThatThrownBy(() -> requestService.requestMortgage(mortgageCommand(new BigDecimal("350000001"))))
                .isInstanceOf(SecuredLoanRejectedException.class);
    }

    @Test
    void 한도_경계값은_통과한다() {
        assertThat(requestService.requestMortgage(mortgageCommand(new BigDecimal("350000000"))))
                .isNotNull();
    }

    @Test
    void 사업자번호가_있으면_법인차주로_해석한다() {
        MortgageCommand command = new MortgageCommand(7L, "레무엘커머스", "123-45-67890",
                "서울시 강남구", new BigDecimal("500000000"), new BigDecimal("100000000"),
                360, RepaymentMethod.EQUAL_PAYMENT);

        SecuredLoan loan = requestService.requestMortgage(command);

        assertThat(loan.getBorrower().type()).isEqualTo(BorrowerType.CORPORATE);
        assertThat(loan.getBorrower().registrationNo()).isEqualTo("1234567890");
    }

    // ─── 신청: 개인신용 ────────────────────────────────────────────────────────

    @Test
    void 개인신용_신청은_CB점수로_등급과_금리를_확정한다() {
        SecuredLoan loan = requestService.requestPersonalCredit(
                personalCommand(new BigDecimal("30000000"), 780));

        assertThat(loan.getProductType()).isEqualTo(LoanProductType.PERSONAL_CREDIT);
        assertThat(loan.getCollateral()).isNull();
        assertThat(loan.getCreditScore()).isEqualTo(780);
        assertThat(loan.getCreditGrade()).isEqualTo("B");   // 750~849
        assertThat(loan.getAnnualRatePercent()).isEqualByComparingTo("6.00");  // 3.5 + 2.5
    }

    @Test
    void 개인신용_E등급은_대출_불가로_거절한다() {
        assertThatThrownBy(() -> requestService.requestPersonalCredit(
                personalCommand(new BigDecimal("1000000"), 500)))
                .isInstanceOf(SecuredLoanRejectedException.class);
    }

    @Test
    void 개인신용_등급별_정액한도를_넘으면_거절한다() {
        // B등급 한도 5천만
        assertThatThrownBy(() -> requestService.requestPersonalCredit(
                personalCommand(new BigDecimal("50000001"), 780)))
                .isInstanceOf(SecuredLoanRejectedException.class);
    }

    // ─── 승인 · 실행 ──────────────────────────────────────────────────────────

    @Test
    void 승인하면_담보가_유효화된다() {
        SecuredLoan requested = requestService.requestMortgage(mortgageCommand(new BigDecimal("300000000")));

        SecuredLoan approved = disburseService.approve(requested.getId());

        assertThat(approved.getStatus()).isEqualTo(SecuredLoanStatus.APPROVED);
        assertThat(approved.getCollateral().getStatus()).isEqualTo(CollateralStatus.ACTIVE);
    }

    @Test
    void 거절하면_담보가_말소된다() {
        SecuredLoan requested = requestService.requestMortgage(mortgageCommand(new BigDecimal("300000000")));

        SecuredLoan rejected = disburseService.reject(requested.getId());

        assertThat(rejected.getStatus()).isEqualTo(SecuredLoanStatus.REJECTED);
        assertThat(rejected.getCollateral().getStatus()).isEqualTo(CollateralStatus.RELEASED);
    }

    @Test
    void 실행하면_전표1건과_이벤트가_남는다() {
        SecuredLoan requested = requestService.requestMortgage(mortgageCommand(new BigDecimal("300000000")));
        disburseService.approve(requested.getId());

        SecuredLoan disbursed = disburseService.disburse(requested.getId());

        assertThat(disbursed.getStatus()).isEqualTo(SecuredLoanStatus.DISBURSED);
        assertThat(disbursed.getOutstanding()).isEqualByComparingTo("300000000");

        assertThat(ledger.entries).hasSize(1);
        LoanLedgerEntry entry = ledger.entries.get(0);
        assertThat(entry.getRefType()).isEqualTo("SEC_DISBURSE");
        assertThat(entry.getDebit()).isEqualTo(LedgerAccount.LOAN_RECEIVABLE);
        assertThat(entry.getCredit()).isEqualTo(LedgerAccount.CASH);
        assertThat(entry.getAmount()).isEqualByComparingTo("300000000");

        assertThat(events.disbursed).hasSize(1);
    }

    @Test
    void 없는_대출_실행은_404_예외() {
        assertThatThrownBy(() -> disburseService.disburse(9999L))
                .isInstanceOf(SecuredLoanNotFoundException.class);
    }

    // ─── 상환 ────────────────────────────────────────────────────────────────

    private SecuredLoan disbursedMortgage() {
        SecuredLoan requested = requestService.requestMortgage(mortgageCommand(new BigDecimal("300000000")));
        disburseService.approve(requested.getId());
        return disburseService.disburse(requested.getId());
    }

    @Test
    void 회차상환은_원금과_이자를_분리해_기표한다() {
        SecuredLoan loan = disbursedMortgage();
        ledger.entries.clear();

        SecuredLoan repaid = repayService.repay(loan.getId(), 42L,
                new BigDecimal("500000"), new BigDecimal("1075000"));

        assertThat(repaid.getOutstanding()).isEqualByComparingTo("299500000");
        assertThat(ledger.entries).hasSize(2);

        LoanLedgerEntry principalEntry = ledger.entries.get(0);
        assertThat(principalEntry.getRefType()).isEqualTo("SEC_REPAYMENT");
        assertThat(principalEntry.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(principalEntry.getCredit()).isEqualTo(LedgerAccount.LOAN_RECEIVABLE);

        LoanLedgerEntry interestEntry = ledger.entries.get(1);
        assertThat(interestEntry.getRefType()).isEqualTo("SEC_INTEREST");
        assertThat(interestEntry.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(interestEntry.getCredit()).isEqualTo(LedgerAccount.FEE_INCOME);
        assertThat(interestEntry.getAmount()).isEqualByComparingTo("1075000");
    }

    @Test
    void 이자가_0인_회차는_이자전표를_남기지_않는다() {
        SecuredLoan loan = disbursedMortgage();
        ledger.entries.clear();

        repayService.repay(loan.getId(), 42L, new BigDecimal("500000"), BigDecimal.ZERO);

        assertThat(ledger.entries).hasSize(1);
        assertThat(ledger.entries.get(0).getRefType()).isEqualTo("SEC_REPAYMENT");
    }

    @Test
    void 완제되면_담보가_말소되고_완제이벤트가_발행된다() {
        SecuredLoan loan = disbursedMortgage();

        SecuredLoan repaid = repayService.repay(loan.getId(), 42L,
                new BigDecimal("300000000"), new BigDecimal("1000"));

        assertThat(repaid.getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
        assertThat(repaid.getCollateral().getStatus()).isEqualTo(CollateralStatus.RELEASED);
        assertThat(events.repaid).hasSize(1);
    }

    @Test
    void 완제_전에는_완제이벤트를_발행하지_않는다() {
        SecuredLoan loan = disbursedMortgage();

        repayService.repay(loan.getId(), 42L, new BigDecimal("1000000"), BigDecimal.ZERO);

        assertThat(events.repaid).isEmpty();
    }

    @Test
    void 타인_대출을_상환하려_하면_403으로_차단한다() {
        SecuredLoan loan = disbursedMortgage();

        // IDOR 가드레일: 소유권 불일치는 403(AccessDeniedException) — 기존 LoanController·
        // CorporateLoanController 와 같은 관례. 서비스에서도 막아 웹 우회 경로를 닫는다.
        assertThatThrownBy(() -> repayService.repay(loan.getId(), 999L,
                new BigDecimal("1000"), BigDecimal.ZERO))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 이자가_음수면_거부한다() {
        SecuredLoan loan = disbursedMortgage();

        assertThatThrownBy(() -> repayService.repay(loan.getId(), 42L,
                new BigDecimal("1000"), new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 연체 · 기한이익상실 ───────────────────────────────────────────────────

    @Test
    void 연체_진입_후_기한이익상실까지_전이한다() {
        SecuredLoan loan = disbursedMortgage();

        assertThat(collectionService.markOverdue(loan.getId()).getStatus())
                .isEqualTo(SecuredLoanStatus.OVERDUE);
        assertThat(collectionService.accelerate(loan.getId()).getStatus())
                .isEqualTo(SecuredLoanStatus.DEFAULTED);
    }

    @Test
    void 기한이익상실된_대출도_전액상환하면_완제된다() {
        SecuredLoan loan = disbursedMortgage();
        collectionService.markOverdue(loan.getId());
        collectionService.accelerate(loan.getId());

        SecuredLoan repaid = repayService.repay(loan.getId(), 42L,
                new BigDecimal("300000000"), BigDecimal.ZERO);

        assertThat(repaid.getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
    }

    // ─── 페이크 ──────────────────────────────────────────────────────────────

    /** 대출·담보를 인메모리에 보관하는 페이크. id 발번과 담보 연결까지 실제 어댑터처럼 흉내낸다. */
    private static final class FakeSecuredLoanStore implements LoadSecuredLoanPort, SaveSecuredLoanPort {
        private final Map<Long, SecuredLoan> loans = new HashMap<>();
        private final Map<Long, Collateral> collaterals = new HashMap<>();
        private final AtomicLong loanSeq = new AtomicLong();
        private final AtomicLong collateralSeq = new AtomicLong();

        @Override
        public Optional<SecuredLoan> findById(Long loanId) {
            return Optional.ofNullable(loans.get(loanId));
        }

        @Override
        public Optional<SecuredLoan> findByIdForUpdate(Long loanId) {
            return findById(loanId);
        }

        @Override
        public List<SecuredLoan> findByBorrower(Long borrowerUserId, int limit) {
            return loans.values().stream()
                    .filter(l -> l.getBorrower().userId().equals(borrowerUserId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<SecuredLoan> findRepayable() {
            return loans.values().stream()
                    .filter(l -> l.getStatus() == SecuredLoanStatus.DISBURSED
                            || l.getStatus() == SecuredLoanStatus.OVERDUE)
                    .toList();
        }

        @Override
        public SecuredLoan save(SecuredLoan loan) {
            Long id = loan.getId() == null ? loanSeq.incrementAndGet() : loan.getId();
            Collateral collateral = loan.getCollateral() == null
                    ? null
                    : collaterals.get(loan.getCollateral().getId());
            SecuredLoan stored = SecuredLoan.reconstitute(id, loan.getBorrower(), loan.getProductType(),
                    collateral, loan.getPrincipal(), loan.getTermMonths(), loan.getAnnualRatePercent(),
                    loan.getRepaymentMethod(), loan.getCreditScore(), loan.getCreditGrade(),
                    loan.getOutstanding(), loan.getStatus(), loan.getCreatedAt());
            loans.put(id, stored);
            return stored;
        }

        @Override
        public Collateral saveCollateral(Collateral collateral) {
            Long id = collateral.getId() == null ? collateralSeq.incrementAndGet() : collateral.getId();
            Collateral stored = Collateral.reconstitute(id, collateral.getType(),
                    collateral.getDescription(), collateral.getAppraisedValue(),
                    collateral.getAppraisedAt(), collateral.getStatus());
            collaterals.put(id, stored);
            return stored;
        }
    }

    private static final class RecordingLedgerPort implements AppendLedgerPort {
        private final List<LoanLedgerEntry> entries = new ArrayList<>();

        @Override
        public void append(LoanLedgerEntry entry) {
            entries.add(entry);
        }
    }

    private static final class RecordingEventPort implements PublishSecuredLoanEventPort {
        private final List<SecuredLoan> disbursed = new ArrayList<>();
        private final List<SecuredLoan> repaid = new ArrayList<>();

        @Override
        public void publishDisbursed(SecuredLoan loan) {
            disbursed.add(loan);
        }

        @Override
        public void publishRepaid(SecuredLoan loan, BigDecimal totalInterestPaid) {
            repaid.add(loan);
        }
    }

    /** 담보 유형이 부동산 하나뿐임을 서비스가 전제하지 않는지 확인하는 최소 가드. */
    @Test
    void 담보유형은_부동산이다() {
        SecuredLoan loan = requestService.requestMortgage(mortgageCommand(new BigDecimal("100000000")));
        assertThat(loan.getCollateral().getType()).isEqualTo(CollateralType.REAL_ESTATE);
    }
}
