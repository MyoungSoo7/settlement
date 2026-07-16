package github.lms.lemuel.ledger.domain;

import github.lms.lemuel.common.ledger.LedgerInvariants;
import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.ledger.domain.exception.InvalidLedgerStateException;
import github.lms.lemuel.ledger.domain.exception.LedgerInvariantViolationException;
import github.lms.lemuel.ledger.domain.exception.UnbalancedLedgerEntryException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 원장 항목 — 한 쌍의 분개(차변+대변)를 나타내는 도메인 객체.
 *
 * <p>한 비즈니스 거래(예: 정산 1건)는 동일 {@code referenceId}·{@code referenceType} 로
 * 묶이는 여러 LedgerEntry row 로 표현될 수 있다. 각 row 는
 * {@code (debitAccount, creditAccount, amount)} 한 쌍을 기록하며 amount 는 항상 양수다.
 *
 * <p>불변(Immutable) 원칙: 일단 작성된 entry 는 status 전이({@link #post()}·{@link #reverse()})를
 * 제외하고 수정하지 않는다. 식별·금액 필드({@code referenceId}·계정·{@code amount}·{@code settlementDate}
 * 등)는 {@code final} 로 못박아 생성 후 재할당 자체를 컴파일 단에서 봉인한다. 정정이 필요하면 역분개 후
 * 신규 entry 를 추가한다. public setter 는 두지 않으며, 영속 레코드 복원은 {@link #rehydrate} 팩토리로만 수행한다.
 */
public class LedgerEntry {

    // ── 불변 식별·금액 필드 (생성 후 재할당 불가) ─────────────────────────
    private final Long referenceId;
    private final ReferenceType referenceType;
    private final LedgerEntryType entryType;
    private final AccountType debitAccount;
    private final AccountType creditAccount;
    private final BigDecimal amount;
    private final LocalDate settlementDate;
    private final String memo;
    private final LocalDateTime createdAt;

    // ── 가변 필드 (PK 1회 부여·상태 전이 시에만 변경) ────────────────────
    private Long id;
    private LedgerStatus status;
    private LocalDateTime postedAt;
    private LocalDateTime updatedAt;

    /**
     * 정본 생성자 — 두 팩토리({@link #of}·{@link #rehydrate})만 통과한다. 불변 필드를 여기서 못박아
     * 이후 어떤 경로로도 재할당할 수 없게 한다.
     */
    private LedgerEntry(Long id, Long referenceId, ReferenceType referenceType, LedgerEntryType entryType,
                        AccountType debitAccount, AccountType creditAccount, BigDecimal amount,
                        LedgerStatus status, LocalDate settlementDate, LocalDateTime postedAt, String memo,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.referenceId = referenceId;
        this.referenceType = referenceType;
        this.entryType = entryType;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amount = amount;
        this.status = status;
        this.settlementDate = settlementDate;
        this.postedAt = postedAt;
        this.memo = memo;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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
        LocalDateTime now = LocalDateTime.now();
        BigDecimal normalized = amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : null;
        LedgerEntry entry = new LedgerEntry(null, referenceId, referenceType, entryType,
                debitAccount, creditAccount, normalized, LedgerStatus.PENDING, settlementDate,
                null, memo, now, now);
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
        return new LedgerEntry(id, referenceId, referenceType, entryType, debitAccount, creditAccount,
                amount, status, settlementDate, postedAt, memo, createdAt, updatedAt);
    }

    /**
     * 정산 확정(DONE) → 원장 균형 분개 쌍 생성. 차1·대1 페어링과 그 전제 불변식(정산 상태·구성적 금액 균형)을
     * 도메인이 구성적으로 강제하는 정본 팩토리다(account {@code AccountEntry} 정적 팩토리와 동형).
     *
     * <ul>
     *   <li>row1: Dr ACCOUNTS_PAYABLE / Cr REVENUE = netAmount (셀러 미지급 + 매출 인식)</li>
     *   <li>row2: Dr COMMISSION_EXPENSE / Cr COMMISSION_REVENUE = commission (수수료 비용/수익 인식)</li>
     * </ul>
     *
     * <p>각 row 는 즉시 {@link #post()} 되어 POSTED 로 반환된다. 금액이 0 인 row 는 분개가 성립하지 않으므로
     * 생략한다(수수료 0 이면 1 row). 금액 검증·합산은 공용 {@link Money} VO(scale 2 HALF_UP)로 수행한다.
     *
     * @throws LedgerInvariantViolationException 정산이 DONE 이 아니거나, 구성적 균형
     *                                           (payment = net + commission)이 깨진 경우
     */
    public static List<LedgerEntry> balancedPairForSettlement(
            Long settlementId, String settlementStatus,
            BigDecimal paymentAmount, BigDecimal commission, BigDecimal netAmount,
            LocalDate settlementDate) {

        // Phase 2 는 확정분만 원장에 반영한다 — DONE 이 아닌 정산의 분개 요청은 거부.
        if (!"DONE".equals(settlementStatus)) {
            throw new LedgerInvariantViolationException(
                    "Settlement " + settlementId + " is not DONE (status=" + settlementStatus + ")");
        }

        Money net = Money.of(nullSafe(netAmount));
        Money commissionMoney = Money.of(nullSafe(commission));
        Money payment = Money.of(nullSafe(paymentAmount));

        // 구성적 균형 불변식 — payment = net + commission (원 결제액 = 실지급액 + 수수료).
        Money sum = net.plus(commissionMoney);
        if (payment.isPositive() && !sum.equals(payment)) {
            throw new LedgerInvariantViolationException(
                    "Settlement " + settlementId + " amount mismatch: payment=" + payment.toBigDecimal()
                            + " net+commission=" + sum.toBigDecimal());
        }

        List<LedgerEntry> pair = new ArrayList<>(2);

        // row 1 — 매출/미지급금
        if (net.isPositive()) {
            LedgerEntry entry = of(
                    settlementId, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CONFIRMED,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    net.toBigDecimal(), settlementDate,
                    "정산 확정 — 셀러 미지급 / 매출 인식");
            entry.post();
            pair.add(entry);
        }

        // row 2 — 수수료
        if (commissionMoney.isPositive()) {
            LedgerEntry entry = of(
                    settlementId, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CONFIRMED,
                    AccountType.COMMISSION_EXPENSE, AccountType.COMMISSION_REVENUE,
                    commissionMoney.toBigDecimal(), settlementDate,
                    "정산 확정 — 수수료 인식");
            entry.post();
            pair.add(entry);
        }

        return pair;
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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
        // 구성적 균형(차변≠대변 + 양수 금액)은 공용 LedgerInvariants 단일 출처로 강제한다.
        LedgerInvariants.requireDistinctAccounts(debitAccount, creditAccount,
                () -> new UnbalancedLedgerEntryException(debitAccount));
        LedgerInvariants.requirePositiveAmount(amount,
                () -> new LedgerInvariantViolationException("amount 는 양수여야 합니다: " + amount));
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
