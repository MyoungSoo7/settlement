package github.lms.lemuel.ledger.domain;

import github.lms.lemuel.ledger.domain.exception.InvalidLedgerStateException;
import github.lms.lemuel.ledger.domain.exception.LedgerInvariantViolationException;
import github.lms.lemuel.ledger.domain.exception.UnbalancedLedgerEntryException;

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
 * <p>불변(Immutable) 원칙: 일단 작성된 entry 는 status 전이({@link #post()}·{@link #reverse()})를
 * 제외하고 수정하지 않는다. 정정이 필요하면 역분개 후 신규 entry 를 추가한다. public setter 는 두지
 * 않으며, 영속 레코드 복원은 {@link #rehydrate} 팩토리로만 수행한다.
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

    private LedgerEntry() {
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

    /**
     * 영속 레코드 복원 전용(어댑터의 toDomain 에서만 호출). 저장된 필드를 그대로 재구성한다 —
     * 이미 전기(POSTED)·역분개(REVERSED)된 상태도 그대로 살려야 하므로 {@link #validate()} 를 재실행하지 않는다.
     */
    public static LedgerEntry rehydrate(Long id, Long referenceId, ReferenceType referenceType,
                                        LedgerEntryType entryType, AccountType debitAccount,
                                        AccountType creditAccount, BigDecimal amount,
                                        LedgerStatus status, LocalDate settlementDate,
                                        LocalDateTime postedAt, String memo,
                                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        LedgerEntry entry = new LedgerEntry();
        entry.id = id;
        entry.referenceId = referenceId;
        entry.referenceType = referenceType;
        entry.entryType = entryType;
        entry.debitAccount = debitAccount;
        entry.creditAccount = creditAccount;
        entry.amount = amount;
        entry.status = status;
        entry.settlementDate = settlementDate;
        entry.postedAt = postedAt;
        entry.memo = memo;
        entry.createdAt = createdAt;
        entry.updatedAt = updatedAt;
        return entry;
    }

    private void validate() {
        if (referenceId == null || referenceId <= 0) {
            throw new LedgerInvariantViolationException("referenceId 는 양수여야 합니다: " + referenceId);
        }
        if (referenceType == null) {
            throw new LedgerInvariantViolationException("referenceType 필수");
        }
        if (entryType == null) {
            throw new LedgerInvariantViolationException("entryType 필수");
        }
        if (debitAccount == null || creditAccount == null) {
            throw new LedgerInvariantViolationException("debitAccount, creditAccount 모두 필수");
        }
        if (debitAccount == creditAccount) {
            throw new UnbalancedLedgerEntryException(debitAccount);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new LedgerInvariantViolationException("amount 는 양수여야 합니다: " + amount);
        }
        if (settlementDate == null) {
            throw new LedgerInvariantViolationException("settlementDate 필수");
        }
    }

    /**
     * 영속 후 DB 가 부여한 PK 를 1회만 주입(write-once). setter 우회를 막기 위해 재부여를 차단한다.
     */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여할 수 있습니다");
        }
        this.id = id;
    }

    // ========== 상태 전이 ==========

    /** PENDING → POSTED. 전기(공식 회계 반영) 완료. */
    public void post() {
        if (!status.canTransitionTo(LedgerStatus.POSTED)) {
            throw new InvalidLedgerStateException(status, LedgerStatus.POSTED);
        }
        this.status = LedgerStatus.POSTED;
        this.postedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** PENDING/POSTED → REVERSED. 역분개로 인해 원 entry 무효화 마킹. */
    public void reverse() {
        if (!status.canTransitionTo(LedgerStatus.REVERSED)) {
            throw new InvalidLedgerStateException(status, LedgerStatus.REVERSED);
        }
        this.status = LedgerStatus.REVERSED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isPending()  { return status == LedgerStatus.PENDING; }
    public boolean isPosted()   { return status == LedgerStatus.POSTED; }
    public boolean isReversed() { return status == LedgerStatus.REVERSED; }

    // ========== Getters ==========

    public Long getId() { return id; }

    public Long getReferenceId() { return referenceId; }

    public ReferenceType getReferenceType() { return referenceType; }

    public LedgerEntryType getEntryType() { return entryType; }

    public AccountType getDebitAccount() { return debitAccount; }

    public AccountType getCreditAccount() { return creditAccount; }

    public BigDecimal getAmount() { return amount; }

    public LedgerStatus getStatus() { return status; }

    public LocalDate getSettlementDate() { return settlementDate; }

    public LocalDateTime getPostedAt() { return postedAt; }

    public String getMemo() { return memo; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
