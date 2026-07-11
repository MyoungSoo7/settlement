package github.lms.lemuel.loan.domain;

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
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("전표 금액은 양수여야 합니다: " + amount);
        }
        this.id = id;
        this.debit = debit;
        this.credit = credit;
        this.amount = amount;
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
