package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.PrepaySecuredLoanUseCase.PrepayResult;
import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase.MortgageCommand;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.BaseRatePort;
import github.lms.lemuel.loan.application.port.out.CollateralValuationPort;
import github.lms.lemuel.loan.application.port.out.LoadCollateralDocumentPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.PublishSecuredLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralDocument;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.LedgerAccount;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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
 * 중도상환 응용 서비스 — 수수료 기표·완제 연쇄(담보 말소 + 이벤트)·소유권 대조.
 *
 * <p>수수료는 정책의 순수 함수지만, <b>어느 전표로 얼마가 남는지</b>는 이 서비스의 책임이라
 * 전표 계정쌍·금액까지 관찰한다. 특히 면제(경과 3년) 경로는 "수수료 전표가 없음"을 확인해야
 * 0원 전표(원장 불변식 위반) 시도를 잡는다.
 */
class PrepaySecuredLoanServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);
    private static final BigDecimal BASE_RATE = new BigDecimal("3.5");
    private static final BigDecimal LTV = new BigDecimal("0.70");
    private static final long BORROWER = 42L;

    private FakeSecuredLoanStore store;
    private RecordingLedgerPort ledger;
    private RecordingEventPort events;
    private CountingMetricsPort metrics;
    private RequestSecuredLoanService requestService;
    private DisburseSecuredLoanService disburseService;
    private SecuredLoanCollectionService collectionService;
    private PrepaySecuredLoanService prepayService;

    /** 담보서류 미첨부 — 게이트가 기존 경로를 바꾸지 않음을 전제로 한다 (ADR 0036 점진 도입). */
    private static final LoadCollateralDocumentPort NO_DOCUMENTS = new LoadCollateralDocumentPort() {
        @Override public Optional<CollateralDocument> findById(Long id) { return Optional.empty(); }
        @Override public Optional<CollateralDocument> findByLoanIdAndFileHash(Long loanId, String fileHash) {
            return Optional.empty();
        }
        @Override public Optional<CollateralDocument> findLatestByLoanId(Long loanId) { return Optional.empty(); }
        @Override public java.util.List<CollateralDocument> findByStatus(
                github.lms.lemuel.loan.domain.CollateralDocumentStatus status, int limit) {
            return java.util.List.of();
        }
    };

    @BeforeEach
    void setUp() {
        store = new FakeSecuredLoanStore();
        ledger = new RecordingLedgerPort();
        events = new RecordingEventPort();
        metrics = new CountingMetricsPort();
        CollateralValuationPort valuation = claim -> claim.declaredValue();
        BaseRatePort baseRate = () -> BASE_RATE;

        requestService = new RequestSecuredLoanService(store, valuation, baseRate, metrics, LTV, FIXED_CLOCK);
        disburseService = new DisburseSecuredLoanService(store, store, ledger, events, metrics,
                NO_DOCUMENTS, FIXED_CLOCK);
        collectionService = new SecuredLoanCollectionService(store, store);
        prepayService = new PrepaySecuredLoanService(store, store, ledger, events, metrics, FIXED_CLOCK);
    }

    /** 실행까지 마친 주택담보대출(3억, 360개월) — 실행 시각은 FIXED_CLOCK(잔존=약정). */
    private SecuredLoan disbursedMortgage() {
        SecuredLoan requested = requestService.requestMortgage(new MortgageCommand(
                BORROWER, "홍길동", null, "서울시 강남구 테헤란로 1",
                new BigDecimal("500000000"), new BigDecimal("300000000"), 360,
                RepaymentMethod.EQUAL_PAYMENT));
        disburseService.approve(requested.getId());
        SecuredLoan disbursed = disburseService.disburse(requested.getId());
        ledger.entries.clear();   // 실행 전표는 이 테스트의 관찰 대상이 아니다
        return disbursed;
    }

    // ─── 수수료 기표 ──────────────────────────────────────────────────────────

    @Test
    void 중도상환은_원금전표와_수수료전표를_남긴다() {
        SecuredLoan loan = disbursedMortgage();

        PrepayResult result = prepayService.prepay(loan.getId(), BORROWER, new BigDecimal("100000000"));

        // 실행 직후 상환이라 잔존=약정 → 수수료 = 1억 × 1.2% × 1 = 1,200,000
        assertThat(result.prepaidAmount()).isEqualByComparingTo("100000000");
        assertThat(result.fee()).isEqualByComparingTo("1200000.00");
        assertThat(result.loan().getOutstanding()).isEqualByComparingTo("200000000");

        assertThat(ledger.entries).hasSize(2);
        LoanLedgerEntry principalEntry = ledger.entries.get(0);
        assertThat(principalEntry.getRefType()).isEqualTo("SEC_REPAYMENT");
        assertThat(principalEntry.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(principalEntry.getCredit()).isEqualTo(LedgerAccount.LOAN_RECEIVABLE);
        assertThat(principalEntry.getAmount()).isEqualByComparingTo("100000000");

        LoanLedgerEntry feeEntry = ledger.entries.get(1);
        assertThat(feeEntry.getRefType()).isEqualTo("SEC_EARLY_FEE");
        assertThat(feeEntry.getDebit()).isEqualTo(LedgerAccount.CASH);
        assertThat(feeEntry.getCredit()).isEqualTo(LedgerAccount.FEE_INCOME);
        assertThat(feeEntry.getAmount()).isEqualByComparingTo("1200000.00");
    }

    @Test
    void 경과_3년_이후_중도상환은_수수료전표를_남기지_않는다() {
        // 4년 전 실행된 대출 — 면제 구간. 0원 수수료 전표를 만들면 원장 불변식(양수 금액) 위반이므로
        // "전표가 아예 없음"까지 확인한다.
        SecuredLoan legacy = store.save(SecuredLoan.reconstitute(null,
                Borrower.individual(BORROWER, "홍길동"), LoanProductType.PERSONAL_CREDIT, null,
                new BigDecimal("10000000.00"), 120, new BigDecimal("6.00"),
                RepaymentMethod.EQUAL_PAYMENT, 780, "B", new BigDecimal("5000000.00"),
                SecuredLoanStatus.DISBURSED, NOW.minusYears(4), NOW.minusYears(4)));

        PrepayResult result = prepayService.prepay(legacy.getId(), BORROWER, new BigDecimal("1000000"));

        assertThat(result.fee()).isEqualByComparingTo("0");
        assertThat(ledger.entries).hasSize(1);
        assertThat(ledger.entries.get(0).getRefType()).isEqualTo("SEC_REPAYMENT");
    }

    // ─── 완제 연쇄 ────────────────────────────────────────────────────────────

    @Test
    void 전액_중도상환하면_완제되고_담보말소와_이벤트가_따른다() {
        SecuredLoan loan = disbursedMortgage();

        PrepayResult result = prepayService.prepay(loan.getId(), BORROWER, new BigDecimal("300000000"));

        assertThat(result.loan().getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
        assertThat(result.loan().getCollateral().getStatus()).isEqualTo(CollateralStatus.RELEASED);
        assertThat(events.repaid).hasSize(1);
        // 완제 이벤트에는 이 중도상환에 부과된 수수료 실액이 실린다 — 3억 × 1.2% × 1 = 3,600,000
        assertThat(events.repaidFees).hasSize(1);
        assertThat(events.repaidFees.get(0)).isEqualByComparingTo("3600000.00");
    }

    @Test
    void 부분_중도상환은_완제이벤트를_발행하지_않는다() {
        SecuredLoan loan = disbursedMortgage();

        prepayService.prepay(loan.getId(), BORROWER, new BigDecimal("100000000"));

        assertThat(events.repaid).isEmpty();
    }

    // ─── 가드 ────────────────────────────────────────────────────────────────

    @Test
    void 타인_대출을_중도상환하려_하면_403으로_차단한다() {
        SecuredLoan loan = disbursedMortgage();

        assertThatThrownBy(() -> prepayService.prepay(loan.getId(), 999L, new BigDecimal("1000")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 연체된_대출은_중도상환할_수_없다() {
        SecuredLoan loan = disbursedMortgage();
        collectionService.markOverdue(loan.getId());

        assertThatThrownBy(() -> prepayService.prepay(loan.getId(), BORROWER, new BigDecimal("1000")))
                .isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 없는_대출은_404_예외() {
        assertThatThrownBy(() -> prepayService.prepay(9999L, BORROWER, new BigDecimal("1000")))
                .isInstanceOf(SecuredLoanNotFoundException.class);
    }

    @Test
    void 중도상환이_상환지표로_계상된다() {
        SecuredLoan loan = disbursedMortgage();

        prepayService.prepay(loan.getId(), BORROWER, new BigDecimal("100000000"));

        assertThat(metrics.securedRepaid).isEqualTo(1);
    }

    // ─── 페이크 (SecuredLoanServiceTest 와 동형) ────────────────────────────────

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
                    loan.getOutstanding(), loan.getStatus(), loan.getCreatedAt(), loan.getDisbursedAt());
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
        private final List<BigDecimal> repaidFees = new ArrayList<>();
        private final List<BigDecimal> principalRepayments = new ArrayList<>();
        private final List<String> principalRepaymentReasons = new ArrayList<>();

        @Override
        public void publishDisbursed(SecuredLoan loan) {
            disbursed.add(loan);
        }

        @Override
        public void publishRepaid(SecuredLoan loan, BigDecimal totalInterestPaid, BigDecimal prepaymentFee) {
            repaid.add(loan);
            repaidFees.add(prepaymentFee);
        }

        /** #183 — 원금 감소 건별 발행. 금액이 실제 차감액인지 검증하려고 기록해 둔다. */
        @Override
        public void publishPrincipalRepaid(SecuredLoan loan, BigDecimal principalRepaid, String reason) {
            principalRepayments.add(principalRepaid);
            principalRepaymentReasons.add(reason);
        }
    }

    private static final class CountingMetricsPort implements LoanMetricsPort {
        private int securedRepaid;

        @Override public void securedRepaid(BigDecimal deductedAmount) { securedRepaid++; }

        @Override public void securedRequested() { }
        @Override public void securedRejected() { }
        @Override public void securedDisbursed() { }
        @Override public void advanceRequested() { }
        @Override public void advanceDisbursed() { }
        @Override public void advanceRejected() { }
        @Override public void corporateRequested() { }
        @Override public void corporateRejected() { }
        @Override public void corporateDisbursed() { }
        @Override public void repaymentApplied(BigDecimal deductedAmount) { }
        @Override public void corporateRepaid(BigDecimal deductedAmount) { }
        @Override public void advanceOverdue() { }
        @Override public void advanceWrittenOff(BigDecimal loss) { }
    }
}
