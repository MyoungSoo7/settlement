package github.lms.lemuel.deposit.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 예치금 원장 엔트리 (append-only, 순수 POJO).
 *
 * <p>모든 잔고 변경은 이 엔트리로 기록된다. 실체화된 계좌 잔고와
 * 엔트리 합계가 일치해야 대사(reconciliation) 가 가능하다.
 *
 * <p>(account_id, entry_type, reference_type, reference_id, offset_sequence) 의
 * UNIQUE 제약이 멱등 2차 방어선이다.
 */
public class DepositEntry {

    private Long id;
    private final Long accountId;
    private final DepositEntryType entryType;
    private final BigDecimal amount;
    /** 참조 도메인 식별자 (예: settlementId, payoutId, authorizationId). */
    private final String referenceId;
    /** 참조 도메인 유형 (예: "SETTLEMENT", "PAYOUT", "CARD_AUTHORIZATION"). */
    private final String referenceType;
    /** 상계 순서 — 동일 참조에 대한 복수 상계를 구분한다 (기본 0). */
    private final int offsetSequence;
    /**
     * 상계 엔트리의 source hold ID (nullable).
     * null 이면 hold 없는 늦은 청구에 의한 상계임을 식별하는 감사 표식.
     */
    private final Long sourceHoldId;
    private final LocalDateTime createdAt;

    private DepositEntry(Long id, Long accountId, DepositEntryType entryType,
                          BigDecimal amount, String referenceId, String referenceType,
                          int offsetSequence, Long sourceHoldId, LocalDateTime createdAt) {
        this.id = id;
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.entryType = Objects.requireNonNull(entryType, "entryType");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.referenceId = referenceId;
        this.referenceType = referenceType;
        this.offsetSequence = offsetSequence;
        this.sourceHoldId = sourceHoldId;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    public static DepositEntry of(Long accountId, DepositEntryType entryType,
                                   BigDecimal amount, String referenceId, String referenceType) {
        return new DepositEntry(null, accountId, entryType, amount,
                referenceId, referenceType, 0, null, LocalDateTime.now());
    }

    public static DepositEntry ofOffset(Long accountId, BigDecimal amount,
                                         String referenceId, String referenceType,
                                         int offsetSequence, Long sourceHoldId) {
        return new DepositEntry(null, accountId, DepositEntryType.OFFSET, amount,
                referenceId, referenceType, offsetSequence, sourceHoldId, LocalDateTime.now());
    }

    public static DepositEntry rehydrate(Long id, Long accountId, DepositEntryType entryType,
                                          BigDecimal amount, String referenceId, String referenceType,
                                          int offsetSequence, Long sourceHoldId, LocalDateTime createdAt) {
        return new DepositEntry(id, accountId, entryType, amount, referenceId, referenceType,
                offsetSequence, sourceHoldId, createdAt);
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("이미 ID 가 할당된 엔트리입니다");
        this.id = Objects.requireNonNull(id);
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public DepositEntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public String getReferenceId() { return referenceId; }
    public String getReferenceType() { return referenceType; }
    public int getOffsetSequence() { return offsetSequence; }
    public Long getSourceHoldId() { return sourceHoldId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
