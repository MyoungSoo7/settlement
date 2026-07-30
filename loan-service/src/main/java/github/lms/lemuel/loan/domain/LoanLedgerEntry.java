package github.lms.lemuel.loan.domain;

import github.lms.lemuel.common.ledger.LedgerInvariants;
import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;

/**
 * loan 자체 복식부기 전표 (순수 POJO).
 *
 * <p>각 전표는 차변(debit) 1 + 대변(credit) 1 로 구성된 균형 분개다. 한 전표 안에서 차변금액 =
 * 대변금액(=amount) 이므로 균형이 구성적으로 보장된다.
 *
 * <pre>
 * 선지급   : 차변 LOAN_RECEIVABLE / 대변 CASH            (amount = 원금)
 * 수수료인식: 차변 FEE_RECEIVABLE  / 대변 FEE_INCOME      (amount = 수수료)
 * 상환     : 차변 CASH            / 대변 LOAN_RECEIVABLE  (amount = 차감액)
 * </pre>
 */
public class LoanLedgerEntry {

    private final Long id;
    private final LedgerAccount debit;
    private final LedgerAccount credit;
    private final BigDecimal amount;
    private final String refType;   // "DISBURSE" / "FEE" / "REPAYMENT"
    private final Long refId;        // loanId 또는 settlementId

    private LoanLedgerEntry(Long id, LedgerAccount debit, LedgerAccount credit,
                            BigDecimal amount, String refType, Long refId) {
        // 양수 금액 불변식은 공용 LedgerInvariants 단일 출처로 강제한다(AccountEntry·settlement LedgerEntry 동형).
        // loan_ledger_entries.amount 는 NUMERIC(19,2) 라 Money(scale 2 HALF_UP) 저장 표현이 동일하다.
        Money money = LedgerInvariants.requirePositiveAmount(amount,
                () -> new LoanInvariantViolationException("전표 금액은 양수여야 합니다: " + amount));
        this.id = id;
        this.debit = debit;
        this.credit = credit;
        this.amount = money.toBigDecimal();   // 영속 경계로는 원시 BigDecimal 로 환원
        this.refType = refType;
        this.refId = refId;
    }

    public static LoanLedgerEntry disbursement(Long loanId, BigDecimal principal) {
        return new LoanLedgerEntry(null, LedgerAccount.LOAN_RECEIVABLE, LedgerAccount.CASH,
                principal, "DISBURSE", loanId);
    }

    public static LoanLedgerEntry feeAccrual(Long loanId, BigDecimal fee) {
        return new LoanLedgerEntry(null, LedgerAccount.FEE_RECEIVABLE, LedgerAccount.FEE_INCOME,
                fee, "FEE", loanId);
    }

    public static LoanLedgerEntry repayment(Long settlementId, BigDecimal amount) {
        return new LoanLedgerEntry(null, LedgerAccount.CASH, LedgerAccount.LOAN_RECEIVABLE,
                amount, "REPAYMENT", settlementId);
    }

    /** 기업 신용대출 실행 전표: 차변 LOAN_RECEIVABLE / 대변 CASH (amount = 원금). */
    public static LoanLedgerEntry corporateDisbursement(Long loanId, BigDecimal principal) {
        return new LoanLedgerEntry(null, LedgerAccount.LOAN_RECEIVABLE, LedgerAccount.CASH,
                principal, "CORP_DISBURSE", loanId);
    }

    /** 기업 신용대출 수수료 인식 전표: 차변 FEE_RECEIVABLE / 대변 FEE_INCOME (amount = 수수료). */
    public static LoanLedgerEntry corporateFeeAccrual(Long loanId, BigDecimal fee) {
        return new LoanLedgerEntry(null, LedgerAccount.FEE_RECEIVABLE, LedgerAccount.FEE_INCOME,
                fee, "CORP_FEE", loanId);
    }

    /**
     * 기업 신용대출 상환 전표: 차변 CASH / 대변 LOAN_RECEIVABLE (amount = 차감액).
     * 선정산 {@link #repayment(Long, BigDecimal)} 과 계정 방향은 동일하나 refId 가 loanId 라 구분한다.
     */
    public static LoanLedgerEntry corporateRepayment(Long loanId, BigDecimal amount) {
        return new LoanLedgerEntry(null, LedgerAccount.CASH, LedgerAccount.LOAN_RECEIVABLE,
                amount, "CORP_REPAYMENT", loanId);
    }

    /**
     * 대손 상각 전표: 차변 BAD_DEBT_EXPENSE / 대변 BAD_DEBT_ALLOWANCE (amount = 상각 손실액=미상환잔액).
     * 연체(OVERDUE) 대출을 회수 불능으로 확정할 때 손실을 비용/충당금으로 인식한다(ledger-invariants).
     */
    public static LoanLedgerEntry badDebtWriteOff(Long loanId, BigDecimal loss) {
        return new LoanLedgerEntry(null, LedgerAccount.BAD_DEBT_EXPENSE, LedgerAccount.BAD_DEBT_ALLOWANCE,
                loss, "BAD_DEBT", loanId);
    }

    /**
     * 담보/개인신용 대출 실행 전표: 차변 LOAN_RECEIVABLE / 대변 CASH (amount = 원금).
     * 대출 1건당 1회라 {@code uq_loan_ledger_reference_accounts} 유니크 대상으로 남아 이중지급을 막는다.
     */
    public static LoanLedgerEntry securedDisbursement(Long loanId, BigDecimal principal) {
        return new LoanLedgerEntry(null, LedgerAccount.LOAN_RECEIVABLE, LedgerAccount.CASH,
                principal, "SEC_DISBURSE", loanId);
    }

    /**
     * 담보/개인신용 대출 원금 상환 전표: 차변 CASH / 대변 LOAN_RECEIVABLE (amount = 원금 차감액).
     * 장기 분할상환이라 회차 수만큼 반복되므로 중복 기표 유니크에서 제외된다.
     */
    public static LoanLedgerEntry securedPrincipalRepayment(Long loanId, BigDecimal principalPortion) {
        return new LoanLedgerEntry(null, LedgerAccount.CASH, LedgerAccount.LOAN_RECEIVABLE,
                principalPortion, "SEC_REPAYMENT", loanId);
    }

    /**
     * 담보/개인신용 대출 이자 수익 전표: 차변 CASH / 대변 FEE_INCOME (amount = 회차 이자).
     *
     * <p>단기 상품이 실행 시점에 수수료를 한 번에 인식({@link #corporateFeeAccrual})하는 것과 달리,
     * 장기 분할상환은 이자가 회차마다 잔액 기준으로 발생하므로 <b>수취 시점에 회차별로</b> 인식한다.
     * 따라서 회차 수만큼 반복되며 중복 기표 유니크에서 제외된다.
     */
    public static LoanLedgerEntry securedInterestIncome(Long loanId, BigDecimal interest) {
        return new LoanLedgerEntry(null, LedgerAccount.CASH, LedgerAccount.FEE_INCOME,
                interest, "SEC_INTEREST", loanId);
    }

    // ─── Phase 2: 보증부 · 중도상환 · 담보 실행 ────────────────────────────────

    /**
     * 보증료 선취 전표: 차변 GUARANTEE_FEE_EXPENSE / 대변 CASH (amount = 보증료).
     *
     * <p>우리가 보증기관에 <b>지급</b>하는 비용이라 방향이 수익성 전표들과 반대다 — 이자·수수료가
     * 현금 유입(차변 CASH)인 반면 보증료는 현금 유출(대변 CASH)이다. 실행 시 1회.
     */
    public static LoanLedgerEntry securedGuaranteeFee(Long loanId, BigDecimal fee) {
        return new LoanLedgerEntry(null, LedgerAccount.GUARANTEE_FEE_EXPENSE, LedgerAccount.CASH,
                fee, "SEC_GUARANTEE_FEE", loanId);
    }

    /**
     * 중도상환수수료 전표: 차변 CASH / 대변 FEE_INCOME (amount = 수수료).
     * 중도상환은 여러 번 가능하므로 중복 기표 유니크에서 제외된다.
     */
    public static LoanLedgerEntry securedEarlyRepaymentFee(Long loanId, BigDecimal fee) {
        return new LoanLedgerEntry(null, LedgerAccount.CASH, LedgerAccount.FEE_INCOME,
                fee, "SEC_EARLY_FEE", loanId);
    }

    /**
     * 담보 처분 회수 전표: 차변 CASH / 대변 LOAN_RECEIVABLE (amount = 채권에 충당된 회수액).
     * 담보 1건당 1회 처분이므로 유니크 대상으로 남겨 이중 회수 기표를 막는다.
     */
    public static LoanLedgerEntry securedCollateralDisposal(Long loanId, BigDecimal proceeds) {
        return new LoanLedgerEntry(null, LedgerAccount.CASH, LedgerAccount.LOAN_RECEIVABLE,
                proceeds, "SEC_DISPOSAL", loanId);
    }

    /**
     * 처분 부족분 전표: 차변 COLLATERAL_DISPOSAL_LOSS / 대변 LOAN_RECEIVABLE (amount = 미회수 채권).
     *
     * <p>회수하지 못한 채권을 털어내면서 손실을 비용으로 인식한다. 대손 상각
     * ({@link #securedWriteOff})과 달리 <b>담보를 실제로 처분한 결과</b>라 충당금이 아니라
     * 처분손실 계정으로 간다 — 손익계산서에서 성격이 다른 손실이다.
     */
    public static LoanLedgerEntry securedDisposalShortfall(Long loanId, BigDecimal loss) {
        return new LoanLedgerEntry(null, LedgerAccount.COLLATERAL_DISPOSAL_LOSS,
                LedgerAccount.LOAN_RECEIVABLE, loss, "SEC_DISPOSAL_LOSS", loanId);
    }

    /**
     * 처분 초과분 전표: 차변 CASH / 대변 COLLATERAL_DISPOSAL_GAIN (amount = 채권 초과 회수액).
     * 채권액보다 많이 회수된 몫이다(차주 반환 정산은 이 단계 범위 밖).
     */
    public static LoanLedgerEntry securedDisposalSurplus(Long loanId, BigDecimal gain) {
        return new LoanLedgerEntry(null, LedgerAccount.CASH, LedgerAccount.COLLATERAL_DISPOSAL_GAIN,
                gain, "SEC_DISPOSAL_GAIN", loanId);
    }

    /**
     * 대위변제 회수 전표: 차변 CASH / 대변 LOAN_RECEIVABLE (amount = 보증기관 회수액).
     * 보증비율만큼만 회수되고 미보증분은 {@link #securedWriteOff} 로 상각된다.
     */
    public static LoanLedgerEntry securedSubrogation(Long loanId, BigDecimal recovered) {
        return new LoanLedgerEntry(null, LedgerAccount.CASH, LedgerAccount.LOAN_RECEIVABLE,
                recovered, "SEC_SUBROGATION", loanId);
    }

    /**
     * 담보대출 미회수 상각 전표: 차변 BAD_DEBT_EXPENSE / 대변 BAD_DEBT_ALLOWANCE (amount = 손실액).
     * 선정산 {@link #badDebtWriteOff} 와 계정 방향은 같으나 refType 으로 상품을 구분한다.
     */
    public static LoanLedgerEntry securedWriteOff(Long loanId, BigDecimal loss) {
        return new LoanLedgerEntry(null, LedgerAccount.BAD_DEBT_EXPENSE, LedgerAccount.BAD_DEBT_ALLOWANCE,
                loss, "SEC_BAD_DEBT", loanId);
    }

    public static LoanLedgerEntry reconstitute(Long id, LedgerAccount debit, LedgerAccount credit,
                                               BigDecimal amount, String refType, Long refId) {
        return new LoanLedgerEntry(id, debit, credit, amount, refType, refId);
    }

    public Long getId() { return id; }
    public LedgerAccount getDebit() { return debit; }
    public LedgerAccount getCredit() { return credit; }
    public BigDecimal getAmount() { return amount; }
    public String getRefType() { return refType; }
    public Long getRefId() { return refId; }
}
