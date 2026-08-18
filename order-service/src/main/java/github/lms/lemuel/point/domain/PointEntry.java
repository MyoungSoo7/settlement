package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 포인트 원장 엔트리 — append-only. 모든 잔고 변경은 여기에 한 건으로 남는다.
 *
 * <p>금액은 <b>언제나 양수</b>이고 잔고에 미치는 방향은 {@link PointEntryType} 이 정한다
 * (deposit_entries 와 같은 규약 — 부호를 금액에 넣지 않는다).
 *
 * <p>핵심 불변식은 <b>엔트리 금액 = 로트 배분 합계</b> 하나다. 배분({@link PointLotConsumption})은
 * 소비만이 아니라 적립·복원·소멸까지 <b>이 엔트리가 건드린 로트와 금액</b>을 뜻한다. 이 상세가
 * 없으면 환불 때 어느 로트로 되돌릴지 알 수 없고, 잔고 요약과 로트 합계의 대사도 불가능해진다.
 *
 * <p>{@code sequence} 는 같은 {@code (type, referenceType, referenceId)} 가 정당하게 반복될 때
 * L3 멱등 자연키를 구분한다 — 같은 tender 를 여러 번 부분 환불하는 경우가 대표적이다.
 */
public class PointEntry {

    private Long id;
    private final Long accountId;
    private final PointEntryType type;
    private final BigDecimal amount;
    private final String referenceType;
    private final String referenceId;
    private final int sequence;
    private final String memo;
    private final String createdBy;
    private final OffsetDateTime createdAt;
    private final List<PointLotConsumption> allocations;

    private PointEntry(Long id, Long accountId, PointEntryType type, BigDecimal amount,
                       String referenceType, String referenceId, int sequence, String memo,
                       String createdBy, OffsetDateTime createdAt, List<PointLotConsumption> allocations) {
        this.id = id;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.sequence = sequence;
        this.memo = memo;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.allocations = allocations;
    }

    /**
     * 모든 팩토리가 지나는 유일한 생성 경로 — 금액 규약과 배분 합계 불변식을 여기서 한 번에 강제한다.
     * 팩토리를 우회해 생성자를 직접 부르는 길은 없다(private).
     */
    private static PointEntry create(PointEntryType type, Long accountId, BigDecimal amount,
                                     String referenceType, String referenceId, int sequence,
                                     List<PointLotConsumption> allocations, String createdBy, String memo,
                                     OffsetDateTime createdAt) {
        BigDecimal value = PointAmounts.requirePoint(amount, type.name().toLowerCase());
        if (allocations == null || allocations.isEmpty()) {
            throw new PointInvariantViolationException(
                    "불변식 위반: 로트 배분이 없는 엔트리는 만들 수 없다 (type=" + type + ")");
        }
        BigDecimal allocated = allocations.stream()
                .map(PointLotConsumption::amount)
                .reduce(PointAmounts.zero(), BigDecimal::add);
        if (allocated.compareTo(value) != 0) {
            throw new PointInvariantViolationException(
                    "불변식 위반: 엔트리 금액(" + value + ") != 로트 배분 합계(" + allocated + ")");
        }
        return new PointEntry(null, accountId, type, value, referenceType, referenceId, sequence,
                memo, createdBy, createdAt, List.copyOf(allocations));
    }

    public static PointEntry grant(Long accountId, BigDecimal amount, String referenceType,
                                   String referenceId, int sequence,
                                   List<PointLotConsumption> allocations, String createdBy, String memo) {
        return create(PointEntryType.GRANT, accountId, amount, referenceType, referenceId,
                sequence, allocations, createdBy, memo, OffsetDateTime.now());
    }

    public static PointEntry use(Long accountId, BigDecimal amount, String referenceType,
                                 String referenceId, int sequence,
                                 List<PointLotConsumption> allocations, String createdBy) {
        return create(PointEntryType.USE, accountId, amount, referenceType, referenceId,
                sequence, allocations, createdBy, null, OffsetDateTime.now());
    }

    public static PointEntry restore(Long accountId, BigDecimal amount, String referenceType,
                                     String referenceId, int sequence,
                                     List<PointLotConsumption> allocations, String createdBy) {
        return create(PointEntryType.RESTORE, accountId, amount, referenceType, referenceId,
                sequence, allocations, createdBy, null, OffsetDateTime.now());
    }

    public static PointEntry expire(Long accountId, BigDecimal amount, String referenceType,
                                    String referenceId, int sequence,
                                    List<PointLotConsumption> allocations, String createdBy) {
        return create(PointEntryType.EXPIRE, accountId, amount, referenceType, referenceId,
                sequence, allocations, createdBy, null, OffsetDateTime.now());
    }

    public static PointEntry revoke(Long accountId, BigDecimal amount, String referenceType,
                                    String referenceId, int sequence,
                                    List<PointLotConsumption> allocations, String createdBy) {
        return create(PointEntryType.REVOKE, accountId, amount, referenceType, referenceId,
                sequence, allocations, createdBy, null, OffsetDateTime.now());
    }

    /**
     * 영속 상태로부터 복원. 저장된 행도 같은 불변식을 통과해야 한다 — 저장소가 깨진 행을 돌려주면
     * 그 사실이 조용히 흡수되지 않고 즉시 드러나야 한다.
     */
    public static PointEntry rehydrate(Long id, Long accountId, PointEntryType type, BigDecimal amount,
                                       String referenceType, String referenceId, int sequence,
                                       String memo, String createdBy, OffsetDateTime createdAt,
                                       List<PointLotConsumption> allocations) {
        PointEntry entry = create(type, accountId, amount, referenceType, referenceId,
                sequence, allocations, createdBy, memo, createdAt);
        entry.id = id;
        return entry;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new PointInvariantViolationException(
                    "append-only 원장 엔트리의 ID 를 다시 할당할 수 없습니다: " + this.id);
        }
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public PointEntryType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public int getSequence() { return sequence; }
    public String getMemo() { return memo; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public List<PointLotConsumption> getAllocations() { return allocations; }
}
