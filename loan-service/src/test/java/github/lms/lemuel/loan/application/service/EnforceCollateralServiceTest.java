package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.EnforceCollateralUseCase.EnforcementResult;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.CollateralType;
import github.lms.lemuel.loan.domain.LedgerAccount;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * 담보 실행 — 처분 · 대위변제 · 상각.
 *
 * <p>회수액과 손실이 <b>어느 계정으로 흐르는지</b>까지 본다. 금액만 맞고 계정이 틀리면 시산표는 맞아도
 * 손익이 왜곡되므로, 전표 건수와 계정 쌍을 함께 검증한다.
 */
class EnforceCollateralServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final Long BORROWER = 42L;

    private FakeStore store;
    private RecordingLedger ledger;
    private EnforceCollateralService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        ledger = new RecordingLedger();
        service = new EnforceCollateralService(store, store, ledger,
                new BigDecimal("3.5"), new BigDecimal("0.70"), CLOCK);
    }

    /** 잔액 1억 · DEFAULTED 상태의 담보대출. */
    private SecuredLoan defaultedLoan(CollateralType type) {
        Collateral collateral = store.saveCollateral(Collateral.pledge(type, "담보물",
                new BigDecimal("200000000"), LocalDateTime.now(CLOCK)));
        return store.save(SecuredLoan.reconstitute(null, Borrower.individual(BORROWER, "홍길동"),
                LoanProductType.MORTGAGE, collateral, new BigDecimal("100000000.00"), 120,
                new BigDecimal("4.30"), RepaymentMethod.EQUAL_PAYMENT, null, null,
                new BigDecimal("100000000.00"), SecuredLoanStatus.DEFAULTED, LocalDateTime.now(CLOCK)));
    }

    private List<LoanLedgerEntry> entriesOf(String refType) {
        return ledger.entries.stream().filter(e -> e.getRefType().equals(refType)).toList();
    }

    // ─── 처분: 부족 회수 ──────────────────────────────────────────────────────

    @Test
    void 처분대금이_채권에_미달하면_부족분을_상각한다() {
        SecuredLoan loan = defaultedLoan(CollateralType.REAL_ESTATE);

        EnforcementResult result = service.dispose(loan.getId(), new BigDecimal("80000000"));

        assertThat(result.recovered()).isEqualByComparingTo("80000000");
        assertThat(result.writtenOff()).isEqualByComparingTo("20000000");
        assertThat(result.surplus()).isEqualByComparingTo("0");
        assertThat(result.finalStatus()).isEqualTo(SecuredLoanStatus.WRITTEN_OFF.name());

        assertThat(entriesOf("SEC_DISPOSAL")).hasSize(1);
        assertThat(entriesOf("SEC_DISPOSAL").get(0).getAmount()).isEqualByComparingTo("80000000");
        // 부족분은 처분손실로 — 대손충당금(SEC_BAD_DEBT)이 아니다. 담보를 실제로 처분한 결과라
        // 손익계산서상 성격이 다르고, 둘 다 기표하면 손실이 이중 인식된다.
        assertThat(entriesOf("SEC_DISPOSAL_LOSS")).hasSize(1);
        assertThat(entriesOf("SEC_DISPOSAL_LOSS").get(0).getAmount()).isEqualByComparingTo("20000000");
        assertThat(entriesOf("SEC_DISPOSAL_LOSS").get(0).getDebit())
                .isEqualTo(LedgerAccount.COLLATERAL_DISPOSAL_LOSS);
        assertThat(entriesOf("SEC_BAD_DEBT")).isEmpty();
    }

    // ─── 처분: 전액·초과 회수 ─────────────────────────────────────────────────

    @Test
    void 처분대금이_채권과_같으면_완제되고_손실이_없다() {
        SecuredLoan loan = defaultedLoan(CollateralType.REAL_ESTATE);

        EnforcementResult result = service.dispose(loan.getId(), new BigDecimal("100000000"));

        assertThat(result.recovered()).isEqualByComparingTo("100000000");
        assertThat(result.writtenOff()).isEqualByComparingTo("0");
        assertThat(result.finalStatus()).isEqualTo(SecuredLoanStatus.REPAID.name());
        assertThat(entriesOf("SEC_DISPOSAL_LOSS")).isEmpty();
        assertThat(entriesOf("SEC_DISPOSAL_GAIN")).isEmpty();
    }

    @Test
    void 처분대금이_채권을_넘으면_초과분은_처분이익이다() {
        SecuredLoan loan = defaultedLoan(CollateralType.REAL_ESTATE);

        EnforcementResult result = service.dispose(loan.getId(), new BigDecimal("130000000"));

        assertThat(result.recovered()).isEqualByComparingTo("100000000");
        assertThat(result.surplus()).isEqualByComparingTo("30000000");
        assertThat(result.finalStatus()).isEqualTo(SecuredLoanStatus.REPAID.name());
        assertThat(entriesOf("SEC_DISPOSAL_GAIN")).hasSize(1);
        assertThat(entriesOf("SEC_DISPOSAL_GAIN").get(0).getCredit())
                .isEqualTo(LedgerAccount.COLLATERAL_DISPOSAL_GAIN);
    }

    @Test
    void 처분후_담보는_말소된다() {
        SecuredLoan loan = defaultedLoan(CollateralType.REAL_ESTATE);
        service.dispose(loan.getId(), new BigDecimal("100000000"));

        assertThat(store.collaterals.get(loan.getCollateral().getId()).getStatus())
                .isEqualTo(CollateralStatus.RELEASED);
    }

    // ─── 대위변제 ─────────────────────────────────────────────────────────────

    @Test
    void 대위변제는_보증비율만큼_회수하고_미보증분을_상각한다() {
        SecuredLoan loan = defaultedLoan(CollateralType.GUARANTEE);

        EnforcementResult result = service.subrogate(loan.getId());

        // 1억 × 85% = 8500만 회수, 미보증 1500만 상각
        assertThat(result.recovered()).isEqualByComparingTo("85000000");
        assertThat(result.writtenOff()).isEqualByComparingTo("15000000");
        assertThat(result.finalStatus()).isEqualTo(SecuredLoanStatus.WRITTEN_OFF.name());

        assertThat(entriesOf("SEC_SUBROGATION")).hasSize(1);
        // 대위변제 미보증분은 처분이 아니라 대손이다 — 담보물을 처분한 게 아니므로
        // 처분손실 계정이 아니라 대손비용/충당금으로 간다.
        assertThat(entriesOf("SEC_BAD_DEBT")).hasSize(1);
        assertThat(entriesOf("SEC_BAD_DEBT").get(0).getDebit()).isEqualTo(LedgerAccount.BAD_DEBT_EXPENSE);
        assertThat(entriesOf("SEC_DISPOSAL_LOSS")).isEmpty();
    }

    // ─── 경로 가드 ────────────────────────────────────────────────────────────

    @Test
    void 보증부_담보는_처분할_수_없다() {
        SecuredLoan loan = defaultedLoan(CollateralType.GUARANTEE);

        assertThatThrownBy(() -> service.dispose(loan.getId(), new BigDecimal("100000000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 보증부가_아닌_담보는_대위변제_대상이_아니다() {
        SecuredLoan loan = defaultedLoan(CollateralType.REAL_ESTATE);

        assertThatThrownBy(() -> service.subrogate(loan.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 기한이익상실_전에는_담보를_실행할_수_없다() {
        Collateral collateral = store.saveCollateral(Collateral.pledge(CollateralType.REAL_ESTATE,
                "담보물", new BigDecimal("200000000"), LocalDateTime.now(CLOCK)));
        SecuredLoan loan = store.save(SecuredLoan.reconstitute(null,
                Borrower.individual(BORROWER, "홍길동"), LoanProductType.MORTGAGE, collateral,
                new BigDecimal("100000000.00"), 120, new BigDecimal("4.30"),
                RepaymentMethod.EQUAL_PAYMENT, null, null, new BigDecimal("100000000.00"),
                SecuredLoanStatus.OVERDUE, LocalDateTime.now(CLOCK)));

        assertThatThrownBy(() -> service.dispose(loan.getId(), new BigDecimal("100000000")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 처분대금이_0이하면_거부한다() {
        SecuredLoan loan = defaultedLoan(CollateralType.REAL_ESTATE);

        assertThatThrownBy(() -> service.dispose(loan.getId(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 페이크 ──────────────────────────────────────────────────────────────

    private static final class FakeStore implements LoadSecuredLoanPort, SaveSecuredLoanPort {
        private final Map<Long, SecuredLoan> loans = new HashMap<>();
        private final Map<Long, Collateral> collaterals = new HashMap<>();
        private final AtomicLong loanSeq = new AtomicLong();
        private final AtomicLong collateralSeq = new AtomicLong();

        @Override public Optional<SecuredLoan> findById(Long id) { return Optional.ofNullable(loans.get(id)); }
        @Override public Optional<SecuredLoan> findByIdForUpdate(Long id) { return findById(id); }
        @Override public List<SecuredLoan> findByBorrower(Long userId, int limit) { return List.of(); }
        @Override public List<SecuredLoan> findRepayable() { return List.copyOf(loans.values()); }

        @Override
        public SecuredLoan save(SecuredLoan loan) {
            Long id = loan.getId() == null ? loanSeq.incrementAndGet() : loan.getId();
            Collateral collateral = loan.getCollateral() == null ? null
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
                    collateral.getSeniorClaimAmount(), collateral.getAppraisedAt(),
                    collateral.getStatus() == CollateralStatus.PLEDGED
                            ? CollateralStatus.ACTIVE : collateral.getStatus());
            collaterals.put(id, stored);
            return stored;
        }
    }

    private static final class RecordingLedger implements AppendLedgerPort {
        private final List<LoanLedgerEntry> entries = new ArrayList<>();

        @Override
        public void append(LoanLedgerEntry entry) {
            entries.add(entry);
        }
    }
}
