package github.lms.lemuel.loan.domain;

import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 담보/개인신용 대출 애그리거트 루트. 순수 POJO — 프레임워크 의존 0.
 *
 * <p>한 애그리거트가 담보형(주택담보)과 무담보형(개인신용)을 함께 수용한다. 두 상품은 <b>심사 근거만
 * 다르고 실행·상환·연체 흐름은 동일</b>하므로, 상품마다 애그리거트를 쪼개면 상태머신·원장·이벤트 배선이
 * 상품 수만큼 복제된다. 대신 상품별 필수 요소는 생성 시점에 강제한다 — 담보 없는 주택담보대출이나
 * CB 점수 없는 개인신용대출은 만들어질 수 없다.
 *
 * <p>기존 {@link CorporateLoan} 과의 차이:
 * <ul>
 *   <li>차주가 종목코드가 아니라 {@link Borrower} VO — 개인도 차주가 된다.</li>
 *   <li>실행 시 미상환잔액은 <b>원금뿐</b>이다. 단기 무담보 상품은 수수료를 선취해 잔액에 얹지만,
 *       장기 분할상환은 이자가 회차마다 잔액 기준으로 발생하므로 실행 시점에 합산할 이자가 없다.</li>
 *   <li>연체·기한이익상실이 상태머신에 포함된다.</li>
 * </ul>
 *
 * <p>신용점수·등급은 신청 시점 값을 <b>스냅샷</b>으로 보존한다(정산의 {@code commission_rate} 스냅샷과
 * 같은 이력 재현성 철학). 한도·금리 산정은 정책({@code SecuredLoanPolicy})의 책임이며, 이 애그리거트는
 * 산정된 원금·연이율을 받아 잔액과 상태를 강제한다.
 */
public class SecuredLoan {

    private final Long id;
    private final Borrower borrower;
    private final LoanProductType productType;
    private final Collateral collateral;
    private final BigDecimal principal;
    private final int termMonths;
    private final BigDecimal annualRatePercent;
    private final RepaymentMethod repaymentMethod;
    private final Integer creditScore;
    private final String creditGrade;
    private BigDecimal outstanding;
    private SecuredLoanStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime disbursedAt;

    private SecuredLoan(Long id, Borrower borrower, LoanProductType productType, Collateral collateral,
                        BigDecimal principal, int termMonths, BigDecimal annualRatePercent,
                        RepaymentMethod repaymentMethod, Integer creditScore, String creditGrade,
                        BigDecimal outstanding, SecuredLoanStatus status, LocalDateTime createdAt) {
        this.id = id;
        this.borrower = borrower;
        this.productType = productType;
        this.collateral = collateral;
        this.principal = principal;
        this.termMonths = termMonths;
        this.annualRatePercent = annualRatePercent;
        this.repaymentMethod = repaymentMethod;
        this.creditScore = creditScore;
        this.creditGrade = creditGrade;
        this.outstanding = outstanding;
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * 주택담보대출 신청. 담보가 반드시 있어야 하며, 실행 시점에 그 담보가 유효(ACTIVE)해야 한다.
     *
     * @param createdAt 신청 시각 — 응용 계층이 KST {@link java.time.Clock} 으로 만들어 넘긴다
     *                  (도메인은 시각을 생성하지 않는다 — off-by-one 방지, 테스트 결정성)
     */
    public static SecuredLoan requestMortgage(Borrower borrower, Collateral collateral, BigDecimal principal,
                                              int termMonths, BigDecimal annualRatePercent,
                                              RepaymentMethod repaymentMethod, LocalDateTime createdAt) {
        if (collateral == null) {
            throw new LoanInvariantViolationException("주택담보대출은 담보가 필수입니다");
        }
        validateCommon(borrower, principal, termMonths, annualRatePercent, repaymentMethod, createdAt);
        return new SecuredLoan(null, borrower, LoanProductType.MORTGAGE, collateral,
                Money.of(principal).toBigDecimal(), termMonths, annualRatePercent, repaymentMethod,
                null, null, BigDecimal.ZERO, SecuredLoanStatus.REQUESTED, createdAt);
    }

    /**
     * 금융자산담보대출 신청(Phase 2). 담보가 필수이며 <b>금융자산 계열</b>(예금·채권·주식)이어야 한다 —
     * 부동산·보증서를 이 상품에 끼워 넣으면 유형별 인정비율·마진콜(시가 변동 감시) 규칙이 어긋난다.
     */
    public static SecuredLoan requestFinancialAsset(Borrower borrower, Collateral collateral,
                                                    BigDecimal principal, int termMonths,
                                                    BigDecimal annualRatePercent,
                                                    RepaymentMethod repaymentMethod, LocalDateTime createdAt) {
        if (collateral == null) {
            throw new LoanInvariantViolationException("금융자산담보대출은 담보가 필수입니다");
        }
        if (collateral.getType().category() != CollateralType.Category.FINANCIAL_ASSET) {
            throw new LoanInvariantViolationException(
                    "금융자산담보대출의 담보는 금융자산 계열이어야 합니다: " + collateral.getType());
        }
        validateCommon(borrower, principal, termMonths, annualRatePercent, repaymentMethod, createdAt);
        return new SecuredLoan(null, borrower, LoanProductType.FINANCIAL_ASSET, collateral,
                Money.of(principal).toBigDecimal(), termMonths, annualRatePercent, repaymentMethod,
                null, null, BigDecimal.ZERO, SecuredLoanStatus.REQUESTED, createdAt);
    }

    /**
     * 개인신용대출 신청. 담보가 없으므로 외부 CB 점수·등급 스냅샷이 심사의 유일한 근거이며 필수다.
     */
    public static SecuredLoan requestPersonalCredit(Borrower borrower, BigDecimal principal, int termMonths,
                                                    BigDecimal annualRatePercent, RepaymentMethod repaymentMethod,
                                                    Integer creditScore, String creditGrade,
                                                    LocalDateTime createdAt) {
        if (creditScore == null) {
            throw new LoanInvariantViolationException("개인신용대출은 신용점수(CB)가 필수입니다");
        }
        if (creditGrade == null || creditGrade.isBlank()) {
            throw new LoanInvariantViolationException("개인신용대출은 신용등급이 필수입니다");
        }
        validateCommon(borrower, principal, termMonths, annualRatePercent, repaymentMethod, createdAt);
        return new SecuredLoan(null, borrower, LoanProductType.PERSONAL_CREDIT, null,
                Money.of(principal).toBigDecimal(), termMonths, annualRatePercent, repaymentMethod,
                creditScore, creditGrade.trim(), BigDecimal.ZERO, SecuredLoanStatus.REQUESTED, createdAt);
    }

    /** 영속화된 상태를 재구성(리포지토리 전용 — 저장된 값은 이미 검증을 통과했다). */
    public static SecuredLoan reconstitute(Long id, Borrower borrower, LoanProductType productType,
                                           Collateral collateral, BigDecimal principal, int termMonths,
                                           BigDecimal annualRatePercent, RepaymentMethod repaymentMethod,
                                           Integer creditScore, String creditGrade, BigDecimal outstanding,
                                           SecuredLoanStatus status, LocalDateTime createdAt,
                                           LocalDateTime disbursedAt) {
        SecuredLoan loan = new SecuredLoan(id, borrower, productType, collateral, principal, termMonths,
                annualRatePercent, repaymentMethod, creditScore, creditGrade, outstanding, status, createdAt);
        loan.disbursedAt = disbursedAt;
        return loan;
    }

    // ─── 생명주기 ────────────────────────────────────────────────────────────

    public void approve() {
        requireTransition(SecuredLoanStatus.APPROVED);
        this.status = SecuredLoanStatus.APPROVED;
    }

    public void reject() {
        requireTransition(SecuredLoanStatus.REJECTED);
        this.status = SecuredLoanStatus.REJECTED;
    }

    /**
     * 실행(대출금 지급). 미상환잔액 = 원금 — 이자는 회차별로 잔액 기준 후취되므로 실행 시점에 얹지 않는다.
     *
     * <p>담보형 상품은 담보가 <b>유효(ACTIVE)</b>해야만 실행된다. 설정 진행 중(PLEDGED)인 담보로 돈이
     * 나가면 무담보 대출이 되어 버리므로 상태 가드로 막는다.
     */
    public void disburse(LocalDateTime disbursedAt) {
        requireTransition(SecuredLoanStatus.DISBURSED);
        requireActiveCollateral();
        if (disbursedAt == null) {
            throw new LoanInvariantViolationException("실행 시각(disbursedAt)은 필수입니다");
        }
        this.outstanding = principal;
        this.disbursedAt = disbursedAt;
        this.status = SecuredLoanStatus.DISBURSED;
    }

    /**
     * 중도상환 — 약정 회차와 무관하게 원금을 앞당겨 갚는다. <b>정상(DISBURSED) 상태 전용</b>.
     *
     * <p>연체·기한이익상실 중 납입은 연체 해소({@link #repay}) 경로다 — 수수료가 붙는 중도상환으로
     * 받으면 연체자에게 수수료까지 물리게 되므로 상태머신이 막는다. 수수료 산정은 정책
     * ({@code SecuredLoanPolicy#earlyRepaymentFee})의 몫이고, 이 애그리거트는 잔액·상태만 강제한다.
     *
     * @return 실제 차감된 금액(수수료 산정의 기준)
     */
    public BigDecimal prepay(BigDecimal amount) {
        if (status != SecuredLoanStatus.DISBURSED) {
            throw new InvalidLoanStateException(status, SecuredLoanStatus.DISBURSED);
        }
        return repay(amount);
    }

    /**
     * 경과일수 = 기산일 → 기준시각. 중도상환수수료(부과기간 1095일 잔여 비례)의 입력이다.
     *
     * <p>기산일은 <b>실행일</b>이다. {@code disbursed_at} 도입 이전에 실행된 구(舊) 행은 실행 시각이
     * 없으므로 신청 시각으로 폴백한다 — 신청일이 실행일보다 이르므로 경과가 길게 계산돼 면제(경과
     * 3년)가 빨리 오는, 차주에게 불리하지 않은 방향의 근사다. 기준시각이 기산일보다 이르면(시계 역행)
     * 0 으로 clamp 한다 — 음수 경과가 새면 수수료가 요율 전액을 넘을 수 있다.
     */
    public int elapsedDays(LocalDateTime now) {
        LocalDateTime anchor = disbursedAt != null ? disbursedAt : createdAt;
        return (int) Math.max(0, ChronoUnit.DAYS.between(anchor.toLocalDate(), now.toLocalDate()));
    }

    /**
     * 부분상환. 미상환잔액에서 차감하되 잔액을 넘어서 차감하지 않는다(clamp).
     * 잔액이 0 이 되면 REPAID 로 전이한다. 연체·기한이익상실 상태에서도 상환할 수 있다.
     *
     * @param amount 상환액(양수)
     * @return 실제 차감된 금액
     */
    public BigDecimal repay(BigDecimal amount) {
        requireTransition(SecuredLoanStatus.REPAID);
        if (amount == null || amount.signum() <= 0) {
            throw new LoanInvariantViolationException("상환액은 양수여야 합니다: " + amount);
        }
        Money remaining = Money.of(outstanding);
        Money deducted = remaining.min(Money.of(amount));
        remaining = remaining.minus(deducted);
        this.outstanding = remaining.toBigDecimal();
        if (remaining.isZero()) {
            this.status = SecuredLoanStatus.REPAID;
        }
        return deducted.toBigDecimal();
    }

    /** 회차 미납으로 연체 진입. */
    public void markOverdue() {
        requireTransition(SecuredLoanStatus.OVERDUE);
        this.status = SecuredLoanStatus.OVERDUE;
    }

    /** 기한이익상실 — 잔여 원금 전액을 즉시 청구한다. 연체를 거친 뒤에만 가능하다. */
    public void accelerate() {
        requireTransition(SecuredLoanStatus.DEFAULTED);
        this.status = SecuredLoanStatus.DEFAULTED;
    }

    /**
     * 상각 — 담보 실행으로 회수하지 못한 잔여분을 손실로 확정한다.
     *
     * <p><b>기한이익상실(DEFAULTED) 이후에만</b> 가능하다. 그 전에 상각하면 아직 회수 가능한 채권을
     * 손실로 털어 버리므로 상태머신이 막는다. 전액 회수돼 이미 REPAID 라면 상각할 잔액이 없어
     * 역시 전이가 거부된다.
     *
     * @return 상각된 금액(직전 미상환잔액)
     */
    public BigDecimal writeOff() {
        requireTransition(SecuredLoanStatus.WRITTEN_OFF);
        BigDecimal loss = outstanding;
        this.outstanding = Money.of(BigDecimal.ZERO).toBigDecimal();
        this.status = SecuredLoanStatus.WRITTEN_OFF;
        return loss;
    }

    // ─── 파생 계산 ────────────────────────────────────────────────────────────

    /**
     * 회차별 상환표. 산식은 {@link RepaymentSchedule}(결정적 순수 계산)에 위임한다 — 이 애그리거트는
     * 계약 조건(원금·기간·이율·방식)만 소유한다.
     */
    public RepaymentSchedule repaymentSchedule() {
        return RepaymentSchedule.of(principal, termMonths, annualRatePercent, repaymentMethod);
    }

    // ─── 가드 ────────────────────────────────────────────────────────────────

    private static void validateCommon(Borrower borrower, BigDecimal principal, int termMonths,
                                       BigDecimal annualRatePercent, RepaymentMethod repaymentMethod,
                                       LocalDateTime createdAt) {
        if (borrower == null) {
            throw new LoanInvariantViolationException("차주는 필수입니다");
        }
        if (principal == null || principal.signum() <= 0) {
            throw new LoanInvariantViolationException("대출 원금은 양수여야 합니다: " + principal);
        }
        if (termMonths < 1) {
            throw new LoanInvariantViolationException("대출 기간(개월)은 1 이상이어야 합니다: " + termMonths);
        }
        if (annualRatePercent == null || annualRatePercent.signum() < 0) {
            throw new LoanInvariantViolationException("연이자율은 음수일 수 없습니다: " + annualRatePercent);
        }
        if (repaymentMethod == null) {
            throw new LoanInvariantViolationException("상환방식은 필수입니다");
        }
        if (createdAt == null) {
            throw new LoanInvariantViolationException("신청 시각(createdAt)은 필수입니다");
        }
    }

    /** 담보형 상품은 실행 시점에 담보가 유효해야 한다. */
    private void requireActiveCollateral() {
        if (!productType.requiresCollateral()) {
            return;
        }
        if (collateral == null || !collateral.isActive()) {
            CollateralStatus current = collateral == null ? null : collateral.getStatus();
            throw new InvalidLoanStateException(current, CollateralStatus.ACTIVE);
        }
    }

    // 상태 전이 가드 — 허용 전이는 SecuredLoanStatus#canTransitionTo 단일 출처에 위임한다.
    private void requireTransition(SecuredLoanStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidLoanStateException(status, target);
        }
    }

    public Long getId() { return id; }
    public Borrower getBorrower() { return borrower; }
    public LoanProductType getProductType() { return productType; }
    public Collateral getCollateral() { return collateral; }
    public BigDecimal getPrincipal() { return principal; }
    public int getTermMonths() { return termMonths; }
    public BigDecimal getAnnualRatePercent() { return annualRatePercent; }
    public RepaymentMethod getRepaymentMethod() { return repaymentMethod; }
    public Integer getCreditScore() { return creditScore; }
    public String getCreditGrade() { return creditGrade; }
    public BigDecimal getOutstanding() { return outstanding; }
    public SecuredLoanStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDisbursedAt() { return disbursedAt; }
}
