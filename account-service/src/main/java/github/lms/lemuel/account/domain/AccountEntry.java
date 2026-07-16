package github.lms.lemuel.account.domain;

import github.lms.lemuel.account.domain.exception.NonPositiveEntryAmountException;
import github.lms.lemuel.account.domain.exception.UnbalancedAccountEntryException;
import github.lms.lemuel.common.ledger.LedgerInvariants;
import github.lms.lemuel.common.money.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 전사 GL 분개(순수 POJO).
 *
 * <p>한 전표 = 차변 1 계정 + 대변 1 계정 + 금액 1 로 구성된 균형 분개다. 한 전표 안에서
 * 차변금액 = 대변금액(=amount) 이므로 차대 균형이 구성적으로 보장된다(loan {@code LoanLedgerEntry} 패턴).
 *
 * <p>정적 팩토리 6종이 loan·investment·settlement 이벤트를 계정과목 조합으로 매핑한다 — 이 매핑이
 * 계정계의 핵심 도메인 규칙이다. {@code sourceTopic}·{@code refType}·{@code refId} 는 전표의 자연키를
 * 이루어(=중복 수신 시 스키마 UNIQUE 로 멱등) 어느 이벤트에서 파생됐는지 추적한다.
 *
 * <pre>
 * settlementCreated        : DR SETTLEMENT_SCHEDULED      / CR SELLER_PAYABLE
 * settlementConfirmed      : DR SELLER_PAYABLE            / CR SETTLEMENT_SCHEDULED
 * loanDisbursed            : DR LOAN_RECEIVABLE           / CR CASH
 * loanRepaid               : DR CASH                      / CR LOAN_RECEIVABLE
 * corporateLoanDisbursed   : DR CORPORATE_LOAN_RECEIVABLE / CR CASH   (owner=CORPORATE)
 * investmentExecuted       : DR INVESTMENT_ASSET          / CR CASH
 * </pre>
 */
public class AccountEntry {

    public static final String TOPIC_SETTLEMENT_CREATED = "lemuel.settlement.created";
    public static final String TOPIC_SETTLEMENT_CONFIRMED = "lemuel.settlement.confirmed";
    public static final String TOPIC_LOAN_DISBURSED = "lemuel.loan.disbursement_requested";
    public static final String TOPIC_LOAN_REPAID = "lemuel.loan.repayment_applied";
    public static final String TOPIC_CORPORATE_LOAN_DISBURSED = "lemuel.loan.corporate_loan_disbursed";
    public static final String TOPIC_INVESTMENT_EXECUTED = "lemuel.investment.executed";

    private final Long id;
    private final OwnerType ownerType;
    private final String ownerId;
    private final GlAccount debitAccount;
    private final GlAccount creditAccount;
    private final BigDecimal amount;
    private final String refType;
    private final String refId;
    private final String sourceTopic;
    private final LocalDateTime occurredAt;

    private AccountEntry(Long id, OwnerType ownerType, String ownerId,
                         GlAccount debitAccount, GlAccount creditAccount, BigDecimal amount,
                         String refType, String refId, String sourceTopic, LocalDateTime occurredAt) {
        // 구성적 균형 불변식(양수 금액 + 차변≠대변)은 공용 LedgerInvariants 단일 출처로 강제한다.
        // account_entries.amount 는 numeric(19,2) 라 Money(scale 2 HALF_UP) 저장 표현이 동일하다.
        Money money = LedgerInvariants.requirePositiveAmount(amount, () -> new NonPositiveEntryAmountException(amount));
        LedgerInvariants.requireDistinctAccounts(debitAccount, creditAccount,
                () -> new UnbalancedAccountEntryException(debitAccount));
        this.id = id;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amount = money.toBigDecimal();   // 영속 경계로는 원시 BigDecimal 로 환원
        this.refType = refType;
        this.refId = refId;
        this.sourceTopic = sourceTopic;
        this.occurredAt = occurredAt;
    }

    private static AccountEntry of(OwnerType ownerType, String ownerId,
                                   GlAccount debit, GlAccount credit, BigDecimal amount,
                                   String refType, String refId, String sourceTopic) {
        return new AccountEntry(null, ownerType, ownerId, debit, credit, amount,
                refType, refId, sourceTopic, LocalDateTime.now());
    }

    /** 정산 생성 → DR SETTLEMENT_SCHEDULED / CR SELLER_PAYABLE. */
    public static AccountEntry settlementCreated(String sellerId, String settlementId, BigDecimal amount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.SETTLEMENT_SCHEDULED, GlAccount.SELLER_PAYABLE, amount,
                "SETTLEMENT_CREATED", settlementId, TOPIC_SETTLEMENT_CREATED);
    }

    /** 정산 확정 → DR SELLER_PAYABLE / CR SETTLEMENT_SCHEDULED (예정 상계). */
    public static AccountEntry settlementConfirmed(String sellerId, String settlementId, BigDecimal amount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.SELLER_PAYABLE, GlAccount.SETTLEMENT_SCHEDULED, amount,
                "SETTLEMENT_CONFIRMED", settlementId, TOPIC_SETTLEMENT_CONFIRMED);
    }

    /** 셀러 선정산 선지급 → DR LOAN_RECEIVABLE / CR CASH. */
    public static AccountEntry loanDisbursed(String sellerId, String loanId, BigDecimal amount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.LOAN_RECEIVABLE, GlAccount.CASH, amount,
                "LOAN_DISBURSED", loanId, TOPIC_LOAN_DISBURSED);
    }

    /**
     * 셀러 대출 상환 차감 → DR CASH / CR LOAN_RECEIVABLE.
     * deducted 0 이면 분개가 성립하지 않으므로 호출 전 서비스/컨슈머에서 스킵한다(팩토리는 양수만 허용).
     */
    public static AccountEntry loanRepaid(String sellerId, String settlementId, BigDecimal deducted) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.CASH, GlAccount.LOAN_RECEIVABLE, deducted,
                "LOAN_REPAID", settlementId, TOPIC_LOAN_REPAID);
    }

    /** 법인 대출 선지급(원금만) → DR CORPORATE_LOAN_RECEIVABLE / CR CASH (owner=CORPORATE). */
    public static AccountEntry corporateLoanDisbursed(String stockCode, String loanId, BigDecimal principal) {
        return of(OwnerType.CORPORATE, stockCode,
                GlAccount.CORPORATE_LOAN_RECEIVABLE, GlAccount.CASH, principal,
                "CORP_LOAN_DISBURSED", loanId, TOPIC_CORPORATE_LOAN_DISBURSED);
    }

    /** 투자 집행 → DR INVESTMENT_ASSET / CR CASH. */
    public static AccountEntry investmentExecuted(String sellerId, String orderId, BigDecimal amount) {
        return of(OwnerType.SELLER, sellerId,
                GlAccount.INVESTMENT_ASSET, GlAccount.CASH, amount,
                "INVESTMENT_EXECUTED", orderId, TOPIC_INVESTMENT_EXECUTED);
    }

    /** 영속 상태에서 도메인으로 복원 (id·occurredAt 포함). */
    public static AccountEntry reconstitute(Long id, OwnerType ownerType, String ownerId,
                                            GlAccount debitAccount, GlAccount creditAccount, BigDecimal amount,
                                            String refType, String refId, String sourceTopic,
                                            LocalDateTime occurredAt) {
        return new AccountEntry(id, ownerType, ownerId, debitAccount, creditAccount, amount,
                refType, refId, sourceTopic, occurredAt);
    }

    public Long getId() { return id; }
    public OwnerType getOwnerType() { return ownerType; }
    public String getOwnerId() { return ownerId; }
    public GlAccount getDebitAccount() { return debitAccount; }
    public GlAccount getCreditAccount() { return creditAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getRefType() { return refType; }
    public String getRefId() { return refId; }
    public String getSourceTopic() { return sourceTopic; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
