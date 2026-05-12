package github.lms.lemuel.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 원장 항목 — 한 쌍의 분개(차변+대변)를 나타내는 도메인 객체.
 *
 * <p>한 비즈니스 거래(예: 정산 1건)는 동일 {@code referenceId}·{@code referenceType} 로
 * 묶이는 여러 LedgerEntry row 로 표현될 수 있다. 각 row 는
 * {@code (debitAccount, creditAccount, amount)} 한 쌍을 기록하며 amount 는 항상 양수다.
 *
 * <p>불변(Immutable) 원칙: 일단 작성된 entry 는 status 전이를 제외하고 수정하지 않는다.
 * 정정이 필요하면 역분개({@link #reverse()}) 후 신규 entry 를 추가한다.
 */
public class LedgerEntry {

    private Long id;
    private Long referenceId;
    private ReferenceType referenceType;
    private LedgerEntryType entryType;
    private AccountType debitAccount;
    private AccountType creditAccount;
    private BigDecimal amount;
    private LedgerStatus status;
    private LocalDate settlementDate;
    private LocalDateTime postedAt;
    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public LedgerEntry() {
        this.status = LedgerStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 신규 분개 작성. 항상 PENDING 상태로 시작.
     *
     * @param amount 거래 금액. 반드시 {@code > 0}. 차/대 부호는 account 로 결정한다.
     */
    public static LedgerEntry of(Long referenceId, ReferenceType referenceType,
                                  LedgerEntryType entryType,
                                  AccountType debitAccount, AccountType creditAccount,
                                  BigDecimal amount, LocalDate settlementDate, String memo) {
        LedgerEntry entry = new LedgerEntry();
        entry.referenceId = referenceId;
        entry.referenceType = referenceType;
        entry.entryType = entryType;
        entry.debitAccount = debitAccount;
        entry.creditAccount = creditAccount;
        entry.amount = amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : null;
        entry.settlementDate = settlementDate;
        entry.memo = memo;

        entry.validate();
        return entry;
    }

    private void validate() {
        if (referenceId == null || referenceId <= 0) {
            throw new IllegalArgumentException("referenceId 는 양수여야 합니다: " + referenceId);
        }
        if (referenceType == null) {
            throw new IllegalArgumentException("referenceType 필수");
        }
        if (entryType == null) {
            throw new IllegalArgumentException("entryType 필수");
        }
        if (debitAccount == null || creditAccount == null) {
            throw new IllegalArgumentException("debitAccount, creditAccount 모두 필수");
        }
        if (debitAccount == creditAccount) {
            throw new IllegalArgumentException(
                    "debit 과 credit 은 서로 다른 계정이어야 합니다: " + debitAccount);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount 는 양수여야 합니다: " + amount);
        }
        if (settlementDate == null) {
            throw new IllegalArgumentException("settlementDate 필수");
        }
    }

    // ========== 상태 전이 ==========

    /** PENDING → POSTED. 전기(공식 회계 반영) 완료. */
    public void post() {
        if (!status.canTransitionTo(LedgerStatus.POSTED)) {
            throw new IllegalStateException(
                    String.format("Cannot post. Current status: %s", status));
        }
        this.status = LedgerStatus.POSTED;
        this.postedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** PENDING/POSTED → REVERSED. 역분개로 인해 원 entry 무효화 마킹. */
    public void reverse() {
        if (!status.canTransitionTo(LedgerStatus.REVERSED)) {
            throw new IllegalStateException(
                    String.format("Cannot reverse. Current status: %s", status));
        }
        this.status = LedgerStatus.REVERSED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isPending()  { return status == LedgerStatus.PENDING; }
    public boolean isPosted()   { return status == LedgerStatus.POSTED; }
    public boolean isReversed() { return status == LedgerStatus.REVERSED; }

    // ========== Getters / Setters ==========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getReferenceId() { return referenceId; }
    public void setReferenceId(Long referenceId) { this.referenceId = referenceId; }

    public ReferenceType getReferenceType() { return referenceType; }
    public void setReferenceType(ReferenceType referenceType) { this.referenceType = referenceType; }

    public LedgerEntryType getEntryType() { return entryType; }
    public void setEntryType(LedgerEntryType entryType) { this.entryType = entryType; }

    public AccountType getDebitAccount() { return debitAccount; }
    public void setDebitAccount(AccountType debitAccount) { this.debitAccount = debitAccount; }

    public AccountType getCreditAccount() { return creditAccount; }
    public void setCreditAccount(AccountType creditAccount) { this.creditAccount = creditAccount; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LedgerStatus getStatus() { return status; }
    public void setStatus(LedgerStatus status) { this.status = status; }

    public LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }

    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime postedAt) { this.postedAt = postedAt; }

    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
