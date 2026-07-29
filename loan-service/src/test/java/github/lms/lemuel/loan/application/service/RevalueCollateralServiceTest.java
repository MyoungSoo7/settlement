package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RevalueCollateralUseCase.Outcome;
import github.lms.lemuel.loan.application.port.in.RevalueCollateralUseCase.RevaluationResult;
import github.lms.lemuel.loan.application.port.out.CollateralRiskPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveSecuredLoanPort;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralRevaluation;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.CollateralType;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.MarginCall;
import github.lms.lemuel.loan.domain.MarginCallStatus;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * 담보 재평가 · 마진콜 판정 서비스.
 *
 * <p>임계 경계에서 <b>어떤 조치가 나오는지</b>를 본다. 판정이 한 칸 어긋나면 정상 대출을 강제 청산하거나
 * 부실을 방치하므로, 140%/120% 경계와 경계−1 을 모두 확인한다.
 */
class RevalueCollateralServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final Long BORROWER = 42L;

    private FakeStore store;
    private FakeRiskPort risk;
    private RevalueCollateralService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        risk = new FakeRiskPort();
        service = new RevalueCollateralService(store, risk,
                new BigDecimal("3.5"), new BigDecimal("0.70"), CLOCK);
    }

    /** 금융자산(주식) 담보 + 실행된 대출. 잔액 1억. */
    private SecuredLoan equityLoan(String appraised, String senior) {
        Collateral collateral = store.saveCollateral(Collateral.pledge(CollateralType.EQUITY,
                "삼성전자 1000주", new BigDecimal(appraised), new BigDecimal(senior),
                java.time.LocalDateTime.now(CLOCK)));
        SecuredLoan loan = SecuredLoan.reconstitute(null, Borrower.individual(BORROWER, "홍길동"),
                LoanProductType.MORTGAGE, collateral, new BigDecimal("100000000.00"), 36,
                new BigDecimal("4.30"), RepaymentMethod.EQUAL_PAYMENT, null, null,
                new BigDecimal("100000000.00"), SecuredLoanStatus.DISBURSED,
                java.time.LocalDateTime.now(CLOCK), java.time.LocalDateTime.now(CLOCK));
        return store.save(loan);
    }

    // ─── 재평가 이력 ──────────────────────────────────────────────────────────

    @Test
    void 재평가는_이력으로_쌓이고_설정평가액은_보존된다() {
        SecuredLoan loan = equityLoan("200000000", "0");
        BigDecimal originalAppraised = loan.getCollateral().getAppraisedValue();

        service.revalue(loan.getId(), new BigDecimal("180000000"), "MARKET_SERVICE");

        assertThat(risk.revaluations).hasSize(1);
        assertThat(risk.revaluations.get(0).revaluedValue()).isEqualByComparingTo("180000000");
        assertThat(risk.revaluations.get(0).source()).isEqualTo("MARKET_SERVICE");
        // 원 평가액은 그대로 — 한도 산정 근거는 사후에 바뀌지 않는다.
        assertThat(store.collaterals.get(loan.getCollateral().getId()).getAppraisedValue())
                .isEqualByComparingTo(originalAppraised);
    }

    // ─── 판정: 경계값 ─────────────────────────────────────────────────────────

    @Test
    void 유지비율_140퍼센트_정확히_충족하면_조치없음() {
        SecuredLoan loan = equityLoan("200000000", "0");

        RevaluationResult result = service.revalue(loan.getId(), new BigDecimal("140000000"), "MANUAL");

        assertThat(result.outcome()).isEqualTo(Outcome.SUFFICIENT);
        assertThat(result.coverageRatio()).isEqualByComparingTo("1.40");
        assertThat(result.requiredAmount()).isEqualByComparingTo("0");
        assertThat(risk.marginCalls).isEmpty();
    }

    @Test
    void 유지비율_140_미달이면_마진콜_발생하고_부족액을_요구한다() {
        SecuredLoan loan = equityLoan("200000000", "0");

        RevaluationResult result = service.revalue(loan.getId(), new BigDecimal("130000000"), "MANUAL");

        assertThat(result.outcome()).isEqualTo(Outcome.MARGIN_CALL);
        // 필요 1.4억 − 현재 1.3억 = 1천만
        assertThat(result.requiredAmount()).isEqualByComparingTo("10000000");
        assertThat(risk.marginCalls).hasSize(1);
        assertThat(risk.marginCalls.get(0).getStatus()).isEqualTo(MarginCallStatus.OPEN);
    }

    @Test
    void 유지비율_120_미달이면_강제처분_이관이다() {
        SecuredLoan loan = equityLoan("200000000", "0");

        RevaluationResult result = service.revalue(loan.getId(), new BigDecimal("110000000"), "MANUAL");

        assertThat(result.outcome()).isEqualTo(Outcome.LIQUIDATION);
        assertThat(risk.marginCalls).hasSize(1);
        assertThat(risk.marginCalls.get(0).getStatus()).isEqualTo(MarginCallStatus.ESCALATED);
    }

    @Test
    void 유지비율_120_정확히_충족하면_이관하지_않고_마진콜만() {
        SecuredLoan loan = equityLoan("200000000", "0");

        RevaluationResult result = service.revalue(loan.getId(), new BigDecimal("120000000"), "MANUAL");

        assertThat(result.outcome()).isEqualTo(Outcome.MARGIN_CALL);
    }

    // ─── 선순위 반영 ──────────────────────────────────────────────────────────

    @Test
    void 선순위가_있으면_재평가액에서도_차감해_판정한다() {
        // 재평가 2억이지만 선순위 1억 → 유효 1억 → 유지비율 1.00 → 청산선 미달
        SecuredLoan loan = equityLoan("300000000", "100000000");

        RevaluationResult result = service.revalue(loan.getId(), new BigDecimal("200000000"), "MANUAL");

        assertThat(result.coverageRatio()).isEqualByComparingTo("1.00");
        assertThat(result.outcome()).isEqualTo(Outcome.LIQUIDATION);
    }

    // ─── 마진콜 중복·해소 ─────────────────────────────────────────────────────

    @Test
    void 이미_활성_마진콜이_있으면_중복_발생시키지_않는다() {
        SecuredLoan loan = equityLoan("200000000", "0");
        service.revalue(loan.getId(), new BigDecimal("130000000"), "MANUAL");

        service.revalue(loan.getId(), new BigDecimal("125000000"), "MANUAL");

        assertThat(risk.marginCalls).hasSize(1);   // 새로 열지 않는다
        assertThat(risk.revaluations).hasSize(2);  // 재평가 이력은 계속 쌓인다
    }

    @Test
    void 담보가_회복되면_활성_마진콜이_해소된다() {
        SecuredLoan loan = equityLoan("200000000", "0");
        service.revalue(loan.getId(), new BigDecimal("130000000"), "MANUAL");

        RevaluationResult result = service.revalue(loan.getId(), new BigDecimal("160000000"), "MANUAL");

        assertThat(result.outcome()).isEqualTo(Outcome.SUFFICIENT);
        assertThat(risk.marginCalls.get(0).getStatus()).isEqualTo(MarginCallStatus.RESOLVED);
    }

    // ─── 대상 아닌 담보 ───────────────────────────────────────────────────────

    @Test
    void 부동산_담보는_재평가는_되지만_마진콜_대상이_아니다() {
        Collateral realEstate = store.saveCollateral(Collateral.pledge(CollateralType.REAL_ESTATE,
                "서울시 강남구", new BigDecimal("500000000"), java.time.LocalDateTime.now(CLOCK)));
        SecuredLoan loan = store.save(SecuredLoan.reconstitute(null,
                Borrower.individual(BORROWER, "홍길동"), LoanProductType.MORTGAGE, realEstate,
                new BigDecimal("400000000.00"), 360, new BigDecimal("4.30"),
                RepaymentMethod.EQUAL_PAYMENT, null, null, new BigDecimal("400000000.00"),
                SecuredLoanStatus.DISBURSED, java.time.LocalDateTime.now(CLOCK),
                java.time.LocalDateTime.now(CLOCK)));

        // 유지비율 1.0 로 크게 미달이지만 주택담보는 마진콜하지 않는다.
        RevaluationResult result = service.revalue(loan.getId(), new BigDecimal("400000000"), "MANUAL");

        assertThat(result.outcome()).isEqualTo(Outcome.SUFFICIENT);
        assertThat(risk.marginCalls).isEmpty();
        assertThat(risk.revaluations).hasSize(1);   // 이력은 남는다
    }

    @Test
    void 담보없는_개인신용대출은_재평가_대상이_아니다() {
        SecuredLoan loan = store.save(SecuredLoan.reconstitute(null,
                Borrower.individual(BORROWER, "홍길동"), LoanProductType.PERSONAL_CREDIT, null,
                new BigDecimal("10000000.00"), 36, new BigDecimal("6.00"),
                RepaymentMethod.EQUAL_PAYMENT, 780, "B", new BigDecimal("10000000.00"),
                SecuredLoanStatus.DISBURSED, java.time.LocalDateTime.now(CLOCK),
                java.time.LocalDateTime.now(CLOCK)));

        assertThatThrownBy(() -> service.revalue(loan.getId(), new BigDecimal("1"), "MANUAL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 없는_대출은_404() {
        assertThatThrownBy(() -> service.revalue(9999L, new BigDecimal("1"), "MANUAL"))
                .isInstanceOf(SecuredLoanNotFoundException.class);
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
                    loan.getOutstanding(), loan.getStatus(), loan.getCreatedAt(), loan.getDisbursedAt());
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

    private static final class FakeRiskPort implements CollateralRiskPort {
        private final List<CollateralRevaluation> revaluations = new ArrayList<>();
        private final List<MarginCall> marginCalls = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public CollateralRevaluation appendRevaluation(CollateralRevaluation revaluation) {
            revaluations.add(revaluation);
            return revaluation;
        }

        @Override
        public Optional<BigDecimal> findLatestValue(Long collateralId) {
            return revaluations.stream()
                    .filter(r -> r.collateralId().equals(collateralId))
                    .map(CollateralRevaluation::revaluedValue)
                    .reduce((first, second) -> second);
        }

        @Override
        public Optional<MarginCall> findOpenMarginCall(Long loanId) {
            return marginCalls.stream()
                    .filter(c -> c.getLoanId().equals(loanId) && c.isOpen())
                    .findFirst();
        }

        @Override
        public MarginCall saveMarginCall(MarginCall marginCall) {
            if (marginCall.getId() == null) {
                MarginCall stored = MarginCall.reconstitute(seq.incrementAndGet(), marginCall.getLoanId(),
                        marginCall.getCollateralId(), marginCall.getRequiredAmount(),
                        marginCall.getStatus(), marginCall.getOpenedAt(), marginCall.getClosedAt());
                marginCalls.add(stored);
                return stored;
            }
            return marginCall;
        }
    }
}
