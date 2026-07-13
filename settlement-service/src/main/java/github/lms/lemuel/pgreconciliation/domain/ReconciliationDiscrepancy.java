package github.lms.lemuel.pgreconciliation.domain;

import github.lms.lemuel.pgreconciliation.domain.exception.InvalidReconciliationStateException;
import github.lms.lemuel.pgreconciliation.domain.exception.PgReconciliationInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * PG 파일 vs 내부 결제 1건의 불일치를 나타내는 도메인 모델.
 *
 * <p>도메인 모델 = 순수 POJO (헥사고날 원칙). JPA 엔티티는 별도.
 *
 * <p>상태 전이:
 * <pre>
 *   PENDING ──운영자 승인→ APPROVED
 *           ──운영자 무시→ REJECTED
 *           ──시스템 자동→ AUTO_CORRECTED  (ROUNDING_DIFF 만)
 * </pre>
 */
public class ReconciliationDiscrepancy {

    private Long id;
    private final Long runId;
    private final DiscrepancyType type;
    private final Long paymentId;            // null 이면 MISSING_INTERNAL
    private final String pgTransactionId;    // null 이면 MISSING_PG
    private final BigDecimal internalAmount;
    private final BigDecimal pgAmount;
    private final BigDecimal difference;     // pgAmount - internalAmount (signed)
    private DiscrepancyStatus status;
    private LocalDateTime resolvedAt;
    private String resolvedBy;
    private String note;
    private final LocalDateTime createdAt;

    /**
     * 신규 (PENDING 또는 ROUNDING_DIFF 의 경우 AUTO_CORRECTED 직행) 생성자.
     */
    public static ReconciliationDiscrepancy newDiscrepancy(
            Long runId, DiscrepancyType type, Long paymentId, String pgTransactionId,
            BigDecimal internalAmount, BigDecimal pgAmount) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(type, "type");

        BigDecimal diff = computeDifference(internalAmount, pgAmount);
        DiscrepancyStatus initialStatus = type.isAutoCorrectable()
                ? DiscrepancyStatus.AUTO_CORRECTED
                : DiscrepancyStatus.PENDING;
        LocalDateTime resolvedAt = type.isAutoCorrectable() ? LocalDateTime.now() : null;
        String resolvedBy = type.isAutoCorrectable() ? "SYSTEM" : null;

        return new ReconciliationDiscrepancy(
                null, runId, type, paymentId, pgTransactionId,
                internalAmount, pgAmount, diff, initialStatus,
                resolvedAt, resolvedBy, null, LocalDateTime.now()
        );
    }

    public static ReconciliationDiscrepancy rehydrate(
            Long id, Long runId, DiscrepancyType type, Long paymentId, String pgTransactionId,
            BigDecimal internalAmount, BigDecimal pgAmount, BigDecimal difference,
            DiscrepancyStatus status, LocalDateTime resolvedAt, String resolvedBy,
            String note, LocalDateTime createdAt) {
        return new ReconciliationDiscrepancy(id, runId, type, paymentId, pgTransactionId,
                internalAmount, pgAmount, difference, status, resolvedAt, resolvedBy, note, createdAt);
    }

    private ReconciliationDiscrepancy(Long id, Long runId, DiscrepancyType type,
                                      Long paymentId, String pgTransactionId,
                                      BigDecimal internalAmount, BigDecimal pgAmount,
                                      BigDecimal difference,
                                      DiscrepancyStatus status,
                                      LocalDateTime resolvedAt, String resolvedBy,
                                      String note, LocalDateTime createdAt) {
        this.id = id;
        this.runId = runId;
        this.type = type;
        this.paymentId = paymentId;
        this.pgTransactionId = pgTransactionId;
        this.internalAmount = internalAmount;
        this.pgAmount = pgAmount;
        this.difference = difference;
        this.status = status;
        this.resolvedAt = resolvedAt;
        this.resolvedBy = resolvedBy;
        this.note = note;
        this.createdAt = createdAt;
    }

    private static BigDecimal computeDifference(BigDecimal internalAmount, BigDecimal pgAmount) {
        BigDecimal i = internalAmount == null ? BigDecimal.ZERO : internalAmount;
        BigDecimal p = pgAmount == null ? BigDecimal.ZERO : pgAmount;
        return p.subtract(i);
    }

    /**
     * 운영자가 승인 — 후속 SettlementAdjustment(역정산) 생성 트리거가 됨.
     */
    public void approve(String operatorId, String note) {
        if (status != DiscrepancyStatus.PENDING) {
            throw new InvalidReconciliationStateException(status, DiscrepancyStatus.APPROVED);
        }
        this.status = DiscrepancyStatus.APPROVED;
        this.resolvedBy = operatorId;
        this.resolvedAt = LocalDateTime.now();
        this.note = note;
    }

    /**
     * 운영자가 무시 결정 — 통계/감사 로그에는 남되 보정 처리는 하지 않음.
     */
    public void reject(String operatorId, String reasonNote) {
        if (status != DiscrepancyStatus.PENDING) {
            throw new InvalidReconciliationStateException(status, DiscrepancyStatus.REJECTED);
        }
        if (reasonNote == null || reasonNote.isBlank()) {
            throw new PgReconciliationInvariantViolationException("거절 사유는 필수 (감사 추적용)");
        }
        this.status = DiscrepancyStatus.REJECTED;
        this.resolvedBy = operatorId;
        this.resolvedAt = LocalDateTime.now();
        this.note = reasonNote;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여 가능");
        }
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public DiscrepancyType getType() { return type; }
    public Long getPaymentId() { return paymentId; }
    public String getPgTransactionId() { return pgTransactionId; }
    public BigDecimal getInternalAmount() { return internalAmount; }
    public BigDecimal getPgAmount() { return pgAmount; }
    public BigDecimal getDifference() { return difference; }
    public DiscrepancyStatus getStatus() { return status; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public String getNote() { return note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
